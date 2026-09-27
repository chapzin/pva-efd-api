import br.gov.serpro.geradorregistro.fachada.GeradorRegistroFachada;
import br.gov.serpro.sped.fiscal.fronteira.edicao.ControleEditarEscrituracao;
import br.gov.serpro.sped.fiscalpva.dominio.entidades.EscrituracaoFiscal;
import br.gov.serpro.sped.fiscalpva.dominio.util.escrituracao.UtilEscrituracao;
import br.gov.serpro.sped.fiscalpva.edicao.util.UtilEdicao;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.fabrica.FabricaControle;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.gerarArquivoEntrega.IControleGerarArquivo;
import br.gov.serpro.sped.fiscalpva.persistencia.PersistenciaFiscalPVA;
import br.gov.serpro.vepxml.edicao.fachada.EdicaoEscrituracao;
import br.gov.serpro.vepxml.edicao.tratamentoexcecao.ITratadorExcecao;
import br.gov.serpro.vepxml.nucleo.descritorescrituracao.DescritorEscrituracao;
import br.gov.serpro.vepxml.nucleo.descritorescrituracao.metadados.MetadadosCampo;
import br.gov.serpro.vepxml.nucleo.descritorescrituracao.metadados.MetadadosRegistro;
import br.gov.serpro.vepxml.nucleo.entidade.Campo;
import br.gov.serpro.vepxml.nucleo.entidade.Registro;
import br.gov.serpro.vepxml.persistencia.dao.registro.IRegistroDAO;
import br.gov.serpro.vepxml.persistencia.iterador.IIteradorRegistro;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Edição da escrituração pelo mesmo caminho da tela do PVA: DAO de registros (que recalcula o HASH de cada linha),
// gerador de registros analíticos/apuração e exportação do TXT a partir do banco, com 0990/9900/9999 recontados.
final class Edicao {
  private Edicao() {}

  static final List<Throwable> falhas = new ArrayList<>();
  static final ITratadorExcecao TRATADOR = falhas::add;

  static MetadadosRegistro meta(DescritorEscrituracao d, String reg) {
    MetadadosRegistro m = reg == null ? null : d.getMetadadosRegistro(reg.toUpperCase());
    if (m == null) throw new IllegalArgumentException("registro " + reg + " não existe no leiaute desta escrituração");
    return m;
  }

