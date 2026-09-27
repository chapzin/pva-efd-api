import br.gov.serpro.vepxml.persistencia.IPersistencia;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

// Correções dos achados de malha e do cruzamento como operações do efd_editar. Não grava: o que depende de decisão do
// auditor volta como pendência, e o que o servidor não sabe corrigir sozinho vem explicado.
final class Correcao {
  private Correcao() {}

  static final Set<String> SEM_DIREITO = Set.of("CREDITO_USO_CONSUMO", "CREDITO_CST_SEM_DIREITO");
  static final Set<String> ACIMA_DO_XML = Set.of("CREDITO_SIMPLES_ACIMA_PERMITIDO", "CREDITO_MAIOR_QUE_DESTACADO");
  static final Set<String> SITUACAO = Set.of("CANCELADA_ESCRITURADA", "DENEGADA_ESCRITURADA");
  static final String CHAVE = "CHAVE_NAO_AUTORIZADA_ESCRITURADA";
  static final Set<String> SO_CAMPOS_DE_CHAVE = Set.of("IND_OPER", "IND_EMIT", "COD_MOD", "SER", "NUM_DOC", "CHV_NFE", "CHV_CTE");
  static final Map<String, String> SEM_PROPOSTA = Map.ofEntries(
      Map.entry("OPERACAO_INVERTIDA", "trocar IND_OPER muda CFOP, participante e apuração: refaça o documento a partir do XML"),
      Map.entry("CHAVE_DE_TERCEIRO", "nota em que a empresa não é parte: confirme com o contribuinte antes de excluir o C100"),
      Map.entry("CTE_SEM_SER_TOMADOR", "glosa no D190 e no D100: ajuste VL_BC_ICMS/VL_ICMS com efd_editar"),
      Map.entry("XML_NAO_ESCRITURADO", "use efd_propor_nfe com o de-para do COD_ITEM"),
      Map.entry("VALOR_DIVERGENTE_DO_XML", "confira desconto, frete e IPI do documento contra o XML antes de alterar VL_DOC"),
      Map.entry("XML_CHAVE_DIFERE_PROTOCOLO", "informativo: a EFD já usa a chave autorizada; guarde o XML que a SEFAZ recebeu"),
      Map.entry("ESCRITURADO_SEM_XML", "informativo: colete o XML que falta na pasta antes de concluir o cruzamento"),
      Map.entry("CREDITO_ATIVO_DIRETO", "crédito de ativo vai pelo CIAP (bloco G + E111): zere o C170 só depois de montar o G125"),
      Map.entry("DEBITO_EM_SAIDA_ST", "confira se a mercadoria é de ST na UF antes de zerar o débito ou mudar o CST"),
      Map.entry("ESTORNO_DIFERE_DEBITO_ST", "ajuste o valor do E111 de estorno ao ICMS destacado nas saídas com ST"),
      Map.entry("DIFAL_SEM_AJUSTE", "inclua o E300/E310 da UF de destino com o DIFAL das saídas a não contribuinte"),
      Map.entry("INVENTARIO_AUSENTE_FEVEREIRO", "o H005 do inventário de 31/12 vai na EFD de fevereiro: use o levantamento de estoque"),
      Map.entry("INVENTARIO_ZERADO", "inventário zerado com movimento: refaça o H010 pelo levantamento de estoque"));

  // Um C170 pode ser alvo de dois achados (uso e consumo e crédito do Simples): vale o menor crédito.
  static final class Alvo {
    final long id;
    BigDecimal bc, aliq, icms;
    String regra, cst;
    final List<String> achados = new ArrayList<>();

    Alvo(long id) {
      this.id = id;
    }

