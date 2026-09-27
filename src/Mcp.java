import br.gov.serpro.sped.fiscalpva.dominio.entidades.EscrituracaoFiscal;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.escrituracaofiscal.ControleEscrituracaoFiscal;
import br.gov.serpro.sped.fiscalpva.persistencia.PersistenciaFiscalPVA;
import br.gov.serpro.vepxml.persistencia.IPersistencia;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

// Servidor MCP (Streamable HTTP, só respostas JSON) em POST /mcp. O Claude abre uma
// escrituração uma vez (efd_abrir), investiga por páginas e SQL e fecha no fim.
// Os arquivos vêm da pasta montada em /dados: o contêiner não enxerga o disco do host.
final class Mcp {
  private Mcp() {}

  static final String[] VERSOES = {"2025-06-18", "2025-03-26", "2024-11-05"};
  static final Path DADOS = Path.of(System.getenv().getOrDefault("PVA_DADOS_CONTAINER", "/dados"));
  static final String HOST = System.getenv().getOrDefault("PVA_DADOS_HOST", "").replaceAll("/+$", "");
  static final int MAX_SESSOES = Integer.parseInt(System.getenv().getOrDefault("PVA_MCP_SESSOES", "4"));
  static final long TTL_MS = Long.parseLong(System.getenv().getOrDefault("PVA_MCP_TTL_MIN", "60")) * 60_000;
  static final int MAX_CARACTERES = 100_000;
  static final SecureRandom RANDOM = new SecureRandom();

  static final class Sessao {
    final String id;
    String arquivo;
    final String chave;
    final String pastaXml;
    EscrituracaoFiscal esc;
    Map<String, Object> out;
    // Operações de efd_editar ainda não exportadas: o resultado da validação em `out` é o de antes delas.
    int pendentes;
    final long abertaEm = System.currentTimeMillis();
    long usadaEm = abertaEm;

    Sessao(String id, String arquivo, String chave, String pastaXml, EscrituracaoFiscal esc, Map<String, Object> out) {
      this.id = id;
      this.arquivo = arquivo;
      this.chave = chave;
      this.pastaXml = pastaXml;
      this.esc = esc;
      this.out = out;
    }
  }

  // Ordem de abertura: ao passar do limite, fecha a mais antiga.
  static final Map<String, Sessao> SESSOES = new LinkedHashMap<>();

  static void iniciar() {
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(Mcp::expirar, 1, 1, TimeUnit.MINUTES);
  }

  static void expirar() {
    synchronized (PvaServer.class) {
      long agora = System.currentTimeMillis();
      for (Sessao s : new ArrayList<>(SESSOES.values())) {
        if (agora - s.usadaEm > TTL_MS) fechar(s);
      }
    }
  }

  static void fechar(Sessao s) {
    synchronized (PvaServer.class) {
      SESSOES.remove(s.id);
      if (s.esc != null) {
        try {
          ControleEscrituracaoFiscal.getSingleton().apagarEscrituracaoBanco(s.esc);
        } catch (Throwable e) {
          System.err.println("mcp: apagar escrituracao " + s.id + ": " + e);
        }
      }
    }
  }

  // CNPJ (ou CPF) + período do 0000: é o que identifica a escrituração no banco do PVA.
  static String chave(Path arq) {
    try (var r = Files.newBufferedReader(arq, StandardCharsets.ISO_8859_1)) {
      String l = r.readLine();
      if (l == null || !l.startsWith("|0000|")) return null;
      String[] c = l.split("\\|", -1);
      return c.length > 8 ? c[7] + c[8] + "|" + c[4] + "|" + c[5] : null;
    } catch (IOException e) {
      return null;
    }
  }

  static List<String> liberarMesmaEscrituracao(Path arq) {
    List<String> fechadas = new ArrayList<>();
    String k = chave(arq);
    if (k == null) return fechadas;
    synchronized (PvaServer.class) {
      for (Sessao s : new ArrayList<>(SESSOES.values())) {
        if (k.equals(s.chave)) {
          fechar(s);
          fechadas.add(s.id);
        }
      }
    }
    return fechadas;
  }

  // ---------------------------------------------------------------- HTTP / JSON-RPC

  static void tratar(HttpExchange ex) throws IOException {
    try {
      String origem = ex.getRequestHeaders().getFirst("Origin");
      if (origem != null && !origem.matches("https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?")) {
        PvaServer.responder(ex, 403, "{\"erro\":\"origem não permitida\"}");
        return;
      }
      if (!"POST".equals(ex.getRequestMethod())) {
        ex.getResponseHeaders().set("Allow", "POST");
        PvaServer.responder(ex, 405, "{\"erro\":\"MCP Streamable HTTP: use POST com JSON-RPC\"}");
        return;
      }
      String corpo;
      try (InputStream in = ex.getRequestBody()) {
        corpo = new String(in.readNBytes(4 * 1024 * 1024), StandardCharsets.UTF_8);
      }
      Object msg;
      try {
        msg = Json.parse(corpo);
      } catch (RuntimeException e) {
        PvaServer.responder(ex, 400, Json.of(erroRpc(null, -32700, "JSON inválido: " + e.getMessage())));
        return;
      }
      Object resposta;
      if (msg instanceof List<?> lote) {
        List<Object> rs = new ArrayList<>();
        for (Object m : lote) {
          Object r = mensagem(m);
          if (r != null) rs.add(r);
        }
        resposta = rs.isEmpty() ? null : rs;
      } else {
        resposta = mensagem(msg);
      }
      if (resposta == null) {
        ex.sendResponseHeaders(202, -1);
        ex.close();
      } else {
        PvaServer.responder(ex, 200, Json.of(resposta));
      }
    } catch (Throwable e) {
      PvaServer.responder(ex, 500, Json.of(erroRpc(null, -32603, String.valueOf(e))));
    }
  }

