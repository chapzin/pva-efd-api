import br.gov.serpro.vepxml.persistencia.IPersistencia;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Verificações que o PVA não faz, mas que a malha fiscal cruza. Rodam em SQL
// sobre as tabelas reg_XXXX que o próprio PVA monta ao importar o arquivo, ou
// seja, sobre a leitura oficial da escrituração (sem um parser paralelo).
final class Verificacoes {
  private Verificacoes() {}

  static final int MAX_OCORRENCIAS = 200;

  static List<Map<String, String>> linhas(IPersistencia per, String sql, int limite) throws Exception {
    List<Map<String, String>> out = new ArrayList<>();
    ResultSet r = per.executarComandoSql(sql);
    try {
      ResultSetMetaData md = r.getMetaData();
      while (r.next() && out.size() < limite) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i), r.getString(i));
        out.add(m);
      }
    } finally {
      Verificacoes.fechar(r);
    }
    return out;
  }

  // executarComandoSql abre uma conexão por chamada e fechar só o ResultSet não a devolve: o MySQL do PVA chegava ao
  // limite ("Too many connections") numa sequência de propostas.
  static void fechar(ResultSet r) {
    try {
      java.sql.Statement st = r.getStatement();
      java.sql.Connection c = st == null ? null : st.getConnection();
      r.close();
      if (st != null) st.close();
      if (c != null) c.close();
    } catch (java.sql.SQLException e) {
      // conexão já fechada
    }
  }

  static List<Map<String, String>> linhas(IPersistencia per, String sql) throws Exception {
    return linhas(per, sql, Integer.MAX_VALUE);
  }

  static BigDecimal dec(String s) {
    if (s == null || s.isBlank()) return BigDecimal.ZERO;
    return new BigDecimal(s.trim().replace(",", "."));
  }

  static int inteiro(String s) {
    if (s == null || s.isBlank()) return -1;
    try {
      return new BigDecimal(s.trim()).intValue();
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  // O banco guarda número sem zero à esquerda (CNPJ, chave); normaliza para comparar.
  static String digitos(String s, int tamanho) {
    if (s == null) return "";
    String d = s.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
    if (d.isEmpty()) return "";
    return d.length() >= tamanho ? d : "0".repeat(tamanho - d.length()) + d;
  }

  // Datas vêm como ddmmaaaa (arquivo) ou aaaa-mm-dd (coluna DATE).
  static LocalDate data(String s) {
    if (s == null || s.isBlank()) return null;
    String t = s.trim();
    try {
      if (t.length() >= 10 && t.charAt(4) == '-') return LocalDate.parse(t.substring(0, 10));
      String d = t.replaceAll("[^0-9]", "");
      if (d.length() == 7) d = "0" + d;
      if (d.length() == 8) return LocalDate.of(Integer.parseInt(d.substring(4)), Integer.parseInt(d.substring(2, 4)), Integer.parseInt(d.substring(0, 2)));
    } catch (RuntimeException e) {
      return null;
    }
    return null;
  }

  static Map<String, Object> achado(String codigo, String nivel, String titulo, String explicacao, String fundamento,
      List<Map<String, Object>> ocorrencias) {
    BigDecimal total = BigDecimal.ZERO;
    for (Map<String, Object> o : ocorrencias) if (o.get("valor") instanceof BigDecimal v) total = total.add(v);
    return Json.obj("codigo", codigo, "nivel", nivel, "titulo", titulo, "explicacao", explicacao, "fundamento", fundamento,
        "quantidade", ocorrencias.size(), "valorTotal", total,
        "ocorrencias", ocorrencias.size() > MAX_OCORRENCIAS ? ocorrencias.subList(0, MAX_OCORRENCIAS) : ocorrencias);
  }

  static Map<String, Object> resumo(IPersistencia per) throws Exception {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, String>> r0 = linhas(per, "SELECT COD_VER, COD_FIN, DT_INI, DT_FIN, NOME, CNPJ, CPF, UF, IE, IND_PERFIL, IND_ATIV FROM reg_0000");
    if (!r0.isEmpty()) {
      Map<String, String> a = r0.get(0);
      LocalDate ini = data(a.get("DT_INI")), fin = data(a.get("DT_FIN"));
      out.put("contribuinte", Json.obj("nome", a.get("NOME"), "cnpj", a.get("CNPJ") == null ? null : digitos(a.get("CNPJ"), 14),
          "cpf", a.get("CPF") == null || a.get("CPF").isBlank() ? null : digitos(a.get("CPF"), 11), "uf", a.get("UF"), "ie", a.get("IE"),
          "perfil", a.get("IND_PERFIL"), "atividade", a.get("IND_ATIV")));
      out.put("periodo", Json.obj("inicio", ini == null ? null : ini.toString(), "fim", fin == null ? null : fin.toString(),
          "leiaute", a.get("COD_VER"), "finalidade", "0".equals(a.get("COD_FIN")) ? "original" : "substituta"));
    }
    List<Map<String, String>> e110 = linhas(per, "SELECT VL_TOT_DEBITOS, VL_AJ_DEBITOS, VL_TOT_AJ_DEBITOS, VL_ESTORNOS_CRED,"
        + " VL_TOT_CREDITOS, VL_AJ_CREDITOS, VL_TOT_AJ_CREDITOS, VL_ESTORNOS_DEB, VL_SLD_CREDOR_ANT, VL_SLD_APURADO, VL_TOT_DED,"
        + " VL_ICMS_RECOLHER, VL_SLD_CREDOR_TRANSPORTAR, DEB_ESP FROM reg_e110");
    if (!e110.isEmpty()) {
      Map<String, Object> ap = new LinkedHashMap<>();
      e110.get(0).forEach((k, v) -> ap.put(k, dec(v)));
      out.put("apuracaoIcms", ap);
    }
    List<Map<String, Object>> cfops = new ArrayList<>();
    for (Map<String, String> r : linhas(per, "SELECT CFOP, SUM(VL_OPR) VL_OPR, SUM(VL_BC_ICMS) VL_BC_ICMS, SUM(VL_ICMS) VL_ICMS FROM ("
        + " SELECT CFOP, VL_OPR, VL_BC_ICMS, VL_ICMS FROM reg_c190 UNION ALL SELECT CFOP, VL_OPR, VL_BC_ICMS, VL_ICMS FROM reg_d190"
        + " UNION ALL SELECT CFOP, VL_OPR, VL_BC_ICMS, VL_ICMS FROM reg_c890 UNION ALL SELECT CFOP, VL_OPR, VL_BC_ICMS, VL_ICMS FROM reg_c850"
        + " UNION ALL SELECT CFOP, VL_OPR, VL_BC_ICMS, VL_ICMS FROM reg_c490"
        + ") t GROUP BY CFOP ORDER BY CFOP")) {
      cfops.add(Json.obj("cfop", r.get("CFOP"), "valorOperacao", dec(r.get("VL_OPR")), "baseIcms", dec(r.get("VL_BC_ICMS")),
          "icms", dec(r.get("VL_ICMS"))));
    }
    out.put("totaisPorCfop", cfops);
    List<Map<String, String>> q = linhas(per, "SELECT (SELECT COUNT(*) FROM reg_c100) C100, (SELECT COUNT(*) FROM reg_d100) D100,"
        + " (SELECT COUNT(*) FROM reg_0150) PARTICIPANTES, (SELECT COUNT(*) FROM reg_0200) ITENS, (SELECT COUNT(*) FROM reg_h010) INVENTARIO");
    Map<String, Object> qt = new LinkedHashMap<>();
    q.get(0).forEach((k, v) -> qt.put(k, inteiro(v)));
    out.put("quantidades", qt);
    return out;
  }

  static List<Map<String, Object>> executar(IPersistencia per, Map<String, Object> resumo) throws Exception {
    List<Map<String, Object>> achados = new ArrayList<>();
    String docC190 = "SELECT c.LINHA LINHA_DOC, c.NUM_DOC, c.CHV_NFE, c.COD_PART, a.LINHA, a.CFOP, a.CST_ICMS, a.VL_OPR, a.VL_ICMS"
        + " FROM reg_c190 a JOIN reg_c100 c ON a.ID_PAI = c.ID WHERE ";

    add(achados, per, docC190 + "a.CFOP IN (1556, 2556, 3556, 1407, 2407) AND a.VL_ICMS > 0 ORDER BY a.LINHA",
        "CREDITO_USO_CONSUMO", "alerta", "Crédito de ICMS em material de uso e consumo",
        "Entradas para uso e consumo (CFOP x556/x407) com ICMS no C190 viram crédito na apuração. O direito a esse crédito"
            + " está adiado por lei; o fisco glosa e cobra com multa.",
        "LC 87/1996, art. 33, I");

    add(achados, per, docC190 + "a.CFOP IN (1551, 2551, 3551, 1406, 2406) AND a.VL_ICMS > 0 ORDER BY a.LINHA",
        "CREDITO_ATIVO_DIRETO", "atencao", "Crédito de ativo imobilizado lançado direto no documento",
        "O crédito de bem do ativo é apropriado em 1/48 por mês pelo CIAP (Bloco G, G110/G125) e entra na apuração por ajuste"
            + " no E111. Com ICMS no C190 da entrada, o crédito integral entra no mês da compra.",
        "LC 87/1996, art. 20, §5º; Guia Prático EFD ICMS/IPI, Bloco G");

    add(achados, per, docC190 + "c.IND_OPER = 0 AND MOD(CAST(a.CST_ICMS AS UNSIGNED), 100) IN (40, 41, 50, 60) AND a.VL_ICMS > 0 ORDER BY a.LINHA",
        "CREDITO_CST_SEM_DIREITO", "alerta", "Crédito de ICMS em entrada isenta, não tributada, suspensa ou com ST",
        "CST de final 40, 41, 50 ou 60 indica que não houve ICMS próprio destacado para creditar (ou que o imposto já foi"
            + " retido por substituição). Valor de ICMS nesses C190 de entrada é crédito sem origem.",
        "LC 87/1996, art. 20 e 23; Ajuste SINIEF 20/2012 (tabela de CST)");

    debitoSaidaSt(achados, per);

    Map<?, ?> periodo = (Map<?, ?>) resumo.get("periodo");
    LocalDate ini = periodo == null || periodo.get("inicio") == null ? null : LocalDate.parse((String) periodo.get("inicio"));
    if (ini != null && ini.getMonthValue() == 2) {
      LocalDate fimAno = LocalDate.of(ini.getYear() - 1, 12, 31);
      List<Map<String, String>> invs = linhas(per, "SELECT h.DT_INV, h.VL_INV, (SELECT COUNT(*) FROM reg_h010 i WHERE i.ID_PAI = h.ID) N"
          + " FROM reg_h005 h");
      Map<String, String> inv = null;
      for (Map<String, String> h : invs) if (fimAno.equals(data(h.get("DT_INV")))) inv = h;
      if (inv == null) {
        achados.add(achado("INVENTARIO_AUSENTE_FEVEREIRO", "alerta", "Inventário de 31/12 não informado na EFD de fevereiro",
            "O inventário levantado em 31/12 deve ser informado no Bloco H da EFD do segundo mês seguinte (fevereiro). A"
                + " ausência é uma das omissões mais cruzadas pelas SEFAZ.",
            "Guia Prático EFD ICMS/IPI, Bloco H; Perguntas Frequentes do SPED Fiscal",
            List.of(Json.obj("registro", "H005", "esperado", fimAno.toString()))));
      } else if ((dec(inv.get("VL_INV")).signum() == 0 || inteiro(inv.get("N")) == 0) && temMovimento(per)) {
        achados.add(achado("INVENTARIO_ZERADO", "alerta", "Inventário de 31/12 zerado ou sem itens em empresa com movimento",
            "O H005 de 31/12 está com valor zero ou sem nenhum H010, mas o estabelecimento compra e vende mercadorias. Estoque"
                + " zerado no fim do ano quase nunca é real: para a SEFAZ, tudo o que foi vendido no ano seguinte sai sem"
                + " estoque de origem (omissão de entrada) e o custo das vendas fica sem lastro. Exceção: estabelecimento aberto"
                + " depois de 31/12 (a EFD não traz a data de abertura; confira).",
            "RICMS (livro Registro de Inventário); Guia Prático EFD ICMS/IPI, Bloco H",
            List.of(Json.obj("registro", "H005", "data", fimAno.toString(), "valorInventario", dec(inv.get("VL_INV")),
                "itens", inteiro(inv.get("N"))))));
      }
    }

    // DIFAL de uso/consumo e ativo de contribuinte vai como ajuste de débito no E111
    // (código UF + 0 + 0/5); sem nenhum, a entrada interestadual merece conferência.
    // UFs que cobram o DIFAL na entrada por guia própria (PVA_UF_DIFAL_NA_ENTRADA) caem para "info".
    List<Map<String, String>> inter = linhas(per, docC190.replace(" WHERE ", " WHERE c.IND_OPER = 0 AND ")
        + "a.CFOP IN (2551, 2556, 2406, 2407) AND a.VL_OPR > 0 ORDER BY a.LINHA", 5000);
    if (!inter.isEmpty()) {
      List<Map<String, String>> aj = linhas(per, "SELECT COUNT(*) N FROM reg_e111 WHERE SUBSTRING(COD_AJ_APUR, 3, 1) = '0'"
          + " AND SUBSTRING(COD_AJ_APUR, 4, 1) IN ('0', '5')");
      if (inteiro(aj.get(0).get("N")) == 0) {
        Map<?, ?> contrib = (Map<?, ?>) resumo.get("contribuinte");
        String uf = contrib == null ? null : (String) contrib.get("uf");
        boolean naEntrada = uf != null && UF_DIFAL_NA_ENTRADA.contains(uf.toUpperCase());
        List<Map<String, Object>> oc = new ArrayList<>();
        for (Map<String, String> r : inter) oc.add(ocorrencia(r, dec(r.get("VL_OPR"))));
        achados.add(achado("DIFAL_SEM_AJUSTE", naEntrada ? "info" : "atencao",
            "Entrada interestadual de uso/consumo ou ativo sem ajuste de débito",
            "Há compras interestaduais para uso, consumo ou ativo, mas nenhum ajuste de débito no E111. Em regra o diferencial"
                + " de alíquotas (DIFAL) dessas entradas é lançado como ajuste de débito. Confira se é devido."
                + (naEntrada ? " Nesta UF o DIFAL costuma ser cobrado na entrada, por guia própria (DAE): confira o pagamento"
                    + " da guia em vez do E111." : ""),
            "CF/88, art. 155, §2º, VII e VIII; LC 87/1996, art. 12, XV (verificação heurística; o valor é o da operação)", oc));
      }
    }
    return achados;
  }

  static final java.util.Set<String> UF_DIFAL_NA_ENTRADA = java.util.Set.of(
      System.getenv().getOrDefault("PVA_UF_DIFAL_NA_ENTRADA", "CE").toUpperCase().split("[,; ]+"));

  static final BigDecimal TOLERANCIA = new BigDecimal("1.00");

  private static boolean temMovimento(IPersistencia per) throws Exception {
    return inteiro(linhas(per, "SELECT (SELECT COUNT(*) FROM reg_c190 WHERE VL_OPR > 0) + (SELECT COUNT(*) FROM reg_c890 WHERE VL_OPR > 0)"
        + " + (SELECT COUNT(*) FROM reg_c850 WHERE VL_OPR > 0) + (SELECT COUNT(*) FROM reg_c490 WHERE VL_OPR > 0) N").get(0).get("N")) > 0;
  }

  // Saídas de mercadoria com ST: C190 de nota própria e os resumos de cupom (C890 SAT, C850 NFC-e em
  // C800, C490 ECF). Consulta com colunas iguais para dar para somar tudo junto.
  private static String saidasSt(String cfops) {
    String f = " CFOP IN (" + cfops + ") AND VL_ICMS > 0";
    return "SELECT 'C190' REG, a.LINHA, c.LINHA LINHA_DOC, c.NUM_DOC, c.CHV_NFE, c.COD_PART, a.CFOP, a.CST_ICMS, a.VL_OPR, a.VL_ICMS"
        + " FROM reg_c190 a JOIN reg_c100 c ON a.ID_PAI = c.ID WHERE c.IND_OPER = 1 AND a.CFOP IN (" + cfops + ") AND a.VL_ICMS > 0"
        + " UNION ALL SELECT 'C890', LINHA, NULL, NULL, NULL, NULL, CFOP, CST_ICMS, VL_OPR, VL_ICMS FROM reg_c890 WHERE" + f
        + " UNION ALL SELECT 'C850', LINHA, NULL, NULL, NULL, NULL, CFOP, CST_ICMS, VL_OPR, VL_ICMS FROM reg_c850 WHERE" + f
        + " UNION ALL SELECT 'C490', LINHA, NULL, NULL, NULL, NULL, CFOP, CST_ICMS, VL_OPR, VL_ICMS FROM reg_c490 WHERE" + f;
  }

  // Venda de mercadoria com ST e ICMS próprio destacado. Sem estorno de débito no E111 é,
  // em regra, imposto pago a mais. Algumas UFs mandam destacar e estornar (ex.: CE, Decreto
  // 35.395/2023, código CE030007): aí o que importa é o estorno bater com o débito.
  private static void debitoSaidaSt(List<Map<String, Object>> achados, IPersistencia per) throws Exception {
    List<Map<String, String>> est = linhas(per, "SELECT COD_AJ_APUR, SUM(VL_AJ_APUR) V FROM reg_e111"
        + " WHERE SUBSTRING(COD_AJ_APUR, 3, 2) = '03' GROUP BY COD_AJ_APUR");
    BigDecimal estorno = BigDecimal.ZERO;
    List<String> codigos = new ArrayList<>();
    for (Map<String, String> r : est) {
      estorno = estorno.add(dec(r.get("V")));
      codigos.add(r.get("COD_AJ_APUR"));
    }
    if (estorno.signum() == 0) {
      List<Map<String, Object>> oc = new ArrayList<>();
      for (Map<String, String> r : linhas(per, saidasSt("5405, 6404") + " ORDER BY LINHA", 5000)) oc.add(ocorrencia(r, dec(r.get("VL_ICMS"))));
      if (!oc.isEmpty()) achados.add(achado("DEBITO_EM_SAIDA_ST", "atencao", "Débito de ICMS próprio em venda de mercadoria já tributada por ST",
          "CFOP 5405/6404 é venda de mercadoria recebida com ICMS retido (contribuinte substituído); o imposto próprio normalmente"
              + " não é destacado. Débito aqui, sem estorno no E111, costuma ser erro de cadastro e aumenta o imposto a pagar.",
          "Tabela de CFOP (Convênio s/nº de 1970); Convênio ICMS 142/2018", oc));
      return;
    }
    List<Map<String, String>> tot = linhas(per, "SELECT SUM(VL_ICMS) V FROM (" + saidasSt("5403, 5405, 6403, 6404") + ") t");
    BigDecimal debito = tot.isEmpty() ? BigDecimal.ZERO : dec(tot.get(0).get("V"));
    BigDecimal dif = estorno.subtract(debito);
    if (dif.abs().compareTo(TOLERANCIA) <= 0) return;
    boolean aMais = dif.signum() > 0;
    achados.add(achado("ESTORNO_DIFERE_DEBITO_ST", aMais ? "alerta" : "atencao",
        aMais ? "Estorno de débito maior que o ICMS destacado nas vendas com ST" : "Estorno de débito menor que o ICMS destacado nas vendas com ST",
        "O E111 estorna débito (" + String.join(", ", codigos) + ") e há ICMS destacado em vendas de mercadoria com ST (CFOP"
            + " 5403/5405/6403/6404). Onde a UF manda destacar e estornar, os dois têm que ser iguais. "
            + (aMais ? "Estorno maior reduz o ICMS de outras operações: é imposto a menos." : "Estorno menor deixa débito"
                + " de venda com ST na apuração: confira se é destaque indevido (imposto pago a mais) ou venda que não é de ST."),
        "Guia Prático EFD ICMS/IPI, registro E111; legislação da UF (ex.: CE, Decreto 35.395/2023 e IN 91/2023)",
        List.of(Json.obj("registro", "E111", "codigos", String.join(", ", codigos), "estorno", estorno, "debitoSaidasSt", debito,
            "valor", dif.abs()))));
  }

  private static Map<String, Object> ocorrencia(Map<String, String> r, BigDecimal valor) {
    return Json.obj("registro", r.getOrDefault("REG", "C190"), "linha", inteiro(r.get("LINHA")), "linhaDocumento", inteiro(r.get("LINHA_DOC")),
        "documento", r.get("NUM_DOC"), "chave", r.get("CHV_NFE") == null ? null : digitos(r.get("CHV_NFE"), 44),
        "participante", r.get("COD_PART"), "cfop", r.get("CFOP"), "cst", r.get("CST_ICMS"), "valorOperacao", dec(r.get("VL_OPR")),
        "valor", valor);
  }

  private static void add(List<Map<String, Object>> achados, IPersistencia per, String sql, String codigo, String nivel,
      String titulo, String explicacao, String fundamento) throws Exception {
    List<Map<String, Object>> oc = new ArrayList<>();
    for (Map<String, String> r : linhas(per, sql, 5000)) oc.add(ocorrencia(r, dec(r.get("VL_ICMS"))));
    if (!oc.isEmpty()) achados.add(achado(codigo, nivel, titulo, explicacao, fundamento, oc));
  }
}