    void propor(String codigo, BigDecimal bc, BigDecimal aliq, BigDecimal icms, String cst, String regra) {
      if (!achados.contains(codigo)) achados.add(codigo);
      if (this.icms != null && this.icms.compareTo(icms) <= 0) return;
      this.bc = bc;
      this.aliq = aliq;
      this.icms = icms;
      this.cst = cst;
      this.regra = regra;
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> montar(IPersistencia per, List<Map<String, Object>> achados, String filtro, Path pastaXml,
      int pendentes) throws Exception {
    Map<String, Alvo> c170 = new LinkedHashMap<>(), c190 = new LinkedHashMap<>();
    Map<Long, String> docsC170 = new LinkedHashMap<>();
    Map<String, String> excluidos = new LinkedHashMap<>();
    List<Map<String, Object>> ops = new ArrayList<>(), pend = new ArrayList<>(), cobertura = new ArrayList<>();
    Map<String, Map<String, Object>> xmls = new HashMap<>();
    var parser = Cruzamento.parser();
    boolean analiticos = false;
    if (pendentes > 0) {
      pend.add(p("—", null, pendentes + " edição(ões) não exportadas: os achados são da última validação. Rode efd_gerar_arquivo e"
          + " proponha de novo", true));
    }
    for (Map<String, Object> ach : achados) {
      String cod = String.valueOf(ach.get("codigo"));
      if (filtro != null && !filtro.equals(cod)) continue;
      List<Map<String, Object>> oc = (List<Map<String, Object>>) ach.get("ocorrencias");
      int total = ((Number) ach.get("quantidade")).intValue(), feitas = 0;
      if (oc.size() < total) {
        pend.add(p(cod, null, "o servidor guarda as primeiras " + oc.size() + " de " + total + " ocorrências: corrija estas, gere o"
            + " arquivo e proponha de novo", false));
      }
      String sem = null;
      for (Map<String, Object> o : oc) {
        if (SEM_DIREITO.contains(cod)) {
          Map<String, String> doc = um(per, "SELECT ID, NUM_DOC FROM reg_c100 WHERE LINHA = " + num(o.get("linhaDocumento")));
          if (doc == null) continue;
          long idDoc = Long.parseLong(doc.get("ID"));
          String cfop = String.valueOf(o.get("cfop")), cst = String.valueOf(o.get("cst"));
          List<Map<String, String>> itens = Verificacoes.linhas(per, "SELECT ID, NUM_ITEM, VL_ICMS FROM reg_c170 WHERE ID_PAI = " + idDoc
              + " AND CFOP = " + inteiro(cfop) + " AND CST_ICMS = '" + cst.replace("'", "") + "'");
          String regra = cod.equals("CREDITO_USO_CONSUMO") ? "uso e consumo: sem crédito (LC 87/96, art. 33, I)"
              : "CST " + cst + " não dá crédito (LC 87/96, art. 20 e 23)";
          if (itens.isEmpty()) {
            Map<String, String> r = um(per, "SELECT ID, ALIQ_ICMS FROM reg_c190 WHERE LINHA = " + num(o.get("linha")));
            if (r == null) continue;
            c190.computeIfAbsent(r.get("ID"), k -> new Alvo(Long.parseLong(k))).propor(cod, BigDecimal.ZERO,
                Verificacoes.dec(r.get("ALIQ_ICMS")), BigDecimal.ZERO, null, regra + "; documento sem C170");
          } else {
            for (Map<String, String> it : itens) {
              c170.computeIfAbsent(it.get("ID"), k -> new Alvo(Long.parseLong(k))).propor(cod, BigDecimal.ZERO, BigDecimal.ZERO,
                  BigDecimal.ZERO, null, regra);
              docsC170.put(idDoc, doc.get("NUM_DOC"));
            }
            analiticos = true;
          }
          feitas++;
        } else if (ACIMA_DO_XML.contains(cod)) {
          Map<String, String> doc = um(per, "SELECT ID, NUM_DOC FROM reg_c100 WHERE LINHA = " + num(o.get("linha")));
          if (doc == null) continue;
          long idDoc = Long.parseLong(doc.get("ID"));
          Map<String, Object> itensXml = xmls.computeIfAbsent(String.valueOf(o.get("arquivoXml")), f -> itensDoXml(pastaXml, f, parser));
          List<Map<String, String>> itens = Verificacoes.linhas(per, "SELECT ID, NUM_ITEM, VL_ICMS FROM reg_c170 WHERE ID_PAI = " + idDoc);
          if (itens.isEmpty()) {
            pend.add(p(cod, "NF " + doc.get("NUM_DOC"), "documento sem C170: ajuste o VL_ICMS do C190 ao permitido ("
                + o.getOrDefault("permitido", o.get("destacado")) + ") com efd_editar", true));
            continue;
          }
          boolean mei = "4".equals(String.valueOf(o.get("crt")));
          for (Map<String, String> it : itens) {
            Map<String, BigDecimal> x = (Map<String, BigDecimal>) itensXml.get(String.valueOf(Verificacoes.inteiro(it.get("NUM_ITEM"))));
            if (x == null && !mei) {
              pend.add(p(cod, "NF " + doc.get("NUM_DOC") + " item " + it.get("NUM_ITEM"), "NUM_ITEM sem nItem igual no XML: casamento"
                  + " item a item manual", true));
              continue;
            }
            BigDecimal alvo = mei ? BigDecimal.ZERO : x.get("icms");
            if (Verificacoes.dec(it.get("VL_ICMS")).subtract(alvo).compareTo(Cruzamento.TOLERANCIA) <= 0) continue;
            String regra = mei ? "MEI (CRT 4) não transfere crédito" : cod.equals("CREDITO_MAIOR_QUE_DESTACADO")
                ? "crédito = ICMS destacado no item do XML (LC 87/96, art. 23)" : "crédito = vCredICMSSN do item (LC 123/2006, art. 23)";
            c170.computeIfAbsent(it.get("ID"), k -> new Alvo(Long.parseLong(k))).propor(cod, mei ? BigDecimal.ZERO : x.get("bc"),
                mei ? BigDecimal.ZERO : x.get("aliq"), alvo, null, regra);
            docsC170.put(idDoc, doc.get("NUM_DOC"));
            analiticos = true;
          }
          feitas++;
        } else if (SITUACAO.contains(cod)) {
          String reg = String.valueOf(o.get("registro"));
          if (!reg.equals("C100") && !reg.equals("D100")) {
            pend.add(p(cod, reg + " linha " + o.get("linha"), "cancelamento de " + reg + " fica manual", true));
            continue;
          }
          Map<String, String> doc = um(per, "SELECT * FROM reg_" + reg.toLowerCase() + " WHERE LINHA = " + num(o.get("linha")));
          if (doc == null) continue;
          String sit = cod.equals("CANCELADA_ESCRITURADA") ? "02" : "04";
          if (doc.get("COD_PART") != null && !doc.get("COD_PART").isBlank()) excluidos.put(reg + ":" + doc.get("ID"), doc.get("COD_PART"));
          ops.add(Json.obj("acao", "excluir", "registro", reg, "id", Long.parseLong(doc.get("ID")), "achado", cod,
              "regra", "documento " + (sit.equals("02") ? "cancelado" : "denegado") + " sai com os filhos (C170/C190)"));
          if ("0".equals(doc.get("IND_EMIT"))) {
            Map<String, Object> campos = new LinkedHashMap<>();
            for (String k : doc.keySet()) {
              if (SO_CAMPOS_DE_CHAVE.contains(k) && doc.get(k) != null) campos.put(k, doc.get(k));
            }
            campos.put("COD_SIT", sit);
            ops.add(Json.obj("acao", "incluir", "registro", reg, "pai", Long.parseLong(doc.get("ID_PAI")), "campos", campos, "achado", cod,
                "regra", "emissão própria: fica só com os campos de identificação e COD_SIT " + sit + " (Guia Prático, C100/D100)"));
          }
          feitas++;
        } else if (cod.equals(CHAVE)) {
          String reg = String.valueOf(o.get("registro"));
          Map<String, String> doc = um(per, "SELECT ID FROM reg_" + reg.toLowerCase() + " WHERE LINHA = " + num(o.get("linha")));
          if (doc == null) continue;
          String campo = reg.equals("D100") ? "CHV_CTE" : "CHV_NFE";
          ops.add(Json.obj("acao", "alterar", "registro", reg, "id", Long.parseLong(doc.get("ID")), "campos",
              Json.obj(campo, o.get("chaveAutorizada")), "achado", cod, "regra", "chave do protocolo de autorização (Ajuste SINIEF 07/2005)"));
          pend.add(p(cod, "chave " + o.get("chaveAutorizada"), "confira se a chave autorizada já está escriturada em outro mês:"
              + " se estiver, o documento foi lançado duas vezes", false));
          feitas++;
        } else {
          sem = SEM_PROPOSTA.getOrDefault(cod, "sem regra de correção automática: confira com efd_detalhes");
        }
      }
      if (sem != null) pend.add(p(cod, null, sem, false));
      cobertura.add(Json.obj("codigo", cod, "ocorrencias", total, "propostas", feitas, "valor", ach.get("valorTotal")));
    }
    for (Map.Entry<String, Alvo> e : c170.entrySet()) {
      Alvo a = e.getValue();
      ops.add(Json.obj("acao", "alterar", "registro", "C170", "id", a.id, "campos",
          Json.obj("VL_BC_ICMS", Proposta.valor(a.bc), "ALIQ_ICMS", Proposta.valor(a.aliq), "VL_ICMS", Proposta.valor(a.icms)),
          "achado", String.join(" + ", a.achados), "regra", a.regra));
    }
    for (Map.Entry<String, Alvo> e : c190.entrySet()) {
      Alvo a = e.getValue();
      ops.add(Json.obj("acao", "alterar", "registro", "C190", "id", a.id, "campos",
          Json.obj("VL_BC_ICMS", Proposta.valor(a.bc), "VL_ICMS", Proposta.valor(a.icms)),
          "achado", String.join(" + ", a.achados), "regra", a.regra));
    }
    orfaos(per, excluidos, ops);
    if (!ops.isEmpty()) {
      pend.add(p("E116", null, "o ICMS a recolher muda com a apuração refeita: ajuste o VL_OR do E116 depois do efd_editar", false));
    }
    boolean pronto = !ops.isEmpty() && pend.stream().noneMatch(x -> Boolean.TRUE.equals(x.get("bloqueia")));
    Map<String, Object> r = Json.obj("pronto", pronto, "operacoes", semMeta(ops), "recalcular_analiticos", analiticos,
        "recalcular_apuracao", !ops.isEmpty(), "pendencias", pend, "cobertura", cobertura);
    r.put("tabela", tabela(per, ops, pend, cobertura));
    return r;
  }

  // Participante que só o documento excluído usava vira MSG_REFERENCIADO_COD_PART: sai junto.
  static void orfaos(IPersistencia per, Map<String, String> excluidos, List<Map<String, Object>> ops) throws Exception {
    if (excluidos.isEmpty()) return;
    List<String> tabelas = new ArrayList<>();
    for (Map<String, String> r : Verificacoes.linhas(per, "SELECT TABLE_NAME T FROM INFORMATION_SCHEMA.COLUMNS WHERE COLUMN_NAME = 'COD_PART'"
        + " AND TABLE_SCHEMA = DATABASE()")) {
      String t = r.get("T").toLowerCase();
      if (t.startsWith("reg_") && !t.equals("reg_0150")) tabelas.add(t);
    }
    for (String part : new java.util.LinkedHashSet<>(excluidos.values())) {
      long usos = 0;
      for (String t : tabelas) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) N FROM " + t + " WHERE COD_PART = '" + part.replace("'", "") + "'");
        for (Map.Entry<String, String> e : excluidos.entrySet()) {
          String[] k = e.getKey().split(":");
          if (("reg_" + k[0].toLowerCase()).equals(t)) sql.append(" AND ID <> ").append(Long.parseLong(k[1]));
        }
        usos += Long.parseLong(Verificacoes.linhas(per, sql.toString()).get(0).get("N"));
      }
      Map<String, String> r = usos > 0 ? null : um(per, "SELECT ID FROM reg_0150 WHERE COD_PART = '" + part.replace("'", "") + "'");
      if (r != null) {
        ops.add(Json.obj("acao", "excluir", "registro", "0150", "id", Long.parseLong(r.get("ID")), "achado", "—",
            "regra", "participante " + part + " só aparecia no documento excluído (MSG_REFERENCIADO_COD_PART)"));
      }
    }
  }