  static Map<String, Object> erroRpc(Object id, int codigo, String mensagem) {
    return Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", codigo, "message", mensagem));
  }

  // Notificação e resposta do cliente não têm retorno (null vira 202).
  static Object mensagem(Object m) {
    if (!(m instanceof Map<?, ?> req) || !(req.get("method") instanceof String metodo)) return null;
    if (!req.containsKey("id")) return null;
    Object id = req.get("id");
    Map<?, ?> p = req.get("params") instanceof Map<?, ?> x ? x : Map.of();
    try {
      Object r = switch (metodo) {
        case "initialize" -> inicializar(p);
        case "ping" -> Map.of();
        case "tools/list" -> Json.obj("tools", ferramentas());
        case "tools/call" -> chamar(p);
        default -> null;
      };
      if (r == null) return erroRpc(id, -32601, "método não suportado: " + metodo);
      return Json.obj("jsonrpc", "2.0", "id", id, "result", r);
    } catch (Throwable e) {
      return erroRpc(id, -32603, String.valueOf(e));
    }
  }

  static Map<String, Object> inicializar(Map<?, ?> p) {
    String pedida = String.valueOf(p.get("protocolVersion"));
    String versao = VERSOES[0];
    for (String v : VERSOES) if (v.equals(pedida)) versao = v;
    return Json.obj("protocolVersion", versao, "capabilities", Json.obj("tools", Json.obj("listChanged", false)),
        "serverInfo", Json.obj("name", "pva-efd-api", "version", PvaServer.versao),
        "instructions", "PVA EFD ICMS/IPI oficial da Receita (" + PvaServer.versao + ") rodando sem tela. Fluxo: efd_abrir importa"
            + " a EFD uma vez e devolve um resumo com a sessão; efd_detalhes pagina erros, verificações de malha e achados do"
            + " cruzamento; efd_consultar roda SELECT/SHOW/DESCRIBE no banco que o PVA montou (tabelas reg_0000, reg_c100,"
            + " reg_c190, reg_e110...); efd_editar altera, inclui ou exclui registros (IDs vêm do efd_consultar) e"
            + " efd_gerar_arquivo exporta o TXT pelo PVA e revalida; efd_fechar libera a sessão. Arquivos: caminhos dentro da pasta montada no contêiner"
            + (HOST.isEmpty() ? " (relativos a ela)" : " (" + HOST + " no host, ou relativos a ela)")
            + "; arquivos_listar mostra o que está lá. O PVA valida uma escrituração por vez: chamadas esperam na fila."
            + " Achados de malha são indícios para conferência, não autuação.");
  }

  // ---------------------------------------------------------------- ferramentas

  record Ferramenta(String nome, String descricao, Map<String, Object> propriedades, List<String> obrigatorias) {}

  static Map<String, Object> prop(String tipo, String descricao) {
    return Json.obj("type", tipo, "description", descricao);
  }

  static final Map<String, Object> PAG = Json.obj(
      "pagina", prop("integer", "Página, a partir de 1."),
      "por_pagina", prop("integer", "Itens por página (padrão 50, máximo 200)."));

  static Map<String, Object> props(Object... kv) {
    return Json.obj(kv);
  }

  static Map<String, Object> comPag(Map<String, Object> m) {
    Map<String, Object> r = new LinkedHashMap<>(m);
    r.putAll(PAG);
    return r;
  }

