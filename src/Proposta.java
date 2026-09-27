import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

// Escrituração de uma NF-e a partir do XML, como operações prontas para o efd_editar. Não grava nada: o que é decisão
// do auditor (código do item da empresa, CFOP e CST de entrada, data de entrada) volta como pendência.
final class Proposta {
  private Proposta() {}

  // O que o banco da escrituração já tem e a proposta precisa.
  record Contexto(String cnpj, long id0001, long idC001, Map<String, String> participantes, Map<String, String> unidade0200,
      Map<String, String> tipo0200, Set<String> unidades0190, boolean jaEscriturada) {}

  static final BigDecimal CEM = new BigDecimal("100");

  static Map<String, Object> montar(Element inf, String chave, Contexto ctx, Map<?, ?> dePara, String dtEs) {
    if (ctx.jaEscriturada()) throw new IllegalArgumentException("a chave " + chave + " já tem C100 nesta escrituração");
    Element ide = Cruzamento.primeiro(inf, "ide");
    Element emit = Cruzamento.primeiro(inf, "emit");
    Element dest = Cruzamento.primeiro(inf, "dest");
    String cEmit = Cruzamento.doc(emit), cDest = Cruzamento.doc(dest);
    boolean propria = ctx.cnpj().equals(cEmit);
    if (!propria && !ctx.cnpj().equals(cDest)) {
      throw new IllegalArgumentException("a nota não é desta escrituração: o CNPJ " + ctx.cnpj() + " não é emitente nem destinatário");
    }
    List<Map<String, Object>> ops = new ArrayList<>();
    List<Map<String, Object>> pend = new ArrayList<>();
    String outro = propria ? cDest : cEmit;
    Element outroEl = propria ? dest : emit;
    String codPart = outro == null ? "" : ctx.participantes().get(outro);
    if (outro != null && codPart == null) {
      codPart = (propria ? "C" : "F") + outro;
      Element end = Cruzamento.primeiro(outroEl, propria ? "enderDest" : "enderEmit");
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("COD_PART", codPart);
      c.put("NOME", t(outroEl, "xNome"));
      c.put("COD_PAIS", t(end, "cPais").isEmpty() ? "01058" : t(end, "cPais"));
      c.put(outro.length() == 14 ? "CNPJ" : "CPF", outro);
      c.put("IE", t(outroEl, "IE").equalsIgnoreCase("ISENTO") ? "" : t(outroEl, "IE"));
      c.put("COD_MUN", t(end, "cMun"));
      c.put("END", t(end, "xLgr"));
      c.put("NUM", t(end, "nro"));
      c.put("COMPL", t(end, "xCpl"));
      c.put("BAIRRO", t(end, "xBairro"));
      ops.add(Json.obj("acao", "incluir", "registro", "0150", "pai", ctx.id0001(), "campos", c));
      if (t(end, "cMun").isEmpty()) pend.add(p("0150", null, "COD_MUN", "o XML não traz o endereço do participante: preencha o município", true));
    }
    Element tot = Cruzamento.primeiro(inf, "ICMSTot");
    String dtDoc = data(t(ide, "dhEmi").isEmpty() ? t(ide, "dEmi") : t(ide, "dhEmi"));
    String es = dtEs;
    if (es == null) {
      es = propria ? (t(ide, "dhSaiEnt").isEmpty() ? dtDoc : data(t(ide, "dhSaiEnt"))) : dtDoc;
      if (!propria) pend.add(p("C100", null, "DT_E_S", "data de entrada proposta = emissão; troque pela data em que a mercadoria entrou", false));
    }
    Map<String, Object> c100 = new LinkedHashMap<>();
    c100.put("IND_OPER", propria ? ("0".equals(t(ide, "tpNF")) ? "0" : "1") : "0");
    c100.put("IND_EMIT", propria ? "0" : "1");
    c100.put("COD_PART", codPart);
    c100.put("COD_MOD", t(ide, "mod"));
    c100.put("COD_SIT", "00");
    c100.put("SER", t(ide, "serie"));
    c100.put("NUM_DOC", t(ide, "nNF"));
    c100.put("CHV_NFE", chave);
    c100.put("DT_DOC", dtDoc);
    c100.put("DT_E_S", es);
    c100.put("VL_DOC", v(tot, "vNF"));
    String indPag = t(inf, "indPag");
    c100.put("IND_PGTO", indPag.equals("0") || indPag.equals("1") ? indPag : "2");
    c100.put("VL_DESC", v(tot, "vDesc"));
    c100.put("VL_ABAT_NT", "0,00");
    c100.put("VL_MERC", v(tot, "vProd"));
    String modFrete = t(inf, "modFrete");
    c100.put("IND_FRT", modFrete.isEmpty() ? "9" : modFrete);
    c100.put("VL_FRT", v(tot, "vFrete"));
    c100.put("VL_SEG", v(tot, "vSeg"));
    c100.put("VL_OUT_DA", v(tot, "vOutro"));
    c100.put("VL_BC_ICMS", "0,00");
    c100.put("VL_ICMS", "0,00");
    c100.put("VL_BC_ICMS_ST", v(tot, "vBCST"));
    c100.put("VL_ICMS_ST", v(tot, "vST"));
    c100.put("VL_IPI", v(tot, "vIPI"));
    c100.put("VL_PIS", v(tot, "vPIS"));
    c100.put("VL_COFINS", v(tot, "vCOFINS"));
    int iC100 = ops.size();
    ops.add(Json.obj("acao", "incluir", "registro", "C100", "pai", ctx.idC001(), "campos", c100));

    BigDecimal bcDoc = BigDecimal.ZERO, icmsDoc = BigDecimal.ZERO, merc = BigDecimal.ZERO, desc = BigDecimal.ZERO;
    NodeList dets = inf.getElementsByTagName("det");
    Map<String, BigDecimal[]> grupos = new TreeMap<>();
    boolean usoOuAtivo = false;
    for (int i = 0; i < dets.getLength(); i++) {
      Element det = (Element) dets.item(i);
      Element prod = Cruzamento.primeiro(det, "prod");
      Element icms = Cruzamento.primeiroFilho(Cruzamento.primeiro(Cruzamento.primeiro(det, "imposto"), "ICMS"));
      String nItem = det.getAttribute("nItem").isEmpty() ? String.valueOf(i + 1) : det.getAttribute("nItem");
      String orig = t(icms, "orig");
      String cstXml = t(icms, "CST"), csosn = t(icms, "CSOSN");
      BigDecimal vProd = n(prod, "vProd"), vDesc = n(prod, "vDesc");
      BigDecimal bc, aliq, vIcms;
      String cst;
      String regra;
      if (propria) {
        cst = cstXml.isEmpty() ? csosn : orig + cstXml;
        bc = n(icms, "vBC");
        aliq = n(icms, "pICMS");
        vIcms = n(icms, "vICMS");
        regra = "XML";
      } else if (!cstXml.isEmpty()) {
        cst = orig + cstXml;
        bc = n(icms, "vBC");
        aliq = n(icms, "pICMS");
        vIcms = n(icms, "vICMS");
        regra = "XML (crédito destacado)";
      } else if (n(icms, "vCredICMSSN").signum() > 0) {
        cst = orig + "90";
        bc = vProd.subtract(vDesc);
        aliq = n(icms, "pCredSN");
        vIcms = n(icms, "vCredICMSSN");
        regra = "Simples: crédito = vCredICMSSN (LC 123/2006, art. 23)";
      } else {
        cst = orig + ("500".equals(csosn) ? "60" : "90");
        bc = BigDecimal.ZERO;
        aliq = BigDecimal.ZERO;
        vIcms = BigDecimal.ZERO;
        regra = "Simples sem crédito (CSOSN " + csosn + ")";
      }
      String cfopXml = t(prod, "CFOP");
      String cfop = propria ? cfopXml : cfopEntrada(cfopXml);
      Object codDp = dePara == null ? null : dePara.get(t(prod, "cProd"));
      String tipo = propria || codDp == null ? null : ctx.tipo0200().get(String.valueOf(codDp));
      if (("07".equals(tipo) || "08".equals(tipo)) && cfop.length() == 4) {
        cfop = cfop.charAt(0) + ("07".equals(tipo) ? "556" : "551");
        cst = orig + "90";
        bc = BigDecimal.ZERO;
        aliq = BigDecimal.ZERO;
        vIcms = BigDecimal.ZERO;
        regra = "07".equals(tipo) ? "0200 TIPO_ITEM 07 (uso e consumo): sem crédito (LC 87/96, art. 33, I)"
            : "0200 TIPO_ITEM 08 (ativo): crédito só pelo CIAP (bloco G), não no C170";
      }
      if (propria) {
        BigDecimal opr = vProd.subtract(vDesc).add(n(icms, "vICMSST")).add(n(det, "vIPI")).add(n(prod, "vFrete")).add(n(prod, "vSeg"))
            .add(n(prod, "vOutro"));
        BigDecimal red = cst.endsWith("20") || cst.endsWith("70")
            ? vProd.subtract(vDesc).add(n(prod, "vFrete")).add(n(prod, "vSeg")).add(n(prod, "vOutro")).subtract(bc).max(BigDecimal.ZERO)
            : BigDecimal.ZERO;
        BigDecimal[] g = grupos.computeIfAbsent(cst + "|" + cfop + "|" + valor(aliq), k -> zeros(7));
        BigDecimal[] soma = {opr, bc, vIcms, n(icms, "vBCST"), n(icms, "vICMSST"), red, n(det, "vIPI")};
        for (int k = 0; k < 7; k++) g[k] = g[k].add(soma[k]);
      } else {
        Object cod = dePara == null ? null : dePara.get(t(prod, "cProd"));
        String codItem = cod == null ? "" : String.valueOf(cod);
        if (codItem.isEmpty()) {
          pend.add(p("C170", nItem, "COD_ITEM", "de-para: código da empresa para o cProd " + t(prod, "cProd") + " (" + t(prod, "xProd")
              + "); passe de_para {\"" + t(prod, "cProd") + "\": \"COD_ITEM\"}. Nunca o cProd do fornecedor", true));
        } else if (!ctx.unidade0200().containsKey(codItem)) {
          pend.add(p("C170", nItem, "COD_ITEM", codItem + " não está no 0200: inclua o item (e a unidade no 0190) antes", true));
        }
        String unid = ctx.unidade0200().getOrDefault(codItem, t(prod, "uCom"));
        if (!unid.isEmpty() && !ctx.unidades0190().contains(unid)) {
          pend.add(p("C170", nItem, "UNID", "unidade " + unid + " não está no 0190", true));
        }
        if (t(prod, "qCom").isEmpty()) pend.add(p("C170", nItem, "QTD", "o XML não traz a quantidade", true));
        String body = cfop.length() == 4 ? cfop.substring(1) : "";
        if (List.of("556", "557", "551", "552", "407", "406").contains(body) && vIcms.signum() > 0) usoOuAtivo = true;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("NUM_ITEM", nItem);
        c.put("COD_ITEM", codItem);
        c.put("QTD", qtd(t(prod, "qCom")));
        c.put("UNID", unid);
        c.put("VL_ITEM", valor(vProd));
        c.put("VL_DESC", valor(vDesc));
        c.put("IND_MOV", "0");
        c.put("CST_ICMS", cst);
        c.put("CFOP", cfop);
        c.put("VL_BC_ICMS", valor(bc));
        c.put("ALIQ_ICMS", valor(aliq));
        c.put("VL_ICMS", valor(vIcms));
        c.put("VL_BC_ICMS_ST", valor(n(icms, "vBCST")));
        c.put("ALIQ_ST", valor(n(icms, "pICMSST")));
        c.put("VL_ICMS_ST", valor(n(icms, "vICMSST")));
        c.put("IND_APUR", "0");
        c.put("VL_IPI", valor(n(det, "vIPI")));
        c.put("VL_ABAT_NT", "0,00");
        Map<String, Object> op = Json.obj("acao", "incluir", "registro", "C170", "pai", "@" + iC100, "campos", c);
        op.put("regra", regra + "; CFOP " + cfopXml + " → " + cfop);
        ops.add(op);
      }
      merc = merc.add(vProd);
      desc = desc.add(vDesc);
      bcDoc = bcDoc.add(bc);
      icmsDoc = icmsDoc.add(vIcms);
    }
    for (Map.Entry<String, BigDecimal[]> e : grupos.entrySet()) {
      String[] k = e.getKey().split("\\|");
      BigDecimal[] g = e.getValue();
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("CST_ICMS", k[0]);
      c.put("CFOP", k[1]);
      c.put("ALIQ_ICMS", k[2]);
      c.put("VL_OPR", valor(g[0]));
      c.put("VL_BC_ICMS", valor(g[1]));
      c.put("VL_ICMS", valor(g[2]));
      c.put("VL_BC_ICMS_ST", valor(g[3]));
      c.put("VL_ICMS_ST", valor(g[4]));
      c.put("VL_RED_BC", valor(g[5]));
      c.put("VL_IPI", valor(g[6]));
      ops.add(Json.obj("acao", "incluir", "registro", "C190", "pai", "@" + iC100, "campos", c));
    }
    c100.put("VL_MERC", valor(merc));
    c100.put("VL_DESC", valor(desc));
    c100.put("VL_BC_ICMS", valor(bcDoc));
    c100.put("VL_ICMS", valor(icmsDoc));
    if (!propria) {
      pend.add(p("C170", null, "CFOP/CST_ICMS", "CFOP de entrada vem da regra (5→1, 6→2, x405/x404→x403): confirme o uso da mercadoria."
          + " Uso e consumo (x556) e ativo (x551) não dão crédito direto de ICMS", usoOuAtivo));
    }
    boolean pronto = pend.stream().noneMatch(x -> Boolean.TRUE.equals(x.get("bloqueia")));
    Map<String, Object> r = Json.obj("chave", chave, "tipo", propria ? "emissão própria" : "entrada de terceiro", "pronto", pronto,
        "operacoes", ops, "pendencias", pend,
        "recalcular_analiticos", !propria, "recalcular_apuracao", true,
        "nota", propria ? "nota própria: C190 direto do XML, sem C170 (o Guia dispensa o C170 da NF-e própria); chame efd_editar só"
            + " com recalcular_apuracao" : "entrada: C170 do XML e C190 pelo gerador do PVA (recalcular_analiticos); os totais do C100"
            + " acompanham");
    r.put("tabela", tabela(ops, pend));
    return r;
  }