  // O efd_editar recusa chaves que não conhece; achado e regra ficam só na tabela.
  static List<Map<String, Object>> semMeta(List<Map<String, Object>> ops) {
    List<Map<String, Object>> l = new ArrayList<>();
    for (Map<String, Object> op : ops) {
      Map<String, Object> m = new LinkedHashMap<>(op);
      m.remove("achado");
      m.remove("regra");
      l.add(m);
    }
    return l;
  }

  @SuppressWarnings("unchecked")
  static String tabela(IPersistencia per, List<Map<String, Object>> ops, List<Map<String, Object>> pend, List<Map<String, Object>> cob)
      throws Exception {
    Tabela tc = Tabela.com("Achado", "Ocorrências", "Com proposta", "Valor");
    for (Map<String, Object> c : cob) tc.linha(c.get("codigo"), c.get("ocorrencias"), c.get("propostas"), c.get("valor"));
    Tabela t = Tabela.com("#", "Ação", "Registro", "ID", "Campos", "Antes", "Achado", "Regra");
    for (int i = 0; i < ops.size(); i++) {
      Map<String, Object> op = ops.get(i);
      Map<String, Object> campos = (Map<String, Object>) op.get("campos");
      String antes = "";
      if ("alterar".equals(op.get("acao")) && campos != null) {
        Map<String, String> r = um(per, "SELECT " + String.join(", ", campos.keySet()) + " FROM reg_"
            + String.valueOf(op.get("registro")).toLowerCase() + " WHERE ID = " + op.get("id"));
        boolean chave = campos.containsKey("CHV_NFE") || campos.containsKey("CHV_CTE");
        if (r != null) antes = String.join(" · ", campos.keySet().stream()
            .map(k -> k + " " + (chave ? r.get(k) : Proposta.valor(Verificacoes.dec(r.get(k))))).toList());
      }
      String depois = campos == null ? "" : String.join(" · ", campos.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList());
      t.linha(i, op.get("acao"), op.get("registro"), op.containsKey("id") ? op.get("id") : "pai " + op.get("pai"), depois, antes,
          op.get("achado"), op.get("regra"));
    }
    Tabela tp = Tabela.com("Achado", "Onde", "O que decidir", "Bloqueia");
    for (Map<String, Object> x : pend) tp.linha(x.get("achado"), x.get("onde"), x.get("motivo"), Boolean.TRUE.equals(x.get("bloqueia")) ? "sim" : "não");
    return Tabela.juntar("Achados cobertos", tc, "Correções propostas (nada foi gravado)", t, "Pendências antes de gravar", tp);
  }