  static final List<Ferramenta> FERRAMENTAS = List.of(
      new Ferramenta("pva_saude", "Estado do PVA (versão, tabelas externas), pasta de dados montada e sessões abertas.", props(), List.of()),
      new Ferramenta("arquivos_listar", "Lista arquivos da pasta montada (EFD .txt, pastas de XML). Use para achar o caminho certo.",
          comPag(props("pasta", prop("string", "Subpasta (relativa à pasta montada ou caminho do host). Vazio = raiz."),
              "padrao", prop("string", "Filtro glob no nome, ex.: *.txt"),
              "recursivo", prop("boolean", "Desce nas subpastas (padrão false)."))), List.of()),
      new Ferramenta("efd_abrir", "Importa uma EFD ICMS/IPI no PVA oficial, roda a validação, as verificações de malha e, se vier"
          + " pasta_xml, o cruzamento EFD × XML (NF-e, NFC-e, CF-e SAT, CT-e, eventos). Devolve um resumo compacto e o id da sessão"
          + " para efd_detalhes/efd_consultar. Pode levar minutos em arquivos grandes.",
          props("caminho", prop("string", "Arquivo .txt da EFD na pasta montada."),
              "pasta_xml", prop("string", "Pasta com os XMLs do período (opcional).")), List.of("caminho")),
      new Ferramenta("efd_detalhes", "Pagina uma seção do resultado de uma sessão. Seções: erros (inconsistências do PVA; filtre por"
          + " codigo = ID da mensagem), verificacoes (malha), achados (cruzamento), resumo (contribuinte, E110, totais por CFOP),"
          + " avisos, mensagens, estatistica. Em verificacoes/achados, sem codigo lista os achados; com codigo pagina as ocorrências.",
          comPag(props("sessao", prop("string", "Id devolvido por efd_abrir."),
              "secao", prop("string", "erros | verificacoes | achados | resumo | avisos | mensagens | estatistica"),
              "codigo", prop("string", "Filtro: ID da mensagem do PVA ou código do achado."))), List.of("sessao", "secao")),
      new Ferramenta("efd_consultar", "SELECT, SHOW TABLES ou DESCRIBE <tabela> no banco MySQL que o PVA montou da EFD da sessão"
          + " (tabelas reg_xxxx com os campos do Guia Prático). Somente leitura.",
          props("sessao", prop("string", "Id devolvido por efd_abrir."), "sql", prop("string", "Um único comando, sem ;"),
              "limite", prop("integer", "Máximo de linhas (padrão 200, máximo 2000).")), List.of("sessao", "sql")),
      new Ferramenta("efd_livro", "Livros oficiais que o PVA gera da escrituração da sessão (os mesmos do menu Relatórios): apuração do"
          + " ICMS, do ICMS-ST, DIFAL, IPI, inventário, CIAP, entradas, saídas, produção e estoque, créditos fiscais. Sem livro lista"
          + " os livros e períodos disponíveis. formato=texto devolve as páginas em texto (paginado); formato=pdf grava o PDF em"
          + " PVA_SAIDA e devolve o caminho.",
          comPag(props("sessao", prop("string", "Id devolvido por efd_abrir."),
              "livro", prop("string", "apuracao_icms | apuracao_st | difal | apuracao_ipi | inventario | ciap | entradas | saidas |"
                  + " producao_estoque | creditos_fiscais"),
              "periodo", prop("integer", "Índice do período na lista de efd_livro sem livro (padrão 0)."),
              "formato", prop("string", "texto (padrão) ou pdf"),
              "detalhar", prop("boolean", "Entradas/saídas: lista documento a documento além do resumo por CST/CFOP (padrão false)."))),
          List.of("sessao")),
      new Ferramenta("efd_editar", "Edita a escrituração da sessão no banco do PVA, como a tela de edição: altera campos, inclui ou"
          + " exclui registros (excluir leva os filhos junto: C100 apaga C170/C190). Os IDs e ID_PAI vêm do efd_consultar. Valores no"
          + " formato do arquivo (1000,00; datas ddmmaaaa). A lista inteira é conferida antes de gravar (registro, ID, pai e campos):"
          + " se uma operação é inválida, nada é gravado. Opcionalmente refaz os registros analíticos (C190, C590, D190...) e a"
          + " apuração (E110, E210...) com o gerador do PVA; o VL_OPR do C190, que o gerador deixa vazio, é completado pelos"
          + " C170. Depois use efd_gerar_arquivo para exportar e revalidar.",
          props("sessao", prop("string", "Id devolvido por efd_abrir."),
              "operacoes", Json.obj("type", "array", "description", "Lista de {acao: alterar|incluir|excluir, registro: \"C170\","
                  + " id: ID do registro (alterar/excluir), pai: ID do registro pai (incluir), campos: {CAMPO: valor}}.",
                  "items", Json.obj("type", "object")),
              "recalcular_analiticos", prop("boolean", "Refaz todos os registros analíticos a partir dos itens (padrão false)."),
              "recalcular_apuracao", prop("boolean", "Refaz os registros de apuração do bloco E (padrão false).")),
          List.of("sessao", "operacoes")),
      new Ferramenta("efd_gerar_arquivo", "Exporta pelo PVA a escrituração da sessão (com as edições) para um TXT em PVA_SAIDA, com"
          + " 0990/9900/9999 recontados, e revalida esse arquivo na mesma sessão: o resumo volta com os erros novos. O TXT sai sem"
          + " assinatura; a entrega à Receita é com o contribuinte.",
          props("sessao", prop("string", "Id devolvido por efd_abrir."),
              "nome", prop("string", "Nome do arquivo em PVA_SAIDA (padrão: nome do original com -pva.txt).")), List.of("sessao")),
      new Ferramenta("efd_fechar", "Fecha a sessão e apaga a escrituração do banco do PVA.",
          props("sessao", prop("string", "Id devolvido por efd_abrir.")), List.of("sessao")),
      new Ferramenta("efd_validar_pasta", "Valida em lote as EFD de uma pasta no PVA (sem abrir sessão): estado, total de erros e"
          + " mensagens mais frequentes por arquivo. Processa até `limite` arquivos por chamada; continue com a_partir_de.",
          props("pasta", prop("string", "Pasta na pasta montada."), "padrao", prop("string", "Glob, padrão *.txt"),
              "a_partir_de", prop("integer", "Índice do primeiro arquivo (padrão 0)."),
              "limite", prop("integer", "Arquivos por chamada (padrão 10, máximo 50).")), List.of("pasta")),
      new Ferramenta("tabela_sped", "Tabelas externas do PVA (as mesmas que a Receita publica): sem nome lista as tabelas; com"
          + " nome filtra por UF, prefixo de código e data de vigência (AAAA-MM-DD). Ex.: AJ_APUR_DED com uf=CE.",
          comPag(props("nome", prop("string", "Nome da tabela, ex.: AJ_APUR_DED, CFOP, COD_SIT."), "uf", prop("string", "UF"),
              "codigo", prop("string", "Prefixo do código"), "data", prop("string", "Vigente nesta data (AAAA-MM-DD)"))), List.of()),
      new Ferramenta("explicar_mensagem", "Texto oficial de uma mensagem do validador do PVA (ex.: MSG_VL_ICMS_ANALIT).",
          props("codigo", prop("string", "ID da mensagem")), List.of("codigo")));

  static List<Map<String, Object>> ferramentas() {
    List<Map<String, Object>> l = new ArrayList<>();
    for (Ferramenta f : FERRAMENTAS) {
      l.add(Json.obj("name", f.nome(), "description", f.descricao(), "inputSchema",
          Json.obj("type", "object", "properties", f.propriedades(), "required", f.obrigatorias())));
    }
    return l;
  }