  @SuppressWarnings("unchecked")
  static String tabela(List<Map<String, Object>> ops, List<Map<String, Object>> pend) {
    Tabela t = Tabela.com("#", "Registro", "Pai", "Documento / item", "CFOP", "CST", "Valor", "BC ICMS", "ICMS", "Regra");
    for (int i = 0; i < ops.size(); i++) {
      Map<String, Object> op = ops.get(i);
      Map<String, Object> c = (Map<String, Object>) op.get("campos");
      String reg = String.valueOf(op.get("registro"));
      switch (reg) {
        case "0150" -> t.linha(i, reg, op.get("pai"), c.get("COD_PART") + " " + c.get("NOME"), "", "", "", "", "", "XML");
        case "C100" -> t.linha(i, reg, op.get("pai"), "NF " + c.get("NUM_DOC") + " de " + c.get("DT_DOC") + " (" + c.get("COD_PART") + ")",
            "", "", c.get("VL_DOC"), c.get("VL_BC_ICMS"), c.get("VL_ICMS"), "XML");
        case "C170" -> t.linha(i, reg, op.get("pai"), "item " + c.get("NUM_ITEM") + " " + c.get("COD_ITEM"), c.get("CFOP"),
            c.get("CST_ICMS"), c.get("VL_ITEM"), c.get("VL_BC_ICMS"), c.get("VL_ICMS"), op.get("regra"));
        default -> t.linha(i, reg, op.get("pai"), "", c.get("CFOP"), c.get("CST_ICMS"), c.get("VL_OPR"), c.get("VL_BC_ICMS"),
            c.get("VL_ICMS"), "XML");
      }
    }
    Tabela tp = Tabela.com("Registro", "Item", "Campo", "O que decidir", "Bloqueia");
    for (Map<String, Object> x : pend) tp.linha(x.get("registro"), x.get("item"), x.get("campo"), x.get("motivo"),
        Boolean.TRUE.equals(x.get("bloqueia")) ? "sim" : "não");
    return Tabela.juntar("Proposta de escrituração (nada foi gravado)", t, "Pendências antes de gravar", tp);
  }

