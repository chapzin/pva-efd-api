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
    try (ResultSet r = per.executarComandoSql(sql)) {
      ResultSetMetaData md = r.getMetaData();
      while (r.next() && out.size() < limite) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i), r.getString(i));
        out.add(m);
      }
    }
    return out;
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

    add(achados, per, docC190 + "c.IND_OPER = 1 AND a.CFOP = 5405 AND a.VL_ICMS > 0 ORDER BY a.LINHA",
        "DEBITO_EM_SAIDA_ST", "atencao", "Débito de ICMS próprio em venda de mercadoria já tributada por ST",
        "CFOP 5405 é venda de mercadoria recebida com ICMS retido (contribuinte substituído); o imposto próprio normalmente"
            + " não é destacado. Débito aqui costuma ser erro de cadastro e aumenta o imposto a pagar.",
        "Tabela de CFOP (Convênio s/nº de 1970); Convênio ICMS 142/2018");

    Map<?, ?> periodo = (Map<?, ?>) resumo.get("periodo");
    LocalDate ini = periodo == null || periodo.get("inicio") == null ? null : LocalDate.parse((String) periodo.get("inicio"));
    if (ini != null && ini.getMonthValue() == 2) {
      LocalDate fimAno = LocalDate.of(ini.getYear() - 1, 12, 31);
      boolean tem = false;
      for (Map<String, String> h : linhas(per, "SELECT DT_INV, VL_INV FROM reg_h005")) if (fimAno.equals(data(h.get("DT_INV")))) tem = true;
      if (!tem) {
        achados.add(achado("INVENTARIO_AUSENTE_FEVEREIRO", "alerta", "Inventário de 31/12 não informado na EFD de fevereiro",
            "O inventário levantado em 31/12 deve ser informado no Bloco H da EFD do segundo mês seguinte (fevereiro). A"
                + " ausência é uma das omissões mais cruzadas pelas SEFAZ.",
            "Guia Prático EFD ICMS/IPI, Bloco H; Perguntas Frequentes do SPED Fiscal",
            List.of(Json.obj("registro", "H005", "esperado", fimAno.toString()))));
      }
    }

    // DIFAL de uso/consumo e ativo de contribuinte vai como ajuste de débito no E111
    // (código UF + 0 + 0/5); sem nenhum, a entrada interestadual merece conferência.
    List<Map<String, String>> inter = linhas(per, "SELECT SUM(VL_OPR) VL FROM reg_c190 WHERE CFOP IN (2551, 2556, 2406, 2407)");
    BigDecimal vInter = inter.isEmpty() ? BigDecimal.ZERO : dec(inter.get(0).get("VL"));
    if (vInter.signum() > 0) {
      List<Map<String, String>> aj = linhas(per, "SELECT COUNT(*) N FROM reg_e111 WHERE SUBSTRING(COD_AJ_APUR, 3, 1) = '0'"
          + " AND SUBSTRING(COD_AJ_APUR, 4, 1) IN ('0', '5')");
      if (inteiro(aj.get(0).get("N")) == 0) {
        achados.add(achado("DIFAL_SEM_AJUSTE", "atencao", "Entrada interestadual de uso/consumo ou ativo sem ajuste de débito",
            "Há compras interestaduais para uso, consumo ou ativo, mas nenhum ajuste de débito no E111. Em regra o diferencial"
                + " de alíquotas (DIFAL) dessas entradas é lançado como ajuste de débito. Confira se é devido.",
            "CF/88, art. 155, §2º, VII e VIII; LC 87/1996, art. 12, XV (verificação heurística)",
            List.of(Json.obj("registro", "C190", "cfops", "2551, 2556, 2406, 2407", "valor", vInter))));
      }
    }
    return achados;
  }

  private static void add(List<Map<String, Object>> achados, IPersistencia per, String sql, String codigo, String nivel,
      String titulo, String explicacao, String fundamento) throws Exception {
    List<Map<String, Object>> oc = new ArrayList<>();
    for (Map<String, String> r : linhas(per, sql, 5000)) {
      oc.add(Json.obj("registro", "C190", "linha", inteiro(r.get("LINHA")), "linhaDocumento", inteiro(r.get("LINHA_DOC")),
          "documento", r.get("NUM_DOC"), "chave", r.get("CHV_NFE") == null ? null : digitos(r.get("CHV_NFE"), 44),
          "participante", r.get("COD_PART"), "cfop", r.get("CFOP"), "cst", r.get("CST_ICMS"), "valorOperacao", dec(r.get("VL_OPR")),
          "valor", dec(r.get("VL_ICMS"))));
    }
    if (!oc.isEmpty()) achados.add(achado(codigo, nivel, titulo, explicacao, fundamento, oc));
  }
}
