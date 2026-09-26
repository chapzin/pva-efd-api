import br.gov.serpro.vepxml.persistencia.IPersistencia;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

// Cruza os documentos escriturados (C100/D100, lidos das tabelas do PVA) com os
// XMLs de NF-e, NFC-e e CT-e: o que a malha da SEFAZ compara e o PVA não vê.
final class Cruzamento {
  private Cruzamento() {}

  static final BigDecimal TOLERANCIA = new BigDecimal("0.01");

  static final class Doc {
    String chave, tipo, modelo, emitente, destinatario, tomador, crt, tpNF;
    LocalDate emissao;
    BigDecimal valor = BigDecimal.ZERO, icms = BigDecimal.ZERO, credSN = BigDecimal.ZERO;
    String cStat;
    // Id do infNFe/infCte quando difere da chave autorizada no protocolo (nota regerada pelo ERP).
    String idAssinado;
    boolean cancelada;
    String arquivo, ufDestino, serieSat;
    int numero;
    // ICMS e CST/CSOSN dos itens por CFOP: é nessa granularidade que o C190 se compara com a nota.
    final Map<String, BigDecimal> icmsCfop = new TreeMap<>();
    final Map<String, Set<String>> cstCfop = new TreeMap<>();

    boolean autorizada() {
      return cStat == null || cStat.equals("100") || cStat.equals("150");
    }

    boolean denegada() {
      return cStat != null && (cStat.equals("110") || cStat.equals("301") || cStat.equals("302") || cStat.equals("303"));
    }
  }

  static final class Lote {
    final Map<String, Doc> docs = new HashMap<>();
    final Set<String> cancelamentos = new HashSet<>();
    final List<String> ignorados = new ArrayList<>();
    int lidos;
  }