  // Valores do ICMS por nItem, como o destinatário pode se creditar: destacado (vBC/pICMS/vICMS) ou do Simples (pCredSN).
  static Map<String, Object> itensDoXml(Path pasta, String arquivo, javax.xml.parsers.DocumentBuilder parser) {
    Map<String, Object> out = new HashMap<>();
    try {
      Element inf = Cruzamento.primeiro(parser.parse(pasta.resolve(arquivo).toFile()).getDocumentElement(), "infNFe");
      NodeList dets = inf.getElementsByTagName("det");
      for (int i = 0; i < dets.getLength(); i++) {
        Element det = (Element) dets.item(i);
        Element prod = Cruzamento.primeiro(det, "prod");
        Element icms = Cruzamento.primeiroFilho(Cruzamento.primeiro(Cruzamento.primeiro(det, "imposto"), "ICMS"));
        BigDecimal cred = Proposta.n(icms, "vCredICMSSN");
        Map<String, BigDecimal> v = new HashMap<>();
        if (!Proposta.t(icms, "CSOSN").isEmpty()) {
          v.put("bc", cred.signum() > 0 ? Proposta.n(prod, "vProd").subtract(Proposta.n(prod, "vDesc")) : BigDecimal.ZERO);
          v.put("aliq", cred.signum() > 0 ? Proposta.n(icms, "pCredSN") : BigDecimal.ZERO);
          v.put("icms", cred);
        } else {
          v.put("bc", Proposta.n(icms, "vBC"));
          v.put("aliq", Proposta.n(icms, "pICMS"));
          v.put("icms", Proposta.n(icms, "vICMS"));
        }
        out.put(det.getAttribute("nItem").isEmpty() ? String.valueOf(i + 1) : det.getAttribute("nItem"), v);
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("não consegui ler o XML " + arquivo + ": " + e.getMessage());
    }
    return out;
  }

  static Map<String, Object> p(String achado, String onde, String motivo, boolean bloqueia) {
    return Json.obj("achado", achado, "onde", onde, "motivo", motivo, "bloqueia", bloqueia);
  }

  static Map<String, String> um(IPersistencia per, String sql) throws Exception {
    List<Map<String, String>> l = Verificacoes.linhas(per, sql, 1);
    return l.isEmpty() ? null : l.get(0);
  }

  static long num(Object o) {
    if (o instanceof Number n) return n.longValue();
    throw new IllegalArgumentException("ocorrência sem linha do arquivo");
  }

  static int inteiro(String s) {
    return Integer.parseInt(s.replaceAll("[^0-9]", ""));
  }
}