  static Map<String, Object> p(String reg, String item, String campo, String motivo, boolean bloqueia) {
    return Json.obj("registro", reg, "item", item, "campo", campo, "motivo", motivo, "bloqueia", bloqueia);
  }

  static String cfopEntrada(String cfop) {
    if (cfop == null || cfop.length() != 4) return cfop;
    char d = switch (cfop.charAt(0)) {
      case '5' -> '1';
      case '6' -> '2';
      case '7' -> '3';
      default -> cfop.charAt(0);
    };
    String body = cfop.substring(1);
    if (body.equals("405") || body.equals("404")) body = "403";
    return d + body;
  }

  static String t(Element e, String tag) {
    String x = e == null ? null : Cruzamento.texto(e, tag);
    return x == null ? "" : x;
  }

  static BigDecimal n(Element e, String tag) {
    return Cruzamento.num(e == null ? null : Cruzamento.texto(e, tag));
  }

  static String v(Element e, String tag) {
    return valor(n(e, tag));
  }

  static String valor(BigDecimal b) {
    return b.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  static String qtd(String s) {
    if (s.isEmpty()) return "";
    BigDecimal q = new BigDecimal(s).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros();
    return (q.scale() < 0 ? q.setScale(0) : q).toPlainString().replace('.', ',');
  }

  static String data(String iso) {
    return iso == null || iso.length() < 10 ? "" : iso.substring(8, 10) + iso.substring(5, 7) + iso.substring(0, 4);
  }

  static BigDecimal[] zeros(int n) {
    BigDecimal[] z = new BigDecimal[n];
    java.util.Arrays.fill(z, BigDecimal.ZERO);
    return z;
  }
}