  static DocumentBuilder parser() throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(false);
    f.setExpandEntityReferences(false);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    return f.newDocumentBuilder();
  }

  static void ler(Lote lote, String nome, byte[] xml, DocumentBuilder p) {
    lote.lidos++;
    try {
      Document d = p.parse(new ByteArrayInputStream(xml));
      Element raiz = d.getDocumentElement();
      Element infNFe = primeiro(raiz, "infNFe");
      Element infCte = primeiro(raiz, "infCte");
      Element evento = primeiro(raiz, "infEvento");
      if (raiz.getTagName().equals("CFeCanc")) {
        String ch = chaveDe(primeiro(raiz, "infCFe").getAttribute("chCanc"));
        if (ch != null && ch.length() == 44) lote.cancelamentos.add(ch);
      } else if (raiz.getTagName().equals("CFe")) {
        Doc x = cfe(primeiro(raiz, "infCFe"), nome);
        lote.docs.put(x.chave, x);
      } else if (infCte != null) {
        // Antes do infNFe: o CT-e lista as NF-e transportadas em infDoc/infNFe.
        Doc x = cte(infCte, raiz, nome);
        lote.docs.put(x.chave, x);
      } else if (infNFe != null) {
        Doc x = nfe(infNFe, raiz, nome);
        lote.docs.put(x.chave, x);
      } else if (evento != null) {
        String tp = texto(evento, "tpEvento");
        String ch = texto(evento, "chNFe") != null ? texto(evento, "chNFe") : texto(evento, "chCTe");
        String stat = texto(primeiro(raiz, "retEvento") == null ? raiz : primeiro(raiz, "retEvento"), "cStat");
        boolean homologado = stat == null || stat.equals("135") || stat.equals("136") || stat.equals("155");
        if ("110111".equals(tp) && ch != null && homologado) lote.cancelamentos.add(ch);
      } else {
        lote.ignorados.add(nome + " (raiz <" + raiz.getTagName() + "> não é NF-e, NFC-e, CT-e, CF-e nem evento)");
      }
    } catch (Exception e) {
      lote.ignorados.add(nome + " (" + motivo(e) + ")");
    }
  }

  static String motivo(Exception e) {
    if (e instanceof org.xml.sax.SAXException) return "XML malformado: " + e.getMessage();
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  private static Doc nfe(Element inf, Element raiz, String nome) {
    Doc d = new Doc();
    d.arquivo = nome;
    d.chave = chaveDe(inf.getAttribute("Id"));
    d.tipo = "NF-e";
    Element ide = primeiro(inf, "ide");
    d.modelo = texto(ide, "mod");
    if ("65".equals(d.modelo)) d.tipo = "NFC-e";
    d.tpNF = texto(ide, "tpNF");
    d.emissao = dia(texto(ide, "dhEmi") != null ? texto(ide, "dhEmi") : texto(ide, "dEmi"));
    Element emit = primeiro(inf, "emit");
    d.emitente = doc(emit);
    d.crt = texto(emit, "CRT");
    d.destinatario = doc(primeiro(inf, "dest"));
    d.ufDestino = texto(primeiro(inf, "enderDest"), "UF");
    itens(d, inf);
    Element tot = primeiro(inf, "ICMSTot");
    d.valor = num(texto(tot, "vNF"));
    d.icms = num(texto(tot, "vICMS"));
    NodeList cred = inf.getElementsByTagName("vCredICMSSN");
    for (int i = 0; i < cred.getLength(); i++) d.credSN = d.credSN.add(num(cred.item(i).getTextContent()));
    protocolo(d, primeiro(raiz, "infProt"), "chNFe");
    return d;
  }

  // A chave que vale é a do protocolo: há ERP que regera a nota e guarda o XML assinado com outro Id.
  private static void protocolo(Doc d, Element prot, String tag) {
    if (prot == null) return;
    d.cStat = texto(prot, "cStat");
    String ch = chaveDe(texto(prot, tag));
    if (ch != null && ch.length() == 44 && !ch.equals(d.chave)) {
      d.idAssinado = d.chave;
      d.chave = ch;
    }
  }

  // CST como na EFD: origem + CST (3 dígitos); CSOSN já tem 3.
  private static void itens(Doc d, Element inf) {
    NodeList dets = inf.getElementsByTagName("det");
    for (int i = 0; i < dets.getLength(); i++) {
      Element det = (Element) dets.item(i);
      String cfop = texto(primeiro(det, "prod"), "CFOP");
      Element icms = primeiro(primeiro(det, "imposto"), "ICMS");
      Element grupo = icms == null ? null : primeiroFilho(icms);
      String cst = null;
      if (grupo != null && texto(grupo, "CST") != null) {
        String orig = texto(grupo, "orig") != null ? texto(grupo, "orig") : texto(grupo, "Orig");
        cst = (orig == null ? "" : orig) + texto(grupo, "CST");
      } else if (grupo != null) {
        cst = texto(grupo, "CSOSN");
      }
      cfop = cfop == null ? "?" : cfop;
      d.icmsCfop.merge(cfop, num(grupo == null ? null : texto(grupo, "vICMS")), BigDecimal::add);
      if (cst != null) d.cstCfop.computeIfAbsent(cfop, k -> new TreeSet<>()).add(cst);
    }
  }

  private static Doc cfe(Element inf, String nome) {
    Doc d = new Doc();
    d.arquivo = nome;
    d.chave = chaveDe(inf.getAttribute("Id"));
    d.tipo = "CF-e";
    d.modelo = "59";
    d.tpNF = "1";
    Element ide = primeiro(inf, "ide");
    d.serieSat = Verificacoes.digitos(texto(ide, "nserieSAT"), 9);
    d.numero = Verificacoes.inteiro(texto(ide, "nCFe"));
    d.emissao = dia(texto(ide, "dEmi"));
    d.emitente = doc(primeiro(inf, "emit"));
    d.destinatario = doc(primeiro(inf, "dest"));
    Element total = primeiro(inf, "total");
    d.valor = num(texto(total, "vCFe"));
    d.icms = num(texto(primeiro(total, "ICMSTot"), "vICMS"));
    itens(d, inf);
    return d;
  }

  private static Doc cte(Element inf, Element raiz, String nome) {
    Doc d = new Doc();
    d.arquivo = nome;
    d.chave = chaveDe(inf.getAttribute("Id"));
    if (d.chave.length() != 44) throw new IllegalArgumentException("infCte sem Id de 44 dígitos (versao " + inf.getAttribute("versao") + ")");
    d.tipo = "CT-e";
    Element ide = primeiro(inf, "ide");
    d.modelo = texto(ide, "mod");
    d.emissao = dia(texto(ide, "dhEmi"));
    d.emitente = doc(primeiro(inf, "emit"));
    Map<String, String> partes = new HashMap<>();
    partes.put("0", doc(primeiro(inf, "rem")));
    partes.put("1", doc(primeiro(inf, "exped")));
    partes.put("2", doc(primeiro(inf, "receb")));
    partes.put("3", doc(primeiro(inf, "dest")));
    Element toma4 = primeiro(ide, "toma4");
    if (toma4 != null) {
      d.tomador = doc(toma4);
    } else {
      Element toma3 = primeiro(ide, "toma3") != null ? primeiro(ide, "toma3") : primeiro(ide, "toma");
      d.tomador = toma3 == null ? null : partes.get(texto(toma3, "toma"));
    }
    d.destinatario = partes.get("3");
    Element vPrest = primeiro(inf, "vPrest");
    d.valor = num(texto(vPrest, "vTPrest"));
    Element imp = primeiro(inf, "imp");
    d.icms = num(texto(imp, "vICMS"));
    protocolo(d, primeiro(raiz, "infProt"), "chCTe");
    return d;
  }

  static List<Map<String, Object>> cruzar(IPersistencia per, Lote lote, Map<String, Object> resumo, Map<String, Object> estatistica)
      throws Exception {
    for (String ch : lote.cancelamentos) if (lote.docs.containsKey(ch)) lote.docs.get(ch).cancelada = true;
    Map<?, ?> contrib = (Map<?, ?>) resumo.get("contribuinte");
    Map<?, ?> periodo = (Map<?, ?>) resumo.get("periodo");
    String eu = contrib.get("cnpj") != null ? (String) contrib.get("cnpj") : (String) contrib.get("cpf");
    LocalDate ini = LocalDate.parse((String) periodo.get("inicio")), fim = LocalDate.parse((String) periodo.get("fim"));

    List<Map<String, Object>> naoEscrit = new ArrayList<>(), semXml = new ArrayList<>(), valor = new ArrayList<>(),
        credMaior = new ArrayList<>(), debMenor = new ArrayList<>(), cancel = new ArrayList<>(), deneg = new ArrayList<>(),
        invertida = new ArrayList<>(), terceiro = new ArrayList<>(), naoTomador = new ArrayList<>(), simples = new ArrayList<>(),
        idNaoAutorizado = new ArrayList<>(), idDiverge = new ArrayList<>();
    Map<String, Doc> porId = new HashMap<>();
    for (Doc x : lote.docs.values()) if (x.idAssinado != null) porId.put(x.idAssinado, x);
    Set<String> idEscriturado = new HashSet<>();

    Map<String, Map<String, BigDecimal>> c190Icms = new HashMap<>();
    Map<String, Map<String, Set<String>>> c190Cst = new HashMap<>();
    for (Map<String, String> r : Verificacoes.linhas(per, "SELECT c.LINHA, a.CFOP, a.CST_ICMS, a.VL_ICMS FROM reg_c190 a"
        + " JOIN reg_c100 c ON a.ID_PAI = c.ID WHERE c.IND_OPER = 1 AND c.IND_EMIT = 0 UNION ALL SELECT c.LINHA, a.CFOP,"
        + " a.CST_ICMS, a.VL_ICMS FROM reg_c850 a JOIN reg_c800 c ON a.ID_PAI = c.ID")) {
      c190Icms.computeIfAbsent(r.get("LINHA"), k -> new TreeMap<>()).merge(r.get("CFOP"), Verificacoes.dec(r.get("VL_ICMS")), BigDecimal::add);
      c190Cst.computeIfAbsent(r.get("LINHA"), k -> new TreeMap<>()).computeIfAbsent(r.get("CFOP"), k -> new TreeSet<>()).add(r.get("CST_ICMS"));
    }

    Set<String> escrituradas = new HashSet<>();
    List<Map<String, String>> docsEfd = new ArrayList<>(Verificacoes.linhas(per,
        "SELECT 'C100' REG, LINHA, IND_OPER, IND_EMIT, COD_MOD, COD_SIT, NUM_DOC, CHV_NFE CHV, VL_DOC, VL_ICMS FROM reg_c100"));
    docsEfd.addAll(Verificacoes.linhas(per,
        "SELECT 'C800' REG, LINHA, '1' IND_OPER, '0' IND_EMIT, COD_MOD, COD_SIT, NUM_CFE NUM_DOC, CHV_CFE CHV, VL_CFE VL_DOC,"
            + " VL_ICMS FROM reg_c800"));
    docsEfd.addAll(Verificacoes.linhas(per,
        "SELECT 'D100' REG, LINHA, IND_OPER, IND_EMIT, COD_MOD, COD_SIT, NUM_DOC, CHV_CTE CHV, VL_DOC, VL_ICMS FROM reg_d100"));
    int casados = 0;
    {
      for (Map<String, String> r : docsEfd) {
        String reg = r.get("REG");
        boolean ehCte = reg.equals("D100");
        String ch = Verificacoes.digitos(r.get("CHV"), 44);
        if (ch.isEmpty()) continue;
        escrituradas.add(ch);
        int sit = Verificacoes.inteiro(r.get("COD_SIT"));
        int oper = Verificacoes.inteiro(r.get("IND_OPER")), emissao = Verificacoes.inteiro(r.get("IND_EMIT"));
        BigDecimal vDoc = Verificacoes.dec(r.get("VL_DOC")), vIcms = Verificacoes.dec(r.get("VL_ICMS"));
        Doc x = lote.docs.get(ch);
        Map<String, Object> base = Json.obj("registro", reg, "linha", Verificacoes.inteiro(r.get("LINHA")), "documento", r.get("NUM_DOC"),
            "chave", ch);
        if (x == null && porId.containsKey(ch)) {
          Doc a = porId.get(ch);
          idEscriturado.add(a.chave);
          idNaoAutorizado.add(com(base, "chaveAutorizada", a.chave, "arquivoXml", a.arquivo, "valor", vIcms));
          continue;
        }
        if (x == null) {
          if (sit != 2 && sit != 3 && sit != 4 && sit != 5) semXml.add(base);
          continue;
        }
        casados++;
        base.put("arquivoXml", x.arquivo);
        boolean canceladaNaEfd = sit == 2 || sit == 3;
        if (x.cancelada || (x.cStat != null && x.cStat.equals("101"))) {
          if (!canceladaNaEfd) cancel.add(com(base, "codSit", r.get("COD_SIT"), "valor", vDoc));
          continue;
        }
        if (x.denegada()) {
          if (sit != 4) deneg.add(com(base, "codSit", r.get("COD_SIT")));
          continue;
        }
        if (canceladaNaEfd) continue;
        if (vDoc.subtract(x.valor).abs().compareTo(TOLERANCIA) > 0) {
          valor.add(com(base, "escriturado", vDoc, "xml", x.valor, "valor", vDoc.subtract(x.valor).abs()));
        }
        if (!ehCte) {
          boolean simplesNacional = "1".equals(x.crt) || "4".equals(x.crt);
          if (oper == 0 && emissao == 1 && !simplesNacional && vIcms.subtract(x.icms).compareTo(TOLERANCIA) > 0) {
            credMaior.add(com(base, "creditado", vIcms, "destacado", x.icms, "valor", vIcms.subtract(x.icms)));
          }
          if (oper == 1 && emissao == 0 && x.icms.subtract(vIcms).compareTo(TOLERANCIA) > 0) {
            debMenor.add(com(base, "debitado", vIcms, "destacado", x.icms, "valor", x.icms.subtract(vIcms), "ufDestino", x.ufDestino,
                "porCfop", porCfop(x, c190Icms.get(r.get("LINHA")), c190Cst.get(r.get("LINHA")))));
          }
          if (emissao == 0 && x.tpNF != null && Verificacoes.inteiro(x.tpNF) != oper) {
            invertida.add(com(base, "indOperEfd", r.get("IND_OPER"), "tpNFXml", x.tpNF));
          }
          if (eu != null && !eu.equals(x.emitente) && !eu.equals(x.destinatario)) {
            terceiro.add(com(base, "emitente", x.emitente, "destinatario", x.destinatario));
          }
          if (oper == 0 && vIcms.signum() > 0 && simplesNacional) {
            BigDecimal limite = "4".equals(x.crt) ? BigDecimal.ZERO : x.credSN;
            if (vIcms.subtract(limite).compareTo(TOLERANCIA) > 0) {
              simples.add(com(base, "crt", x.crt, "creditado", vIcms, "permitido", limite, "valor", vIcms.subtract(limite)));
            }
          }
        } else if (oper == 0 && vIcms.signum() > 0 && eu != null && x.tomador != null && !eu.equals(x.tomador)) {
          naoTomador.add(com(base, "tomador", x.tomador, "valor", vIcms));
        }
      }
    }

    List<Map<String, Object>> satFora = new ArrayList<>(), satSemResumo = new ArrayList<>(), satDebMenor = new ArrayList<>();
    List<Map<String, Object>> satProvCanc = new ArrayList<>();
    Set<String> cobertosPorResumo = sat(per, lote, eu, ini, fim, escrituradas, c190Icms, satFora, satSemResumo, satDebMenor, satProvCanc);

    for (Doc x : lote.docs.values()) {
      if (escrituradas.contains(x.chave) || cobertosPorResumo.contains(x.chave) || x.cancelada || !x.autorizada()
          || x.emissao == null) continue;
      if (x.emissao.isBefore(ini) || x.emissao.isAfter(fim)) continue;
      boolean minha = eu != null && (eu.equals(x.emitente) || eu.equals(x.destinatario) || eu.equals(x.tomador));
      if (!minha) continue;
      String papel = eu.equals(x.emitente) ? "emitente" : eu.equals(x.tomador) ? "tomador" : "destinatário";
      naoEscrit.add(Json.obj("tipo", x.tipo, "chave", x.chave, "emissao", x.emissao.toString(), "papel", papel, "valor", x.valor,
          "arquivoXml", x.arquivo));
    }

    for (Doc x : lote.docs.values()) {
      if (x.idAssinado == null || idEscriturado.contains(x.chave)) continue;
      idDiverge.add(Json.obj("tipo", x.tipo, "chave", x.chave, "idAssinado", x.idAssinado, "escriturada", escrituradas.contains(x.chave),
          "arquivoXml", x.arquivo));
    }

    estatistica.put("xmlsLidos", lote.lidos);
    estatistica.put("documentos", lote.docs.size());
    estatistica.put("eventosCancelamento", lote.cancelamentos.size());
    estatistica.put("casadosComEscrituracao", casados);
    estatistica.put("ignorados", lote.ignorados.size() > 50 ? lote.ignorados.subList(0, 50) : lote.ignorados);

    List<Map<String, Object>> out = new ArrayList<>();
    add(out, naoEscrit, "XML_NAO_ESCRITURADO", "alerta", "Documento autorizado no período e não escriturado",
        "NF-e/NFC-e/CT-e emitido no período em que o contribuinte é emitente, destinatário ou tomador, sem C100/D100"
            + " correspondente. É o cruzamento nº 1 das malhas estaduais. Nota de entrada pode ter sido lançada em outro mês:"
            + " confira antes de corrigir.",
        "Ajuste SINIEF 02/2009; Guia Prático EFD ICMS/IPI (C100/D100); malhas NF-e × EFD das SEFAZ");
    add(out, cancel, "CANCELADA_ESCRITURADA", "alerta", "Documento cancelado escriturado como regular",
        "O XML traz evento ou protocolo de cancelamento, mas a EFD informa COD_SIT diferente de 02/03. Débito ou crédito de"
            + " documento que não existe.",
        "Guia Prático EFD ICMS/IPI, tabela 4.1.2 (COD_SIT)");
    add(out, deneg, "DENEGADA_ESCRITURADA", "alerta", "Documento denegado escriturado sem COD_SIT 04",
        "O protocolo do XML é de denegação; a EFD deve informar COD_SIT 04 sem valores.",
        "Guia Prático EFD ICMS/IPI, tabela 4.1.2 (COD_SIT)");
    add(out, credMaior, "CREDITO_MAIOR_QUE_DESTACADO", "alerta", "Crédito de ICMS maior que o destacado na NF-e",
        "O ICMS escriturado na entrada supera o destacado no XML do fornecedor. É o cruzamento de autorregularização usado,"
            + " por exemplo, pela SEFAZ-RS.",
        "LC 87/1996, art. 23");
    add(out, simples, "CREDITO_SIMPLES_ACIMA_PERMITIDO", "alerta", "Crédito de nota do Simples Nacional acima do permitido",
        "Fornecedor do Simples (CRT 1) só transfere o crédito informado em pCredSN/vCredICMSSN; MEI (CRT 4) não transfere"
            + " crédito. Crédito acima disso é indicador de malha (ex.: SEFAZ-CE, indicador 45).",
        "LC 123/2006, art. 23, §1º");
    add(out, debMenor, "DEBITO_MENOR_QUE_DESTACADO", "atencao", "Débito de ICMS menor que o destacado na NF-e própria",
        "A nota de saída destaca mais ICMS do que a EFD debita. O fisco cobra a diferença pelo valor do XML. porCfop compara"
            + " os itens do XML com o C190: CST 00 na nota e 060 na EFD (CFOP 6403/6404) indica C190 montado pelo cadastro do"
            + " ERP, não pela nota emitida.",
        "LC 87/1996, art. 13");
    add(out, satFora, "CFE_FORA_DO_RESUMO_SAT", "alerta", "CF-e emitido fora do intervalo do resumo diário (C860)",
        "O equipamento SAT emitiu o cupom no dia, mas o número não está entre DOC_INI e DOC_FIM do C860 daquele SAT e data:"
            + " a venda não entrou na apuração.",
        "Guia Prático EFD ICMS/IPI (C860: DOC_INI/DOC_FIM)");
    add(out, satSemResumo, "CFE_SEM_RESUMO_SAT", "alerta", "Dia de venda no SAT sem C860 nem C800",
        "Há CF-e autorizados do equipamento na data, e a EFD não traz resumo diário (C860) nem os cupons (C800) desse SAT e dia.",
        "Guia Prático EFD ICMS/IPI (C800/C860)");
    add(out, satDebMenor, "DEBITO_SAT_MENOR_QUE_XML", "atencao", "ICMS do resumo diário do SAT menor que o dos cupons",
        "Soma do ICMS dos CF-e do SAT no dia, por CFOP, maior que o C890. porCfop mostra o CST de cada lado; CST de ST na EFD"
            + " e tributado no cupom é cadastro do ERP divergente do que o SAT emitiu. Cupom cancelado sem o XML de"
            + " cancelamento na pasta também aparece aqui: confira cuponsCancelados.",
        "LC 87/1996, art. 13; Guia Prático EFD ICMS/IPI (C890)");
    add(out, satProvCanc, "CFE_PROVAVEL_CANCELADO_SEM_XML", "info", "Cupom SAT provavelmente cancelado, sem o XML de cancelamento",
        "O valor dos cupons do dia excede o VL_OPR do C890 exatamente pelo valor deste(s) cupom(ns): o ERP o tratou como"
            + " cancelado. Ele sai da comparação de ICMS. Colete o CFeCanc para confirmar; se não existir, a venda está fora da"
            + " apuração.",
        "Guia Prático EFD ICMS/IPI (C860/C890)");
    add(out, valor, "VALOR_DIVERGENTE_DO_XML", "atencao", "Valor do documento diferente do XML",
        "VL_DOC escriturado difere do vNF/vTPrest do XML.", "Guia Prático EFD ICMS/IPI (C100/D100, campo VL_DOC)");
    add(out, invertida, "OPERACAO_INVERTIDA", "alerta", "Nota própria com entrada/saída trocada",
        "Em documento de emissão própria, IND_OPER tem de seguir o tpNF do XML (0 = entrada, 1 = saída).",
        "Guia Prático EFD ICMS/IPI (C100, IND_OPER)");
    add(out, terceiro, "CHAVE_DE_TERCEIRO", "alerta", "NF-e escriturada em que o contribuinte não é emitente nem destinatário",
        "O CNPJ do informante não aparece na nota. Crédito de nota de terceiro é glosado.",
        "LC 87/1996, art. 23");
    add(out, naoTomador, "CTE_SEM_SER_TOMADOR", "alerta", "Crédito de CT-e em que o contribuinte não é o tomador",
        "Só o tomador do serviço de transporte pode se creditar do ICMS do CT-e.", "LC 87/1996, art. 20 e 23");
    add(out, idNaoAutorizado, "CHAVE_NAO_AUTORIZADA_ESCRITURADA", "alerta", "Documento escriturado com a chave do XML regerado, não com a autorizada",
        "O XML assinado traz um Id diferente da chave que a SEFAZ autorizou (protocolo). A EFD usou o Id, que não existe na"
            + " SEFAZ. Costuma vir de ERP que regera a nota; se a chave autorizada também estiver escriturada em outro mês, a"
            + " operação foi lançada duas vezes.",
        "Ajuste SINIEF 07/2005 (chave de acesso); Guia Prático EFD ICMS/IPI (C100, CHV_NFE)");
    add(out, idDiverge, "XML_CHAVE_DIFERE_PROTOCOLO", "info", "XML com Id diferente da chave autorizada",
        "O cruzamento usou a chave do protocolo, que é a que vale. Guarde o XML autorizado de verdade: este arquivo não é o"
            + " que a SEFAZ recebeu.",
        "Ajuste SINIEF 07/2005 (chave de acesso)");
    add(out, semXml, "ESCRITURADO_SEM_XML", "info", "Documento escriturado sem XML na pasta enviada",
        "Normal se a pasta não tiver todos os XMLs; útil para descobrir o que falta coletar.", "—");
    return out;
  }

  // Resumo diário do SAT (C860/C890): o cupom não tem chave na EFD, casa por equipamento, data e faixa de numeração.
  private static Set<String> sat(IPersistencia per, Lote lote, String eu, LocalDate ini, LocalDate fim, Set<String> escrituradas,
      Map<String, Map<String, BigDecimal>> c190Icms, List<Map<String, Object>> fora, List<Map<String, Object>> semResumo,
      List<Map<String, Object>> debMenor, List<Map<String, Object>> provCanc) throws Exception {
    Set<String> cobertos = new HashSet<>();
    Map<String, List<Doc>> porDia = new TreeMap<>();
    Map<String, Integer> cancelados = new HashMap<>();
    for (Doc x : lote.docs.values()) {
      if (!"CF-e".equals(x.tipo) || x.emissao == null || eu == null || !eu.equals(x.emitente) || escrituradas.contains(x.chave)) continue;
      if (x.emissao.isBefore(ini) || x.emissao.isAfter(fim)) continue;
      String k = x.serieSat + "|" + x.emissao;
      if (x.cancelada) {
        cancelados.merge(k, 1, Integer::sum);
        cobertos.add(x.chave);
      } else {
        porDia.computeIfAbsent(k, z -> new ArrayList<>()).add(x);
      }
    }
    if (porDia.isEmpty()) return cobertos;
    Map<String, List<Map<String, String>>> resumos = new HashMap<>();
    for (Map<String, String> r : Verificacoes.linhas(per, "SELECT ID, LINHA, NR_SAT, DT_DOC, DOC_INI, DOC_FIM FROM reg_c860")) {
      LocalDate d = Verificacoes.data(r.get("DT_DOC"));
      resumos.computeIfAbsent(Verificacoes.digitos(r.get("NR_SAT"), 9) + "|" + d, z -> new ArrayList<>()).add(r);
    }
    Map<String, BigDecimal> c890Opr = new HashMap<>();
    Map<String, Map<String, BigDecimal>> c890 = new HashMap<>();
    Map<String, Map<String, Set<String>>> c890Cst = new HashMap<>();
    for (Map<String, String> r : Verificacoes.linhas(per, "SELECT ID_PAI, CFOP, CST_ICMS, VL_OPR, VL_ICMS FROM reg_c890")) {
      c890Opr.merge(r.get("ID_PAI"), Verificacoes.dec(r.get("VL_OPR")), BigDecimal::add);
      c890.computeIfAbsent(r.get("ID_PAI"), z -> new TreeMap<>()).merge(r.get("CFOP"), Verificacoes.dec(r.get("VL_ICMS")), BigDecimal::add);
      c890Cst.computeIfAbsent(r.get("ID_PAI"), z -> new TreeMap<>()).computeIfAbsent(r.get("CFOP"), z -> new TreeSet<>()).add(r.get("CST_ICMS"));
    }
    for (Map.Entry<String, List<Doc>> e : porDia.entrySet()) {
      String[] k = e.getKey().split("\\|");
      List<Map<String, String>> rs = resumos.getOrDefault(e.getKey(), List.of());
      if (rs.isEmpty()) {
        BigDecimal v = BigDecimal.ZERO;
        for (Doc x : e.getValue()) v = v.add(x.valor);
        semResumo.add(Json.obj("nrSat", k[0], "data", k[1], "cupons", e.getValue().size(), "valor", v));
        for (Doc x : e.getValue()) cobertos.add(x.chave);
        continue;
      }
      Doc soma = new Doc();
      Map<String, BigDecimal> efd = new TreeMap<>();
      Map<String, Set<String>> cstEfd = new TreeMap<>();
      BigDecimal oprEfd = BigDecimal.ZERO;
      for (Map<String, String> r : rs) {
        oprEfd = oprEfd.add(c890Opr.getOrDefault(r.get("ID"), BigDecimal.ZERO));
        c890.getOrDefault(r.get("ID"), Map.of()).forEach((cf, v) -> efd.merge(cf, v, BigDecimal::add));
        c890Cst.getOrDefault(r.get("ID"), Map.of()).forEach((cf, c) -> cstEfd.computeIfAbsent(cf, z -> new TreeSet<>()).addAll(c));
      }
      List<Doc> resumidos = new ArrayList<>();
      for (Doc x : e.getValue()) {
        cobertos.add(x.chave);
        boolean dentro = false;
        for (Map<String, String> r : rs) {
          dentro |= x.numero >= Verificacoes.inteiro(r.get("DOC_INI")) && x.numero <= Verificacoes.inteiro(r.get("DOC_FIM"));
        }
        if (!dentro) {
          fora.add(Json.obj("registro", "C860", "linha", Verificacoes.inteiro(rs.get(0).get("LINHA")), "documento", String.valueOf(x.numero),
              "chave", x.chave, "nrSat", k[0], "data", k[1], "faixa", rs.get(0).get("DOC_INI") + "-" + rs.get(0).get("DOC_FIM"),
              "valor", x.valor, "arquivoXml", x.arquivo));
          continue;
        }
        resumidos.add(x);
      }
      BigDecimal oprXml = BigDecimal.ZERO;
      for (Doc x : resumidos) oprXml = oprXml.add(x.valor);
      List<Doc> canc = provaveisCancelados(resumidos, oprXml.subtract(oprEfd));
      for (Doc x : canc) {
        provCanc.add(Json.obj("registro", "C860", "linha", Verificacoes.inteiro(rs.get(0).get("LINHA")), "documento", String.valueOf(x.numero),
            "chave", x.chave, "nrSat", k[0], "data", k[1], "valor", x.valor, "icms", x.icms, "arquivoXml", x.arquivo));
      }
      for (Doc x : resumidos) {
        if (canc.contains(x)) continue;
        x.icmsCfop.forEach((cf, v) -> soma.icmsCfop.merge(cf, v, BigDecimal::add));
        x.cstCfop.forEach((cf, c) -> soma.cstCfop.computeIfAbsent(cf, z -> new TreeSet<>()).addAll(c));
      }
      BigDecimal xml = soma.icmsCfop.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal deb = efd.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      if (xml.subtract(deb).compareTo(TOLERANCIA) > 0) {
        debMenor.add(Json.obj("registro", "C860", "linha", Verificacoes.inteiro(rs.get(0).get("LINHA")), "nrSat", k[0], "data", k[1],
            "cupons", e.getValue().size(), "cuponsCancelados", cancelados.getOrDefault(e.getKey(), 0), "provaveisCancelados", canc.size(),
            "valorCupons", oprXml, "valorC890", oprEfd, "debitado", deb,
            "destacado", xml, "valor", xml.subtract(deb), "porCfop", porCfop(soma, efd, cstEfd)));
      }
    }
    return cobertos;
  }

  // Cupom cancelado cujo CFeCanc não veio: o C890 fica menor exatamente pelo valor de 1 ou 2 cupons do dia.
  static List<Doc> provaveisCancelados(List<Doc> cupons, BigDecimal excesso) {
    BigDecimal tol = new BigDecimal("0.05");
    if (excesso.compareTo(tol) <= 0) return List.of();
    for (Doc a : cupons) if (a.valor.subtract(excesso).abs().compareTo(tol) <= 0) return List.of(a);
    for (int i = 0; i < cupons.size(); i++) {
      for (int j = i + 1; j < cupons.size(); j++) {
        if (cupons.get(i).valor.add(cupons.get(j).valor).subtract(excesso).abs().compareTo(tol) <= 0) {
          return List.of(cupons.get(i), cupons.get(j));
        }
      }
    }
    return List.of();
  }

  // Diferença por CFOP entre os itens do XML e o C190. Padrão típico: CFOP 6403/6404 com CST 00 na nota e 060 na EFD
  // (o cadastro do ERP diz ST, a nota tributou) ou saída interna com alíquota menor no C190.
  static List<Map<String, Object>> porCfop(Doc x, Map<String, BigDecimal> efd, Map<String, Set<String>> cstEfd) {
    Map<String, BigDecimal> e = efd == null ? Map.of() : efd;
    Map<String, Set<String>> c = cstEfd == null ? Map.of() : cstEfd;
    Set<String> cfops = new TreeSet<>(x.icmsCfop.keySet());
    cfops.addAll(e.keySet());
    List<Map<String, Object>> out = new ArrayList<>();
    for (String cf : cfops) {
      BigDecimal nx = x.icmsCfop.getOrDefault(cf, BigDecimal.ZERO), ne = e.getOrDefault(cf, BigDecimal.ZERO);
      if (nx.subtract(ne).abs().compareTo(TOLERANCIA) <= 0) continue;
      out.add(Json.obj("cfop", cf, "cstXml", String.join("/", x.cstCfop.getOrDefault(cf, Set.of())),
          "cstEfd", e.containsKey(cf) ? String.join("/", c.getOrDefault(cf, Set.of())) : null, "icmsXml", nx, "icmsEfd", ne,
          "diferenca", nx.subtract(ne)));
    }
    return out;
  }

  private static void add(List<Map<String, Object>> out, List<Map<String, Object>> oc, String codigo, String nivel, String titulo,
      String explicacao, String fundamento) {
    if (!oc.isEmpty()) out.add(Verificacoes.achado(codigo, nivel, titulo, explicacao, fundamento, oc));
  }

  private static Map<String, Object> com(Map<String, Object> base, Object... kv) {
    Map<String, Object> m = new java.util.LinkedHashMap<>(base);
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private static String chaveDe(String id) {
    return id == null ? null : id.replaceAll("[^0-9]", "");
  }

  private static Element primeiro(Element e, String tag) {
    if (e == null) return null;
    NodeList l = e.getElementsByTagName(tag);
    return l.getLength() == 0 ? null : (Element) l.item(0);
  }

  private static Element primeiroFilho(Element e) {
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c) return c;
    return null;
  }

  private static String texto(Element e, String tag) {
    Element x = primeiro(e, tag);
    return x == null ? null : x.getTextContent().trim();
  }

  private static String doc(Element e) {
    if (e == null) return null;
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element c && (c.getTagName().equals("CNPJ") || c.getTagName().equals("CPF"))) {
        return Verificacoes.digitos(c.getTextContent(), c.getTagName().equals("CNPJ") ? 14 : 11);
      }
    }
    return null;
  }

  private static BigDecimal num(String s) {
    return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim());
  }

  private static LocalDate dia(String s) {
    if (s != null && s.matches("\\d{8}")) return LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    return s == null || s.length() < 10 ? null : LocalDate.parse(s.substring(0, 10));
  }
}