  static Map<String, Object> chamar(Map<?, ?> p) {
    String nome = String.valueOf(p.get("name"));
    Map<?, ?> a = p.get("arguments") instanceof Map<?, ?> x ? x : Map.of();
    Object r;
    boolean erro = false;
    try {
      r = switch (nome) {
        case "pva_saude" -> saude();
        case "arquivos_listar" -> listar(a);
        case "efd_abrir" -> abrir(a);
        case "efd_detalhes" -> detalhes(a);
        case "efd_consultar" -> consultar(a);
        case "efd_fechar" -> fecharSessao(a);
        case "efd_livro" -> livro(a);
        case "efd_editar" -> editar(a);
        case "efd_gerar_arquivo" -> gerarArquivo(a);
        case "efd_validar_pasta" -> validarPasta(a);
        case "tabela_sped" -> tabela(a);
        case "explicar_mensagem" -> explicar(a);
        default -> throw new IllegalArgumentException("ferramenta desconhecida: " + nome);
      };
    } catch (Throwable e) {
      if (!(e instanceof IllegalArgumentException)) e.printStackTrace();
      r = Json.obj("erro", e instanceof IllegalArgumentException ? e.getMessage() : String.valueOf(e));
      erro = true;
    }
    String texto = Json.of(r);
    if (texto.length() > MAX_CARACTERES) {
      texto = Json.of(Json.obj("erro", "resposta com " + texto.length() + " caracteres; peça menos: por_pagina menor, filtro por"
          + " codigo ou limite menor"));
      erro = true;
    }
    return Json.obj("content", List.of(Json.obj("type", "text", "text", texto)), "isError", erro);
  }

  // ---------------------------------------------------------------- argumentos e caminhos

  static String txt(Map<?, ?> a, String k) {
    Object v = a.get(k);
    return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
  }

  static int num(Map<?, ?> a, String k, int padrao, int max) {
    Object v = a.get(k);
    int n = v instanceof Number x ? x.intValue() : v == null ? padrao : Integer.parseInt(String.valueOf(v).trim());
    return Math.max(0, Math.min(n, max));
  }

  // Aceita o caminho do host (PVA_DADOS_HOST), o do contêiner (/dados/...) ou relativo à pasta montada.
  static Path resolver(String caminho) {
    String c = caminho == null ? "" : caminho.trim();
    if (!HOST.isEmpty() && (c.equals(HOST) || c.startsWith(HOST + "/"))) c = c.substring(HOST.length());
    Path p = Path.of(c);
    if (!p.isAbsolute() || !p.normalize().startsWith(DADOS)) p = DADOS.resolve(c.replaceFirst("^/+", ""));
    p = p.normalize();
    if (!p.startsWith(DADOS)) throw new IllegalArgumentException("caminho fora da pasta montada: " + caminho);
    return p;
  }

  static String exibir(Path p) {
    String rel = DADOS.relativize(p).toString();
    return HOST.isEmpty() ? rel : HOST + (rel.isEmpty() ? "" : "/" + rel);
  }

  static Map<String, Object> pagina(List<?> itens, Map<?, ?> a) {
    int por = Math.max(1, num(a, "por_pagina", 50, 200));
    int pag = Math.max(1, num(a, "pagina", 1, Integer.MAX_VALUE));
    int ini = Math.min((pag - 1) * por, itens.size());
    int fim = Math.min(ini + por, itens.size());
    return Json.obj("pagina", pag, "porPagina", por, "total", itens.size(), "itens", new ArrayList<>(itens.subList(ini, fim)));
  }

  // ---------------------------------------------------------------- implementação