  static long id(Map<?, ?> op, String k) {
    Object v = op.get(k);
    if (v == null) throw new IllegalArgumentException("operação sem " + k + ": " + op);
    return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v).trim());
  }

  static Map<String, Object> campos(Registro r) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Campo c : r.getCampos()) m.put(c.getID(), c.getValor());
    return m;
  }

  static Registro ler(IRegistroDAO dao, MetadadosRegistro m, long id) throws Exception {
    Registro r = dao.selecionarRegistro(m, id);
    if (r == null) throw new IllegalArgumentException("registro " + m.getId() + " com ID " + id + " não existe");
    return r;
  }

  static List<MetadadosRegistro> filhos(DescritorEscrituracao d, MetadadosRegistro pai) {
    List<MetadadosRegistro> l = new ArrayList<>();
    for (MetadadosRegistro m : d.getMetadadosRegistros().values()) if (m.getMetadadosRegistroPai() == pai) l.add(m);
    return l;
  }

  static List<Registro> filhosDe(IRegistroDAO dao, DescritorEscrituracao d, Registro r) throws Exception {
    List<Registro> rs = new ArrayList<>();
    for (MetadadosRegistro f : filhos(d, r.getMetadadosRegistro())) {
      IIteradorRegistro it = dao.selecionarRegistrosFilhos(f, r.getId(), new ArrayList<>());
      try {
        while (it.hasNext()) rs.add(it.proximo());
      } finally {
        it.fechar();
      }
    }
    return rs;
  }

  // Apaga os descendentes antes, como a tela faz: um C100 leva junto C170, C190...
  static int remover(IRegistroDAO dao, DescritorEscrituracao d, Registro r) throws Exception {
    int n = 0;
    for (Registro x : filhosDe(dao, d, r)) n += remover(dao, d, x);
    dao.remover(r);
    return n + 1;
  }

  static void marcarExcluidos(IRegistroDAO dao, DescritorEscrituracao d, Registro r, java.util.Set<String> excluidos) throws Exception {
    excluidos.add(r.getMetadadosRegistro().getId() + "#" + r.getId());
    for (Registro x : filhosDe(dao, d, r)) marcarExcluidos(dao, d, x, excluidos);
  }

  static void preencher(Registro r, Map<?, ?> valores) {
    for (Map.Entry<?, ?> e : valores.entrySet()) {
      String k = String.valueOf(e.getKey()).toUpperCase();
      if (k.equals("REG")) throw new IllegalArgumentException("o campo REG não se altera");
      Campo c = r.getCampo(k);
      if (c == null) throw new IllegalArgumentException("campo " + k + " não existe no registro " + r.getMetadadosRegistro().getId());
      c.setValor(e.getValue() == null ? "" : String.valueOf(e.getValue()));
    }
  }

  static void conferirCampos(MetadadosRegistro m, Map<?, ?> valores) {
    java.util.Set<String> ids = new java.util.HashSet<>();
    for (MetadadosCampo mc : m.getCampos()) ids.add(mc.getId());
    for (Object k : valores.keySet()) {
      String c = String.valueOf(k).toUpperCase();
      if (c.equals("REG")) throw new IllegalArgumentException("o campo REG não se altera");
      if (!ids.contains(c)) throw new IllegalArgumentException("campo " + c + " não existe no registro " + m.getId());
    }
  }

  // Confere a lista inteira antes de gravar: o DAO do PVA grava linha a linha e o rollback não desfaz o que já foi.
  static void conferir(IRegistroDAO dao, DescritorEscrituracao d, List<?> ops) throws Exception {
    java.util.Set<String> excluidos = new java.util.HashSet<>();
    for (int i = 0; i < ops.size(); i++) {
      try {
        if (!(ops.get(i) instanceof Map<?, ?> op)) throw new IllegalArgumentException("não é um objeto");
        String acao = String.valueOf(op.get("acao"));
        if (!List.of("alterar", "incluir", "excluir").contains(acao)) {
          throw new IllegalArgumentException("acao deve ser alterar, incluir ou excluir");
        }
        MetadadosRegistro m = meta(d, op.get("registro") == null ? null : String.valueOf(op.get("registro")));
        Map<?, ?> valores = op.get("campos") instanceof Map<?, ?> x ? x : Map.of();
        conferirCampos(m, valores);
        MetadadosRegistro alvo = acao.equals("incluir") ? m.getMetadadosRegistroPai() : m;
        if (alvo == null) continue;
        long id = id(op, acao.equals("incluir") ? "pai" : "id");
        Registro r = ler(dao, alvo, id);
        if (excluidos.contains(alvo.getId() + "#" + id)) {
          throw new IllegalArgumentException(alvo.getId() + " ID " + id + " já sai com uma exclusão anterior da lista");
        }
        if (acao.equals("excluir")) marcarExcluidos(dao, d, r, excluidos);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("operação " + i + ": " + e.getMessage() + " (nada foi gravado)");
      }
    }
  }

  // O gerador de analíticos do PVA soma só BC, ICMS, ST e IPI por CST/CFOP/alíquota e deixa o VL_OPR do C190 vazio.
  // Completa com a regra do Guia Prático sobre os itens (VL_ITEM - VL_DESC + VL_ICMS_ST + VL_IPI) mais o frete, o
  // seguro e as outras despesas do C100, que o C170 não tem: rateados pelo VL_ITEM de cada grupo, com a sobra dos
  // centavos no grupo de maior peso para a soma fechar com o documento.
  static Map<String, Object> completarVlOpr(EdicaoEscrituracao ed) throws Exception {
    IRegistroDAO dao = ed.getPersistencia().getRegistroDAO();
    MetadadosRegistro m = meta(ed.getDescritor(), "C190");
    String sql = "SELECT R.ID, R.ID_PAI, R.CST_ICMS, R.CFOP, R.ALIQ_ICMS, COALESCE(R.VL_BC_ICMS,0) AS BC,"
        + " SUM(COALESCE(I.VL_ITEM,0)-COALESCE(I.VL_DESC,0)) AS MERC, SUM(COALESCE(I.VL_ITEM,0)-COALESCE(I.VL_DESC,0)+COALESCE(I.VL_ICMS_ST,0)"
        + "+COALESCE(I.VL_IPI,0)) AS VL, SUM(COALESCE(I.VL_ITEM,0)) AS PESO,"
        + " MAX(COALESCE(C.VL_FRT,0)+COALESCE(C.VL_SEG,0)+COALESCE(C.VL_OUT_DA,0)) AS DESP, COUNT(I.ID) AS N"
        + " FROM reg_c190 R JOIN reg_c100 C ON C.ID=R.ID_PAI LEFT JOIN reg_c170 I ON I.ID_PAI=C.ID"
        + " AND I.CST_ICMS=R.CST_ICMS AND I.CFOP=R.CFOP AND I.ALIQ_ICMS<=>R.ALIQ_ICMS"
        + " WHERE R.VL_OPR IS NULL GROUP BY R.ID, R.ID_PAI, R.CST_ICMS, R.CFOP, R.ALIQ_ICMS, R.VL_BC_ICMS ORDER BY R.ID_PAI, R.ID";
    record Grupo(long id, BigDecimal vl, BigDecimal peso, BigDecimal merc, BigDecimal bc, String cst, String cfop, String aliq) {}
    Map<Long, List<Grupo>> porDoc = new LinkedHashMap<>();
    Map<Long, BigDecimal> desp = new LinkedHashMap<>();
    List<Long> semItens = new ArrayList<>();
    try (java.sql.ResultSet rs = ed.getPersistencia().executarComandoSql(sql)) {
      while (rs.next()) {
        if (rs.getLong("N") == 0) {
          semItens.add(rs.getLong("ID"));
          continue;
        }
        long doc = rs.getLong("ID_PAI");
        porDoc.computeIfAbsent(doc, k -> new ArrayList<>()).add(new Grupo(rs.getLong("ID"), rs.getBigDecimal("VL"),
            rs.getBigDecimal("PESO"), rs.getBigDecimal("MERC"), rs.getBigDecimal("BC"), rs.getString("CST_ICMS"), rs.getString("CFOP"),
            rs.getString("ALIQ_ICMS")));
        desp.put(doc, rs.getBigDecimal("DESP"));
      }
    }
    int preenchidos = 0;
    List<Long> rateados = new ArrayList<>();
    List<Long> comReducao = new ArrayList<>();
    List<Map<String, Object>> detalhe = new ArrayList<>();
    for (Map.Entry<Long, List<Grupo>> e : porDoc.entrySet()) {
      List<Grupo> gs = e.getValue();
      BigDecimal total = desp.get(e.getKey()).setScale(2, RoundingMode.HALF_UP);
      BigDecimal pesoTotal = BigDecimal.ZERO;
      for (Grupo g : gs) pesoTotal = pesoTotal.add(g.peso());
      Map<Long, BigDecimal> parte = new LinkedHashMap<>();
      if (total.signum() != 0) {
        rateados.add(e.getKey());
        Grupo maior = gs.get(0);
        BigDecimal soma = BigDecimal.ZERO;
        for (Grupo g : gs) {
          BigDecimal p = pesoTotal.signum() == 0 ? BigDecimal.ZERO
              : total.multiply(g.peso()).divide(pesoTotal, 2, RoundingMode.HALF_UP);
          parte.put(g.id(), p);
          soma = soma.add(p);
          if (g.peso().compareTo(maior.peso()) > 0) maior = g;
        }
        parte.merge(maior.id(), total.subtract(soma), BigDecimal::add);
      }
      for (Grupo g : gs) {
        BigDecimal rateio = parte.getOrDefault(g.id(), BigDecimal.ZERO);
        Registro r = ler(dao, m, g.id());
        r.getCampo("VL_OPR").setValor(valor(g.vl().add(rateio)));
        // Soma de itens sem o campo sai vazia do gerador; o C190 exige o valor, e zero é o que os itens dizem.
        for (String c : List.of("VL_BC_ICMS", "VL_ICMS", "VL_BC_ICMS_ST", "VL_ICMS_ST", "VL_IPI")) {
          if (r.getCampo(c).getValor() == null || r.getCampo(c).getValor().isBlank()) r.getCampo(c).setValor("0,00");
        }
        // VL_RED_BC: o gerador não calcula. Só há redução nos CST x20 e x70: o que a operação tem além da base.
        String cst = g.cst() == null ? "" : g.cst();
        BigDecimal red = BigDecimal.ZERO;
        if (cst.endsWith("20") || cst.endsWith("70")) {
          red = g.merc().add(rateio).subtract(g.bc()).max(BigDecimal.ZERO);
          if (red.signum() != 0) comReducao.add(g.id());
        }
        r.getCampo("VL_RED_BC").setValor(valor(red));
        detalhe.add(Json.obj("id", g.id(), "c100", e.getKey(), "cst", cst, "cfop", g.cfop(),
            "aliq", g.aliq() == null ? "" : g.aliq().replace('.', ','), "vlOpr", valor(g.vl().add(rateio)),
            "rateio", valor(rateio), "vlRedBc", valor(red)));
        r.setAlterado(true);
        dao.atualizar(r);
        preenchidos++;
      }
    }
    Map<String, Object> out = Json.obj("c190Preenchidos", preenchidos,
        "regra", "VL_OPR = soma dos C170 do grupo (VL_ITEM - VL_DESC + VL_ICMS_ST + VL_IPI) + frete, seguro e outras"
            + " despesas do C100 rateados pelo VL_ITEM; campos de valor vazios viram 0,00");
    out.put("c190", detalhe);
    if (!rateados.isEmpty()) out.put("c100ComDespesasRateadas", rateados);
    if (!comReducao.isEmpty()) {
      out.put("c190ComVlRedBcCalculado", comReducao);
      out.put("nota", "VL_RED_BC dos CST x20/x70 = VL_ITEM - VL_DESC + despesas rateadas - VL_BC_ICMS; confira contra a NF-e");
    }
    if (!semItens.isEmpty()) out.put("c190SemItensComVlOprVazio", semItens);
    return out;
  }

  static final List<String> TOTAIS_C100 = List.of("VL_BC_ICMS", "VL_ICMS", "VL_BC_ICMS_ST", "VL_ICMS_ST", "VL_IPI");

  // O gerador refaz o C190 mas não o C100, e o PVA exige C100 = soma dos C190 (MSG_VL_ICMS_ANALIT e afins). Só nos
  // documentos cujos itens esta edição mexeu: divergência antiga em outro documento é achado, não se esconde.
  static List<Map<String, Object>> alinharC100(EdicaoEscrituracao ed, java.util.Set<Long> docs) throws Exception {
    List<Map<String, Object>> out = new ArrayList<>();
    if (docs.isEmpty()) return out;
    IRegistroDAO dao = ed.getPersistencia().getRegistroDAO();
    MetadadosRegistro m = meta(ed.getDescritor(), "C100");
    StringBuilder sql = new StringBuilder("SELECT ID_PAI");
    for (String c : TOTAIS_C100) sql.append(", SUM(COALESCE(").append(c).append(",0)) AS ").append(c);
    sql.append(" FROM reg_c190 WHERE ID_PAI IN (");
    sql.append(String.join(",", docs.stream().map(String::valueOf).toList())).append(") GROUP BY ID_PAI");
    Map<Long, Map<String, BigDecimal>> somas = new LinkedHashMap<>();
    try (java.sql.ResultSet rs = ed.getPersistencia().executarComandoSql(sql.toString())) {
      while (rs.next()) {
        Map<String, BigDecimal> v = new LinkedHashMap<>();
        for (String c : TOTAIS_C100) v.put(c, rs.getBigDecimal(c));
        somas.put(rs.getLong("ID_PAI"), v);
      }
    }
    for (Map.Entry<Long, Map<String, BigDecimal>> e : somas.entrySet()) {
      Registro r = ler(dao, m, e.getKey());
      boolean mudou = false;
      for (String c : TOTAIS_C100) {
        String antes = r.getCampo(c).getValor();
        BigDecimal atual = antes == null || antes.isBlank() ? BigDecimal.ZERO : new BigDecimal(antes.replace(".", "").replace(',', '.'));
        if (atual.compareTo(e.getValue().get(c)) == 0) continue;
        String depois = valor(e.getValue().get(c));
        r.getCampo(c).setValor(depois);
        out.add(Json.obj("c100", e.getKey(), "campo", c, "antes", antes == null ? "" : antes, "depois", depois));
        mudou = true;
      }
      if (mudou) {
        r.setAlterado(true);
        dao.atualizar(r);
      }
    }
    return out;
  }

  static String valor(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  static Map<String, Object> operar(IRegistroDAO dao, DescritorEscrituracao d, Map<?, ?> op) throws Exception {
    String acao = String.valueOf(op.get("acao"));
    MetadadosRegistro m = meta(d, op.get("registro") == null ? null : String.valueOf(op.get("registro")));
    Map<?, ?> valores = op.get("campos") instanceof Map<?, ?> x ? x : Map.of();
    switch (acao) {
      case "alterar" -> {
        Registro r = ler(dao, m, id(op, "id"));
        Map<String, Object> antes = campos(r);
        preencher(r, valores);
        r.setAlterado(true);
        dao.atualizar(r);
        Map<String, Object> mudou = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : campos(r).entrySet()) {
          if (!java.util.Objects.equals(e.getValue(), antes.get(e.getKey()))) {
            mudou.put(e.getKey(), Json.obj("antes", antes.get(e.getKey()), "depois", e.getValue()));
          }
        }
        return Json.obj("acao", acao, "registro", m.getId(), "id", r.getId(), "pai", r.getIdPai(), "alterados", mudou);
      }
      case "excluir" -> {
        Registro r = ler(dao, m, id(op, "id"));
        Map<String, Object> antes = campos(r);
        long paiExcluido = r.getIdPai();
        int n = remover(dao, d, r);
        return Json.obj("acao", acao, "registro", m.getId(), "id", r.getId(), "pai", paiExcluido, "linhasRemovidas", n, "era", antes);
      }
      case "incluir" -> {
        long pai = m.getMetadadosRegistroPai() == null ? 0 : id(op, "pai");
        if (m.getMetadadosRegistroPai() != null) ler(dao, m.getMetadadosRegistroPai(), pai);
        Registro r = new Registro(0, m);
        List<Campo> cs = new ArrayList<>();
        for (MetadadosCampo mc : m.getCampos()) cs.add(new Campo(mc, mc.getId().equals("REG") ? m.getId() : ""));
        r.setCampos(cs);
        r.setIdPai(pai);
        preencher(r, valores);
        dao.inserir(r);
        return Json.obj("acao", acao, "registro", m.getId(), "id", r.getId(), "pai", pai, "campos", campos(r));
      }
      default -> throw new IllegalArgumentException("acao deve ser alterar, incluir ou excluir");
    }
  }

  static Map<String, Object> editar(EscrituracaoFiscal esc, List<?> ops, boolean analiticos, boolean apuracao) throws Exception {
    falhas.clear();
    EdicaoEscrituracao ed = UtilEdicao.abrirEdicao(esc, TRATADOR);
    List<Map<String, Object>> feitas = new ArrayList<>();
    String falhaGerador = null;
    Map<String, Object> vlOpr = null;
    List<Map<String, Object>> c100 = null;
    try {
      IRegistroDAO dao = ed.getPersistencia().getRegistroDAO();
      DescritorEscrituracao d = ed.getDescritor();
      conferir(dao, d, ops);
      dao.abrirTransacao();
      try {
        for (int i = 0; i < ops.size(); i++) {
          try {
            feitas.add(operar(dao, d, (Map<?, ?>) ops.get(i)));
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("operação " + i + ": " + e.getMessage()
                + (feitas.isEmpty() ? " (nada foi gravado)" : " (as " + feitas.size() + " anteriores ficaram gravadas)"));
          }
        }
        dao.commitTransacao();
      } catch (Throwable e) {
        if (dao.isTransacaoAberta()) dao.rollBackTransacao();
        throw e;
      }
      // As operações já estão gravadas: falha do gerador vai na resposta, não desfaz a edição.
      if (analiticos || apuracao) {
        try {
          // Os geradores leem da sessão do editor os dados do 0000 (UF, período). No PVA sem tela o Guice não
          // registra o controlador de edição; o método não usa estado dele.
          new ControleEditarEscrituracao().configurarSessaoEdicaoEscrituracao(ed);
          if (analiticos) {
            GeradorRegistroFachada.geraTodosRegistrosAnaliticos(ed.getFabricaObjetos());
            vlOpr = completarVlOpr(ed);
            java.util.Set<Long> docs = new java.util.LinkedHashSet<>();
            for (Map<String, Object> f : feitas) {
              if (List.of("C170", "C190").contains(String.valueOf(f.get("registro"))) && f.get("pai") instanceof Long p) docs.add(p);
            }
            c100 = alinharC100(ed, docs);
          }
          if (apuracao) {
            GeradorRegistroFachada.geraTodosRegistrosApuracao(ed.getFabricaObjetos());
            GeradorRegistroFachada.geraRegistrosApuracao(ed.getFabricaObjetos());
          }
        } catch (Throwable e) {
          falhaGerador = String.valueOf(e);
        }
      }
    } finally {
      ed.fechar();
    }
    // Como a tela ao entrar em edição: a escrituração deixa de estar "validada" até gerar e validar de novo.
    UtilEscrituracao.alterarEstadoDoObjetoEscrituracaoParaEdicao(esc);
    PersistenciaFiscalPVA.getSingleton().getFabricaDaoMaster().getDaoEscrituracaoFiscal().atualizar(esc);
    Map<String, Object> r = Json.obj("operacoes", feitas, "analiticos", analiticos, "apuracao", apuracao);
    if (vlOpr != null) r.put("vlOprC190", vlOpr);
    if (c100 != null && !c100.isEmpty()) r.put("c100AlinhadosAosC190", c100);
    if (falhaGerador != null) r.put("falhaRecalculo", falhaGerador);
    if (!falhas.isEmpty()) r.put("avisosDoEditor", falhas.stream().map(String::valueOf).toList());
    return r;
  }

  static void exportar(EscrituracaoFiscal esc, Path destino) throws java.io.IOException {
    // Com o arquivo já existente o PVA abre um diálogo modal "substituir?" e a chamada nunca volta.
    java.nio.file.Files.deleteIfExists(destino);
    IControleGerarArquivo g;
    try {
      g = FabricaControle.getSingleton().getServico(IControleGerarArquivo.class);
    } catch (RuntimeException semBinding) {
      g = new br.gov.serpro.sped.fiscalpva.nucleo.controle.gerarArquivoEntrega.ControleGerarArquivoV1();
    }
    if (!g.exportarArquivo(esc, destino.toString(), PvaServer.progresso)) {
      throw new IllegalStateException("o PVA não exportou a escrituração para " + destino);
    }
  }
}