  static Map<String, Object> saude() {
    Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) Json.parse(PvaServer.saude()));
    m.put("pastaDados", Json.obj("contêiner", DADOS.toString(), "host", HOST.isEmpty() ? null : HOST, "montada", Files.isDirectory(DADOS)));
    List<Map<String, Object>> ss = new ArrayList<>();
    long agora = System.currentTimeMillis();
    synchronized (PvaServer.class) {
      for (Sessao s : SESSOES.values()) {
        ss.add(Json.obj("sessao", s.id, "arquivo", s.arquivo, "estado", s.out.get("estado"),
            "abertaHaMin", (agora - s.abertaEm) / 60_000, "ociosaHaMin", (agora - s.usadaEm) / 60_000));
      }
    }
    m.put("sessoes", ss);
    m.put("limiteSessoes", MAX_SESSOES);
    return m;
  }

  static Map<String, Object> listar(Map<?, ?> a) throws IOException {
    Path base = resolver(txt(a, "pasta"));
    if (!Files.isDirectory(base)) throw new IllegalArgumentException("pasta não encontrada: " + exibir(base));
    String padrao = txt(a, "padrao");
    PathMatcher pm = padrao == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + padrao);
    boolean rec = Boolean.TRUE.equals(a.get("recursivo"));
    List<Map<String, Object>> l = new ArrayList<>();
    try (Stream<Path> st = rec ? Files.walk(base, 8) : Files.list(base)) {
      for (Path f : (Iterable<Path>) st.sorted()::iterator) {
        String nome = f.getFileName().toString();
        if (f.equals(base) || nome.startsWith(".") || nome.equals("__pycache__")) continue;
        boolean dir = Files.isDirectory(f);
        if (pm != null && !dir && !pm.matches(f.getFileName())) continue;
        if (pm != null && dir && rec) continue;
        l.add(Json.obj("caminho", exibir(f), "tipo", dir ? "pasta" : "arquivo", "bytes", dir ? null : Files.size(f)));
      }
    }
    Map<String, Object> r = pagina(l, a);
    r.put("pasta", exibir(base));
    return r;
  }

  static Path copiaTemporaria(Path orig) throws IOException {
    if (!Files.isRegularFile(orig)) throw new IllegalArgumentException("arquivo não encontrado: " + exibir(orig));
    if (Files.size(orig) == 0) throw new IllegalArgumentException("arquivo vazio: " + exibir(orig));
    if (Files.size(orig) > PvaServer.LIMITE_BYTES) throw new IllegalArgumentException("arquivo maior que PVA_LIMITE_MB: " + exibir(orig));
    Path t = Files.createTempFile("mcp-", ".txt");
    Files.copy(orig, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return t;
  }

  static Cruzamento.Lote lerXmls(Path pasta) throws Exception {
    if (!Files.isDirectory(pasta)) throw new IllegalArgumentException("pasta de XML não encontrada: " + exibir(pasta));
    Cruzamento.Lote lote = new Cruzamento.Lote();
    var parser = Cruzamento.parser();
    long total = 0;
    try (Stream<Path> st = Files.walk(pasta, 8)) {
      for (Path f : (Iterable<Path>) st.sorted()::iterator) {
        if (!Files.isRegularFile(f) || !f.getFileName().toString().toLowerCase().endsWith(".xml")) continue;
        byte[] b = Files.readAllBytes(f);
        total += b.length;
        if (total > PvaServer.LIMITE_BYTES * 4) throw new IllegalArgumentException("XMLs somam mais que 4 × PVA_LIMITE_MB");
        Cruzamento.ler(lote, pasta.relativize(f).toString(), b, parser);
      }
    }
    return lote;
  }

  @SuppressWarnings("unchecked")
  static PvaServer.Etapa etapa(Cruzamento.Lote lote) {
    return (per, out) -> {
      PvaServer.ANALISE.executar(per, out);
      if (lote != null) {
        Map<String, Object> est = new LinkedHashMap<>();
        List<Map<String, Object>> achados = Cruzamento.cruzar(per, lote, (Map<String, Object>) out.get("resumo"), est);
        out.put("cruzamento", Json.obj("estatistica", est, "achados", achados));
      }
    };
  }

  static Map<String, Object> abrir(Map<?, ?> a) throws Exception {
    Path orig = resolver(txt(a, "caminho"));
    Path tmp = copiaTemporaria(orig);
    String px = txt(a, "pasta_xml");
    Cruzamento.Lote lote = px == null ? null : lerXmls(resolver(px));
    try {
      PvaServer.Etapa etapa = etapa(lote);
      synchronized (PvaServer.class) {
        Map<String, Object> out = PvaServer.processar(tmp, etapa, true);
        String id;
        do {
          id = "s" + Integer.toHexString(RANDOM.nextInt(0x100000, 0x1000000));
        } while (SESSOES.containsKey(id));
        Sessao s = new Sessao(id, exibir(orig), chave(tmp), px, PvaServer.mantida, out);
        PvaServer.mantida = null;
        SESSOES.put(id, s);
        List<String> fechadas = new ArrayList<>();
        while (SESSOES.size() > MAX_SESSOES) {
          Sessao velha = SESSOES.values().iterator().next();
          fechar(velha);
          fechadas.add(velha.id);
        }
        Map<String, Object> r = compacto(s);
        if (!fechadas.isEmpty()) r.put("sessoesFechadasPorLimite", fechadas);
        return r;
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static Sessao sessao(Map<?, ?> a) {
    String id = txt(a, "sessao");
    synchronized (PvaServer.class) {
      Sessao s = SESSOES.get(id);
      if (s == null) throw new IllegalArgumentException("sessão " + id + " não existe (expirou, foi fechada ou substituída);"
          + " abra de novo com efd_abrir");
      s.usadaEm = System.currentTimeMillis();
      return s;
    }
  }

  interface ComBanco<T> {
    T usar(IPersistencia per) throws Exception;
  }

  static <T> T comBanco(Sessao s, ComBanco<T> f) throws Exception {
    if (s.esc == null) throw new IllegalArgumentException("o PVA não integrou este arquivo (erro estrutural): não há banco para"
        + " consultar; veja efd_detalhes secao=erros");
    synchronized (PvaServer.class) {
      if (!SESSOES.containsKey(s.id)) throw new IllegalArgumentException("sessão " + s.id + " foi fechada");
      IPersistencia per = PersistenciaFiscalPVA.getSingleton().abrirPersistencia(s.esc);
      try {
        return f.usar(per);
      } finally {
        per.fecharPersistencia();
      }
    }
  }

  static BigDecimal valor(Object o) {
    return o instanceof BigDecimal b ? b : BigDecimal.ZERO;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> achados(Map<String, Object> out, String secao) {
    Object v = "achados".equals(secao)
        ? (out.get("cruzamento") instanceof Map<?, ?> c ? c.get("achados") : null)
        : out.get("verificacoes");
    return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
  }

  static List<Map<String, Object>> semOcorrencias(List<Map<String, Object>> l, boolean textos) {
    List<Map<String, Object>> r = new ArrayList<>();
    for (Map<String, Object> x : l) {
      Map<String, Object> m = Json.obj("codigo", x.get("codigo"), "nivel", x.get("nivel"), "titulo", x.get("titulo"),
          "quantidade", x.get("quantidade"), "valorTotal", x.get("valorTotal"));
      if (textos) {
        m.put("explicacao", x.get("explicacao"));
        m.put("fundamento", x.get("fundamento"));
      }
      r.add(m);
    }
    return r;
  }

  // Erros agrupados por mensagem: com a escrituração no banco conta todos (o relatório traz só 500).
  @SuppressWarnings("unchecked")
  static Map<String, Object> errosAgrupados(Sessao s) throws Exception {
    List<Map<String, Object>> grupos = new ArrayList<>();
    long total = 0;
    if (s.esc != null && !Boolean.TRUE.equals(s.out.get("valido"))) {
      List<Map<String, String>> l = comBanco(s, per -> {
        String t = PvaServer.tabelaInconsistencias(per);
        return t == null ? List.of() : Verificacoes.linhas(per, "SELECT ID_MENSAGEM, TIPO, COUNT(*) QTD FROM " + t
            + " GROUP BY ID_MENSAGEM, TIPO ORDER BY QTD DESC", 1000);
      });
      for (Map<String, String> r : l) {
        long q = Long.parseLong(r.get("QTD"));
        total += q;
        grupos.add(Json.obj("codigo", r.get("ID_MENSAGEM"), "tipo", r.get("TIPO"), "quantidade", q,
            "descricao", Catalogo.mensagem(r.get("ID_MENSAGEM"))));
      }
    } else if (s.out.get("erros") instanceof List<?> es) {
      Map<String, Map<String, Object>> g = new LinkedHashMap<>();
      for (Object o : es) {
        Map<String, Object> e = (Map<String, Object>) o;
        String k = e.get("codigo") + "|" + e.get("tipo");
        g.computeIfAbsent(k, x -> Json.obj("codigo", e.get("codigo"), "tipo", e.get("tipo"), "quantidade", 0L,
            "descricao", e.get("descricao")));
        g.get(k).put("quantidade", (Long) g.get(k).get("quantidade") + 1);
        total++;
      }
      grupos.addAll(g.values());
      grupos.sort((x, y) -> Long.compare((Long) y.get("quantidade"), (Long) x.get("quantidade")));
    }
    return Json.obj("total", total, "porMensagem", grupos);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> compacto(Sessao s) throws Exception {
    Map<String, Object> o = s.out;
    Map<String, Object> r = Json.obj("sessao", s.id, "arquivo", s.arquivo, "estado", o.get("estado"), "valido", o.get("valido"),
        "ms", o.get("ms"));
    if (o.get("resumo") instanceof Map<?, ?> rs) {
      r.put("contribuinte", rs.get("contribuinte"));
      r.put("periodo", rs.get("periodo"));
      r.put("apuracaoIcms", rs.get("apuracaoIcms"));
      r.put("quantidades", rs.get("quantidades"));
    }
    if (o.get("avisos") instanceof List<?> av && !av.isEmpty()) r.put("avisos", av);
    for (String k : new String[] {"sessoesFechadas", "falha", "falhaEtapa"}) if (o.containsKey(k)) r.put(k, o.get(k));
    Map<String, Object> erros = errosAgrupados(s);
    List<?> grupos = (List<?>) erros.get("porMensagem");
    if (grupos.size() > 15) erros.put("porMensagem", new ArrayList<>(grupos.subList(0, 15)));
    erros.put("mensagensDistintas", grupos.size());
    r.put("erros", erros);
    r.put("verificacoes", semOcorrencias(achados(o, "verificacoes"), false));
    if (o.get("cruzamento") instanceof Map<?, ?> c) {
      Map<String, Object> est = new LinkedHashMap<>((Map<String, Object>) c.get("estatistica"));
      if (est.get("ignorados") instanceof Collection<?> ig) est.put("ignorados", ig.size());
      r.put("cruzamento", Json.obj("estatistica", est, "achados", semOcorrencias(achados(o, "achados"), false)));
    }
    if (o.get("mensagens") instanceof List<?> ms && !ms.isEmpty()) r.put("mensagensDoPva", ms.size());
    if (s.pendentes > 0) r.put("edicoesNaoExportadas", s.pendentes);
    r.put("proximo", "efd_detalhes (erros, verificacoes, achados, resumo) e efd_consultar com sessao=" + s.id + "; efd_fechar no fim");
    return r;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> detalhes(Map<?, ?> a) throws Exception {
    Sessao s = sessao(a);
    String secao = String.valueOf(txt(a, "secao"));
    String codigo = txt(a, "codigo");
    Map<String, Object> r;
    switch (secao) {
      case "erros" -> {
        if (codigo != null && !codigo.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("codigo inválido");
        if (s.esc != null && !Boolean.TRUE.equals(s.out.get("valido"))) {
          int por = Math.max(1, num(a, "por_pagina", 50, 200));
          int pag = Math.max(1, num(a, "pagina", 1, Integer.MAX_VALUE));
          String filtro = codigo == null ? "" : " WHERE ID_MENSAGEM = '" + codigo + "'";
          r = comBanco(s, per -> {
            String t = PvaServer.tabelaInconsistencias(per);
            if (t == null) return pagina(List.of(), a);
            long total = Long.parseLong(Verificacoes.linhas(per, "SELECT COUNT(*) N FROM " + t + filtro, 1).get(0).get("N"));
            List<Map<String, Object>> itens = new ArrayList<>();
            for (Map<String, String> x : Verificacoes.linhas(per, "SELECT TIPO, ID_MENSAGEM, NOME_REGISTRO, ID_CAMPO, NUMERO_LINHA,"
                + " VALOR_CAMPO, VALOR_ESPERADO_CAMPO, CONTEUDO_LINHA FROM " + t + filtro + " ORDER BY NUMERO_LINHA LIMIT " + por
                + " OFFSET " + (long) (pag - 1) * por, por)) {
              itens.add(PvaServer.erro(x.get("TIPO"), x.get("ID_MENSAGEM"), x.get("NOME_REGISTRO"), x.get("ID_CAMPO"),
                  Long.parseLong(x.get("NUMERO_LINHA")), x.get("VALOR_CAMPO"), x.get("VALOR_ESPERADO_CAMPO"), x.get("CONTEUDO_LINHA")));
            }
            return Json.obj("pagina", pag, "porPagina", por, "total", total, "itens", itens);
          });
        } else {
          List<Map<String, Object>> es = new ArrayList<>();
          if (s.out.get("erros") instanceof List<?> l) {
            for (Object o : l) {
              Map<String, Object> e = (Map<String, Object>) o;
              if (codigo == null || codigo.equals(e.get("codigo"))) es.add(e);
            }
          }
          r = pagina(es, a);
        }
      }
      case "verificacoes", "achados" -> {
        List<Map<String, Object>> l = achados(s.out, secao);
        if (codigo == null) {
          r = pagina(semOcorrencias(l, true), a);
        } else {
          Map<String, Object> ach = l.stream().filter(x -> codigo.equals(x.get("codigo"))).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("código " + codigo + " não está em " + secao));
          r = pagina((List<?>) ach.get("ocorrencias"), a);
          r.put("achado", semOcorrencias(List.of(ach), true).get(0));
          if (((Number) ach.get("quantidade")).intValue() > ((List<?>) ach.get("ocorrencias")).size()) {
            r.put("nota", "o servidor guarda as primeiras " + Verificacoes.MAX_OCORRENCIAS + " ocorrências; o total está em achado.quantidade");
          }
        }
      }
      case "resumo" -> r = Json.obj("resumo", s.out.get("resumo"));
      case "avisos" -> r = pagina(s.out.get("avisos") instanceof List<?> l ? l : List.of(), a);
      case "mensagens" -> r = pagina(s.out.get("mensagens") instanceof List<?> l ? l : List.of(), a);
      case "estatistica" -> r = Json.obj("estatistica", s.out.get("cruzamento") instanceof Map<?, ?> c ? c.get("estatistica") : null);
      default -> throw new IllegalArgumentException("secao deve ser erros, verificacoes, achados, resumo, avisos, mensagens ou estatistica");
    }
    r.put("sessao", s.id);
    r.put("secao", secao);
    if (codigo != null) r.put("codigo", codigo);
    return r;
  }

  static Map<String, Object> consultar(Map<?, ?> a) throws Exception {
    Sessao s = sessao(a);
    String sql = String.valueOf(txt(a, "sql"));
    boolean meta = sql.matches("(?is)(SHOW\\s+TABLES|DESCRIBE\\s+\\w+|DESC\\s+\\w+)\\s*");
    if (!meta && !PvaServer.sqlPermitido(sql)) {
      throw new IllegalArgumentException("só um SELECT, SHOW TABLES ou DESCRIBE <tabela>, sem ; nem INTO/OUTFILE/LOAD_FILE");
    }
    int limite = Math.max(1, num(a, "limite", 200, 2000));
    List<Map<String, String>> l = comBanco(s, per -> Verificacoes.linhas(per, sql, limite + 1));
    return Json.obj("sessao", s.id, "truncado", l.size() > limite, "linhas", l.size() > limite ? l.subList(0, limite) : l);
  }

  static final Path SAIDA = Path.of(System.getenv().getOrDefault("PVA_SAIDA_CONTAINER", "/saida"));
  static final String SAIDA_HOST = System.getenv().getOrDefault("PVA_SAIDA_HOST", "").replaceAll("/+$", "");

  static Map<String, Object> livro(Map<?, ?> a) throws Exception {
    Sessao s = sessao(a);
    if (s.esc == null) throw new IllegalArgumentException("o PVA não integrou este arquivo: não há livros");
    String livro = txt(a, "livro");
    synchronized (PvaServer.class) {
      if (!SESSOES.containsKey(s.id)) throw new IllegalArgumentException("sessão " + s.id + " foi fechada");
      if (livro == null) return Json.obj("sessao", s.id, "livros", Livros.disponiveis(s.esc));
      int periodo = num(a, "periodo", 0, 10_000);
      boolean detalhar = Boolean.TRUE.equals(a.get("detalhar"));
      var jp = Livros.gerar(s.esc, livro, periodo, detalhar);
      String formato = txt(a, "formato") == null ? "texto" : txt(a, "formato");
      Map<String, Object> r = Json.obj("sessao", s.id, "livro", livro, "periodo", periodo, "paginasDoLivro", jp.getPages().size());
      if ("pdf".equals(formato)) {
        if (!Files.isDirectory(SAIDA) || !Files.isWritable(SAIDA)) {
          throw new IllegalArgumentException("formato pdf precisa da pasta de saída montada com escrita (PVA_SAIDA)");
        }
        String nome = livro + "-" + String.valueOf(s.chave).replaceAll("[^0-9A-Za-z]+", "_") + "-" + periodo + ".pdf";
        Path destino = SAIDA.resolve(nome);
        Livros.pdf(jp, destino);
        r.put("pdf", SAIDA_HOST.isEmpty() ? destino.toString() : SAIDA_HOST + "/" + nome);
        r.put("bytes", Files.size(destino));
        return r;
      }
      if (!"texto".equals(formato)) throw new IllegalArgumentException("formato deve ser texto ou pdf");
      Map<?, ?> pag = new LinkedHashMap<>(Map.of("pagina", num(a, "pagina", 1, Integer.MAX_VALUE),
          "por_pagina", Math.max(1, num(a, "por_pagina", 5, 20))));
      r.putAll(pagina(Livros.texto(jp), pag));
      r.put("nota", "itens = páginas do livro, cada uma uma lista de linhas; colunas separadas por \" | \"");
      return r;
    }
  }

  static Map<String, Object> editar(Map<?, ?> a) throws Exception {
    Sessao s = sessao(a);
    if (s.esc == null) throw new IllegalArgumentException("o PVA não integrou este arquivo: não há escrituração para editar");
    if (!(a.get("operacoes") instanceof List<?> ops)) throw new IllegalArgumentException("operacoes deve ser uma lista");
    synchronized (PvaServer.class) {
      if (!SESSOES.containsKey(s.id)) throw new IllegalArgumentException("sessão " + s.id + " foi fechada");
      Map<String, Object> r = Edicao.editar(s.esc, ops, Boolean.TRUE.equals(a.get("recalcular_analiticos")),
          Boolean.TRUE.equals(a.get("recalcular_apuracao")));
      s.pendentes += ops.size();
      r.put("sessao", s.id);
      r.put("edicoesNaoExportadas", s.pendentes);
      r.put("proximo", "efd_consultar para conferir; efd_gerar_arquivo para exportar e revalidar");
      return r;
    }
  }

  static Map<String, Object> gerarArquivo(Map<?, ?> a) throws Exception {
    Sessao s = sessao(a);
    if (s.esc == null) throw new IllegalArgumentException("o PVA não integrou este arquivo: não há escrituração para exportar");
    if (!Files.isDirectory(SAIDA) || !Files.isWritable(SAIDA)) {
      throw new IllegalArgumentException("efd_gerar_arquivo precisa da pasta de saída montada com escrita (PVA_SAIDA)");
    }
    String nome = txt(a, "nome");
    if (nome == null) nome = Path.of(s.arquivo).getFileName().toString().replaceAll("(?i)(-pva)?\\.txt$", "") + "-pva.txt";
    if (!nome.matches("[\\w.-]+") || nome.startsWith(".")) throw new IllegalArgumentException("nome inválido: use letras, números, . _ -");
    Path destino = SAIDA.resolve(nome);
    Cruzamento.Lote lote = s.pastaXml == null ? null : lerXmls(resolver(s.pastaXml));
    Path tmp = Files.createTempFile("mcp-", ".txt");
    try {
      synchronized (PvaServer.class) {
        if (!SESSOES.containsKey(s.id)) throw new IllegalArgumentException("sessão " + s.id + " foi fechada");
        Edicao.exportar(s.esc, destino);
        Files.copy(destino, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // A reimportação substitui a escrituração no banco (mesma chave): a sessão segue com o mesmo id.
        SESSOES.remove(s.id);
        int exportadas = s.pendentes;
        try {
          s.out = PvaServer.processar(tmp, etapa(lote), true);
          s.esc = PvaServer.mantida;
          PvaServer.mantida = null;
          s.arquivo = SAIDA_HOST.isEmpty() ? destino.toString() : SAIDA_HOST + "/" + nome;
          s.pendentes = 0;
        } finally {
          SESSOES.put(s.id, s);
        }
        Map<String, Object> r = compacto(s);
        r.put("arquivoGerado", s.arquivo);
        r.put("bytes", Files.size(destino));
        r.put("edicoesExportadas", exportadas);
        return r;
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static Map<String, Object> fecharSessao(Map<?, ?> a) {
    Sessao s = sessao(a);
    fechar(s);
    return Json.obj("sessao", s.id, "fechada", true);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> validarPasta(Map<?, ?> a) throws Exception {
    Path base = resolver(txt(a, "pasta"));
    if (!Files.isDirectory(base)) throw new IllegalArgumentException("pasta não encontrada: " + exibir(base));
    String padrao = txt(a, "padrao") == null ? "*.txt" : txt(a, "padrao");
    PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + padrao);
    List<Path> arqs = new ArrayList<>();
    try (Stream<Path> st = Files.list(base)) {
      st.filter(f -> Files.isRegularFile(f) && pm.matches(f.getFileName())).sorted().forEach(arqs::add);
    }
    int de = num(a, "a_partir_de", 0, Integer.MAX_VALUE);
    int ate = Math.min(arqs.size(), de + Math.max(1, num(a, "limite", 10, 50)));
    List<Map<String, Object>> itens = new ArrayList<>();
    for (Path f : arqs.subList(Math.min(de, arqs.size()), ate)) {
      Map<String, Object> item = Json.obj("arquivo", exibir(f));
      Path tmp = null;
      try {
        tmp = copiaTemporaria(f);
        Map<String, Object> o = PvaServer.processar(tmp, null);
        item.put("estado", o.get("estado"));
        item.put("valido", o.get("valido"));
        Map<String, Long> cont = new LinkedHashMap<>();
        if (o.get("erros") instanceof List<?> es) {
          for (Object e : es) cont.merge(String.valueOf(((Map<String, Object>) e).get("codigo")), 1L, Long::sum);
        }
        item.put("erros", es(o));
        List<Map<String, Object>> top = new ArrayList<>();
        cont.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(3)
            .forEach(e -> top.add(Json.obj("codigo", e.getKey(), "quantidade", e.getValue())));
        item.put("principais", top);
        if (o.get("avisos") instanceof List<?> av && !av.isEmpty()) {
          List<Object> cods = new ArrayList<>();
          for (Object x : av) cods.add(((Map<String, Object>) x).get("codigo"));
          item.put("avisos", cods);
        }
        if (o.containsKey("sessoesFechadas")) item.put("sessoesFechadas", o.get("sessoesFechadas"));
        if (o.containsKey("falha")) item.put("falha", o.get("falha"));
        item.put("ms", o.get("ms"));
      } catch (IllegalArgumentException e) {
        item.put("falha", e.getMessage());
      } finally {
        if (tmp != null) Files.deleteIfExists(tmp);
      }
      itens.add(item);
    }
    return Json.obj("pasta", exibir(base), "arquivos", arqs.size(), "processados", itens.size(), "de", de,
        "proximo", ate < arqs.size() ? ate : null, "itens", itens);
  }

  static int es(Map<String, Object> o) {
    return o.get("erros") instanceof List<?> l ? l.size() : 0;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> tabela(Map<?, ?> a) throws Exception {
    String nome = txt(a, "nome");
    if (nome == null) {
      java.util.Set<Object> nomes = new java.util.TreeSet<>();
      for (Map<String, Object> t : Catalogo.listarTabelas()) nomes.add(t.get("tabela"));
      return Json.obj("total", nomes.size(), "tabelas", nomes);
    }
    Map<String, Object> t = Catalogo.consultarTabela(nome, txt(a, "uf"), txt(a, "codigo"), txt(a, "data"));
    if (((List<?>) t.get("pacotes")).isEmpty()) throw new IllegalArgumentException("tabela " + nome + " não encontrada");
    Map<String, Object> r = pagina((List<?>) t.get("linhas"), a);
    for (Map.Entry<String, Object> e : t.entrySet()) if (!e.getKey().equals("linhas")) r.put(e.getKey(), e.getValue());
    return r;
  }

  static Map<String, Object> explicar(Map<?, ?> a) {
    String cod = txt(a, "codigo");
    String m = Catalogo.mensagem(cod);
    if (m == null) throw new IllegalArgumentException("mensagem " + cod + " não existe no catálogo do PVA");
    return Json.obj("codigo", cod, "descricao", m);
  }
}
