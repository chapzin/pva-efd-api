import br.gov.serpro.comum.progresso.IMostrarProgresso;
import br.gov.serpro.sped.fiscalpva.dominio.entidades.EscrituracaoFiscal;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.escrituracaofiscal.ControleEscrituracaoFiscal;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.fabrica.FabricaControle;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.importacao.escrituracao.IControleImportacaoExportacaoEscrituracao;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.interacaousuario.IInteracaoImportacaoEscrituracao;
import br.gov.serpro.atualizartabela.fachada.SistemaTabelas;
import br.gov.serpro.atualizartabela.fronteira.atualizartabela.IMonitorTransferenciaTabela;
import br.gov.serpro.atualizartabela.fronteira.atualizartabela.ISeletorTabelasAtualizar;
import br.gov.serpro.atualizartabela.xmlbind.sistema.Pacote;
import br.gov.serpro.atualizartabela.xmlbind.sistema.Tabela;
import br.gov.serpro.sped.fiscalpva.nucleo.init.InicializacaoSistemaSPEDFiscalPVA;
import br.gov.serpro.sped.fiscalpva.nucleo.init.InicializacaoTabelasExternas;
import br.gov.serpro.sped.fiscalpva.persistencia.PersistenciaFiscalPVA;
import br.gov.serpro.sped.fiscal.nucleo.leitorescrituracao.LeitorArquivoHierarquicoInputStreamFiscal;
import br.gov.serpro.vepxml.nucleo.leitorescrituracao.ILeitorEscrituracao;
import br.gov.serpro.sped.fiscalpva.validador.fachada.FachadaValidadorSPEDFiscal;
import br.gov.serpro.sped.fiscalpva.validador.resultado.ResultadoValidacao;
import br.gov.serpro.vepxml.nucleo.sessao.SessaoVep;
import br.gov.serpro.vepxml.persistencia.IPersistencia;
import br.gov.serpro.vepxml.validador.resultado.Inconsistencia;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.time.LocalDate;
import java.net.URLDecoder;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Expõe o núcleo do PVA EFD ICMS/IPI por HTTP: POST /validar com o TXT no corpo
// devolve o veredito e as inconsistências oficiais. O PVA é singleton (um MySQL
// embutido, uma escrituração por vez), então as validações são serializadas.
// Todas as classes br.gov.serpro.* vêm do fiscalpva.jar do PVA oficial instalado
// na imagem; nada do PVA é redistribuído por este projeto.
public class PvaServer {
  static final long LIMITE_BYTES = Long.parseLong(System.getenv().getOrDefault("PVA_LIMITE_MB", "512")) * 1024 * 1024;
  static EscrituracaoFiscal capturada;
  static final List<String> mensagens = new ArrayList<>();
  static IControleImportacaoExportacaoEscrituracao controle;
  static IInteracaoImportacaoEscrituracao ui;
  static IMostrarProgresso progresso;
  static String versao = "";
  static final Path DIR_TABELAS = Catalogo.DIR_TABELAS;
  static volatile String ultimaAtualizacao = "";

  // Responde aos diálogos do PVA como o operador responderia na validação:
  // confirma, não cancela e guarda a escrituração que os relatórios recebem.
  static Object resposta(Method m, Object[] a) {
    String n = m.getName();
    if (a != null) {
      for (Object o : a) {
        if (o instanceof EscrituracaoFiscal e) capturada = e;
      }
    }
    if (n.startsWith("exibirMensagem") && a != null && a.length > 0) mensagens.add(String.valueOf(a[0]));
    Class<?> r = m.getReturnType();
    if (r == boolean.class) return !n.equals("foiCanceladoOuFechado");
    if (r.isEnum()) {
      for (Object c : r.getEnumConstants()) {
        String s = c.toString().toUpperCase();
        if (s.contains("SIM") || s.contains("YES") || s.equals("OK")) return c;
      }
      return r.getEnumConstants()[0];
    }
    if (r == int.class) return 0;
    // "atualizar tabelas antes de validar?": opção 0 abre o modal de download e
    // trava; as tabelas já são atualizadas no boot, então responde "não" (1).
    if (r.getName().endsWith("ExibirMensagem$SelecaoOptionPane")) {
      try {
        Object s = r.getDeclaredConstructor(r.getEnclosingClass()).newInstance((Object) null);
        r.getMethod("setOpcao", int.class).invoke(s, 1);
        return s;
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> c) {
    return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[] {c}, (p, m, a) -> {
      if (m.getDeclaringClass() == Object.class) {
        return switch (m.getName()) {
          case "toString" -> c.getSimpleName();
          case "hashCode" -> System.identityHashCode(p);
          default -> p == a[0];
        };
      }
      return resposta(m, a);
    });
  }

  static String js(Object o) {
    return o == null ? "null" : Json.of(o.toString());
  }

  interface Etapa {
    void executar(IPersistencia per, Map<String, Object> out) throws Exception;
  }

  static Map<String, Object> erro(String tipo, String codigo, String registro, String campo, long linha, String valor,
      String esperado, String conteudo) {
    return Json.obj("tipo", tipo, "codigo", codigo, "descricao", umaLinha(Catalogo.mensagem(codigo)), "registro", registro,
        "campo", campo, "linha", linha, "valor", valor, "esperado", esperado, "conteudo", umaLinha(conteudo));
  }

  static String umaLinha(String s) {
    return s == null ? null : s.replaceAll("[\\r\\n\\t]+", " ").strip();
  }

  static String tabelaInconsistencias(IPersistencia per) throws Exception {
    String tabela = null;
    try (ResultSet t = per.executarComandoSql("SHOW TABLES")) {
      while (t.next()) {
        String n = t.getString(1);
        if (n.toLowerCase().contains("inconsist")) tabela = n;
      }
    }
    return tabela;
  }

  static List<Map<String, Object>> inconsistencias(IPersistencia per) throws Exception {
    List<Map<String, Object>> out = new ArrayList<>();
    String tabela = tabelaInconsistencias(per);
    if (tabela == null) return out;
    try (ResultSet r = per.executarComandoSql("SELECT TIPO, ID_MENSAGEM, NOME_REGISTRO, ID_CAMPO, NUMERO_LINHA,"
        + " VALOR_CAMPO, VALOR_ESPERADO_CAMPO, CONTEUDO_LINHA FROM " + tabela + " ORDER BY NUMERO_LINHA LIMIT 500")) {
      while (r.next()) {
        out.add(erro(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5), r.getString(6), r.getString(7),
            r.getString(8)));
      }
    }
    return out;
  }

  // Arquivo não integrado: o PVA descarta o mapa de erros da importação e só
  // abre um relatório de tela. Reimporta pela fachada para devolver esses erros.
  static List<Map<String, Object>> errosDeImportacao(Path arq, EscrituracaoFiscal esc) throws Exception {
    ResultadoValidacao r;
    try (InputStream in = Files.newInputStream(arq)) {
      ILeitorEscrituracao l = new LeitorArquivoHierarquicoInputStreamFiscal(in, esc.getDescritor());
      r = FachadaValidadorSPEDFiscal.importarEscrituracao(l, esc, progresso, 1000, 1000, new SessaoVep());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    if (r.getMapErros() != null) {
      for (List<Inconsistencia> is : r.getMapErros().values()) {
        for (Inconsistencia i : is) {
          out.add(erro(i.getTipo() == null ? null : i.getTipo().name().substring(0, 1), i.getIdentificador(), i.getNomeRegistro(),
              i.getIdentificadorCampo(), i.getLinhaArquivo() == null ? 0 : i.getLinhaArquivo(), i.getValorCampo(),
              i.getValorEsperado(), i.getValorRegistro()));
        }
      }
    }
    return out;
  }

  // Arquivo assinado abre só para visualização no PVA (estado nulo, sem validação).
  // A assinatura fica depois da linha |9999|; cortar ali não muda a escrituração.
  static boolean removerAssinatura(Path arq) throws java.io.IOException {
    byte[] b = Files.readAllBytes(arq);
    byte[] marca = "|9999|".getBytes(StandardCharsets.ISO_8859_1);
    for (int i = 0; i + marca.length <= b.length; i++) {
      if ((i == 0 || b[i - 1] == '\n') && java.util.Arrays.equals(b, i, i + marca.length, marca, 0, marca.length)) {
        int fim = i;
        while (fim < b.length && b[fim] != '\n') fim++;
        if (fim >= b.length - 1) return false;
        Files.write(arq, java.util.Arrays.copyOf(b, fim + 1));
        return true;
      }
    }
    return false;
  }

  // Conferência antes do PVA: o leiaute errado para o período é o motivo mais comum
  // de arquivo recusado sem explicação clara.
  // A EFD é ISO-8859-1. Arquivo gravado em UTF-8 passa no PVA, mas "Ç" vira "Ã‡" na escrituração e nos livros.
  static Map<String, Object> utf8(Path arq) {
    long linha = 1, linhas = 0, primeira = 0;
    boolean nestaLinha = false;
    try (var in = new java.io.BufferedInputStream(Files.newInputStream(arq), 1 << 16)) {
      int ant = -1, b;
      while ((b = in.read()) != -1) {
        if (b == '\n') {
          linha++;
          nestaLinha = false;
        } else if ((ant == 0xC2 || ant == 0xC3) && b >= 0x80 && b <= 0xBF && !nestaLinha) {
          nestaLinha = true;
          linhas++;
          if (primeira == 0) primeira = linha;
        }
        ant = b;
      }
    } catch (java.io.IOException e) {
      return null;
    }
    if (linhas == 0) return null;
    return Json.obj("codigo", "CODIFICACAO_UTF8", "mensagem", "O arquivo parece gravado em UTF-8 (" + linhas + " linhas com acento"
        + " em dois bytes, a primeira é a " + primeira + "). A EFD é ISO-8859-1: o PVA aceita, mas grava os acentos quebrados"
        + " (\"Ç\" vira \"Ã‡\") na escrituração e nos livros.", "linhas", linhas, "primeiraLinha", primeira);
  }

  static List<Map<String, Object>> avisos(Path arq) {
    List<Map<String, Object>> out = new ArrayList<>();
    Map<String, Object> u = utf8(arq);
    if (u != null) out.add(u);
    try (var r = Files.newBufferedReader(arq, StandardCharsets.ISO_8859_1)) {
      String l = r.readLine();
      if (l == null || !l.startsWith("|0000|")) {
        out.add(Json.obj("codigo", "SEM_REGISTRO_0000", "mensagem", "A primeira linha do arquivo não é o registro 0000."));
        return out;
      }
      String[] c = l.split("\\|", -1);
      String codVer = c.length > 2 ? c[2] : "";
      LocalDate ini = Verificacoes.data(c.length > 4 ? c[4] : null);
      if (ini == null) return out;
      Map<String, Object> certo = Catalogo.consultarTabela("VERSOES_LEIAUTE", null, null, ini.toString());
      List<?> linhas = (List<?>) certo.get("linhas");
      if (linhas.isEmpty()) return out;
      String esperado = String.valueOf(((Map<?, ?>) linhas.get(0)).get("COD_LEI"));
      if (!esperado.equals(codVer)) {
        out.add(Json.obj("codigo", "LEIAUTE_DO_PERIODO", "mensagem", "COD_VER " + codVer + " no 0000, mas o leiaute vigente em " + ini
            + " é o " + esperado + ". O PVA recusa o arquivo na importação.", "informado", codVer, "esperado", esperado));
      }
    } catch (Exception e) {
      System.err.println("avisos: " + e);
    }
    return out;
  }

  // Importa, roda a etapa extra com o banco da escrituração aberto e apaga tudo.
  // O PVA é singleton: uma escrituração por vez.
  static synchronized Map<String, Object> processar(Path arq, Etapa etapa) {
    return processar(arq, etapa, false);
  }

  // Com manter=true a escrituração integrada fica no banco (em `mantida`) para as sessões do MCP.
  static EscrituracaoFiscal mantida;

  static synchronized Map<String, Object> processar(Path arq, Etapa etapa, boolean manter) {
    capturada = null;
    mantida = null;
    // Importar a mesma escrituração (CNPJ e período) substitui a que está no banco.
    List<String> fechadas = Mcp.liberarMesmaEscrituracao(arq);
    mensagens.clear();
    long t = System.currentTimeMillis();
    Map<String, Object> out = Json.obj("versaoPva", versao);
    List<Map<String, Object>> av = avisos(arq);
    boolean assinado = false;
    try {
      assinado = removerAssinatura(arq);
    } catch (java.io.IOException e) {
      System.err.println("assinatura: " + e);
    }
    if (assinado) av.add(Json.obj("codigo", "ASSINATURA_REMOVIDA", "mensagem", "O arquivo veio assinado (ReceitanetBX ou"
        + " PVA). A assinatura depois do |9999| foi removida para validar; o conteúdo da escrituração é o mesmo."));
    out.put("avisos", av);
    if (!fechadas.isEmpty()) out.put("sessoesFechadas", fechadas);
    try {
      controle.importarEscrituracao(arq.toString(), ui, progresso, false, 1000, 1000);
      String estado = capturada == null ? null : String.valueOf(capturada.getEstado());
      boolean ok = "VALIDADA".equals(estado) || "GERADA_PARA_ENTREGA".equals(estado);
      out.put("estado", capturada == null || capturada.getEstado() == null ? null : estado);
      out.put("valido", ok);
      out.put("erros", List.of());
      if (capturada != null && capturada.getEstado() == null) {
        try {
          out.put("erros", errosDeImportacao(arq, capturada));
        } catch (Throwable e) {
          out.put("falha", "erros de importação indisponíveis: " + e);
        }
      } else if (capturada != null) {
        IPersistencia per = PersistenciaFiscalPVA.getSingleton().abrirPersistencia(capturada);
        try {
          if (!ok) {
            try {
              out.put("erros", inconsistencias(per));
            } catch (Throwable e) {
              out.put("falha", "relatório de inconsistências indisponível: " + e);
            }
          }
          if (etapa != null) {
            try {
              etapa.executar(per, out);
            } catch (Throwable e) {
              out.put("falhaEtapa", String.valueOf(e));
            }
          }
        } finally {
          per.fecharPersistencia();
        }
      }
    } catch (Throwable e) {
      out.put("valido", false);
      out.put("falha", String.valueOf(e));
    } finally {
      if (manter && capturada != null && capturada.getEstado() != null) {
        mantida = capturada;
      } else if (capturada != null) {
        try {
          ControleEscrituracaoFiscal.getSingleton().apagarEscrituracaoBanco(capturada);
        } catch (Throwable e) {
          System.err.println("apagar escrituracao: " + e);
        }
      }
    }
    out.put("mensagens", new ArrayList<>(mensagens));
    out.put("ms", System.currentTimeMillis() - t);
    return out;
  }

  static final Etapa ANALISE = (per, out) -> {
    Map<String, Object> resumo = Verificacoes.resumo(per);
    out.put("resumo", resumo);
    out.put("verificacoes", Verificacoes.executar(per, resumo));
  };

  static boolean sqlPermitido(String sql) {
    return sql.regionMatches(true, 0, "SELECT", 0, 6) && !SQL_PROIBIDO.matcher(sql).find();
  }

  static final Pattern SQL_PROIBIDO = Pattern.compile(";|\\b(INTO|OUTFILE|DUMPFILE|LOAD_FILE|SLEEP|BENCHMARK|GET_LOCK)\\b",
      Pattern.CASE_INSENSITIVE);

  static Etapa consulta(String sql, int limite) {
    return (per, out) -> {
      List<Map<String, String>> l = Verificacoes.linhas(per, sql, limite + 1);
      out.put("truncado", l.size() > limite);
      out.put("linhas", l.size() > limite ? l.subList(0, limite) : l);
    };
  }

  // Um .txt (a EFD) e qualquer quantidade de XML de NF-e/NFC-e/CT-e e eventos.
  static Path desempacotar(Path zip, Cruzamento.Lote lote) throws Exception {
    Path txt = null;
    long total = 0;
    var p = Cruzamento.parser();
    try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.ISO_8859_1)) {
      for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
        if (e.isDirectory()) continue;
        String nome = e.getName().toLowerCase();
        if (nome.contains("__macosx/")) continue;
        byte[] b = z.readNBytes((int) Math.min(LIMITE_BYTES, Integer.MAX_VALUE - 8) + 1);
        total += b.length;
        if (b.length > LIMITE_BYTES || total > LIMITE_BYTES * 4) throw new IllegalArgumentException("ZIP maior que o limite");
        if (nome.endsWith(".txt")) {
          if (txt != null) throw new IllegalArgumentException("o ZIP deve ter um único .txt (a EFD)");
          txt = Files.createTempFile("efd-", ".txt");
          Files.write(txt, b);
        } else if (nome.endsWith(".xml")) {
          Cruzamento.ler(lote, e.getName(), b, p);
        }
      }
    }
    if (txt == null) throw new IllegalArgumentException("o ZIP não tem o arquivo .txt da EFD");
    return txt;
  }

  // Arquivos "<pacote>$<tabela>$<versao>$<id>": o nome já carrega a versão.
  static TreeSet<String> arquivosTabelas() {
    TreeSet<String> out = new TreeSet<>();
    try (var ds = Files.newDirectoryStream(DIR_TABELAS)) {
      for (Path f : ds) {
        if (Files.isRegularFile(f)) out.add(f.getFileName().toString());
      }
    } catch (java.io.IOException e) {
      System.err.println("listar tabelas: " + e);
    }
    return out;
  }

  // Mesmo lock das validações: nunca troca tabela no meio de uma validação.
  // O PVA escolhe as tabelas num diálogo modal; aqui o seletor marca todas as
  // que o WS da Receita publicou com versão mais nova que a local.
  @SuppressWarnings("unchecked")
  static synchronized String atualizarTabelas() {
    long t = System.currentTimeMillis();
    List<Tabela> escolhidas = new ArrayList<>();
    List<String> baixadas = new ArrayList<>();
    List<String> falhas = new ArrayList<>();
    ISeletorTabelasAtualizar seletor = (ISeletorTabelasAtualizar) Proxy.newProxyInstance(
        ISeletorTabelasAtualizar.class.getClassLoader(), new Class<?>[] {ISeletorTabelasAtualizar.class}, (p, m, a) -> {
          switch (m.getName()) {
            case "setPacotesComTabelasAtualizar" -> {
              for (Pacote pc : (List<Pacote>) a[0]) {
                for (Tabela tb : pc.getTabelas()) {
                  tb.setSelecionada(true);
                  escolhidas.add(tb);
                }
              }
            }
            case "getTabelasSelecionadasParaAtualizar" -> {
              return escolhidas;
            }
            case "hashCode" -> {
              return System.identityHashCode(p);
            }
            case "equals" -> {
              return p == a[0];
            }
            case "toString" -> {
              return "seletor";
            }
            default -> { }
          }
          return null;
        });
    List<Tabela>[] transferir = new List[] {new ArrayList<Tabela>()};
    IMonitorTransferenciaTabela monitor = (IMonitorTransferenciaTabela) Proxy.newProxyInstance(
        IMonitorTransferenciaTabela.class.getClassLoader(), new Class<?>[] {IMonitorTransferenciaTabela.class}, (p, m, a) -> {
          switch (m.getName()) {
            case "setTabelasTransferir" -> transferir[0] = (List<Tabela>) a[0];
            case "getTabelasTransferir" -> {
              return transferir[0];
            }
            case "setTransferenciaConcluidaComSucesso" -> baixadas.add(((Tabela) a[0]).getIdentificadorTabela());
            case "setErroTransferenciaTabela" -> falhas.add(((Tabela) a[0]).getIdentificadorTabela() + ": " + a[1]);
            default -> {
              return m.getDeclaringClass() == Object.class
                  ? (m.getName().equals("equals") ? p == a[0] : m.getName().equals("hashCode") ? System.identityHashCode(p) : "monitor")
                  : resposta(m, a);
            }
          }
          return null;
        });
    StringBuilder out = new StringBuilder("{");
    try {
      SistemaTabelas.atualizarTabelas(seletor, monitor);
      if (!baixadas.isEmpty()) InicializacaoTabelasExternas.getSingleton().carregarTabelas();
      ultimaAtualizacao = java.time.Instant.now().toString();
      out.append("\"ok\":").append(falhas.isEmpty()).append(",\"tabelas\":").append(arquivosTabelas().size());
      out.append(",\"baixadas\":").append(lista(baixadas)).append(",\"falhas\":").append(lista(falhas));
    } catch (Throwable e) {
      out.append("\"ok\":false,\"falha\":").append(js(e));
    }
    String r = out.append(",\"ms\":").append(System.currentTimeMillis() - t).append('}').toString();
    System.err.println("atualizar tabelas: " + r);
    return r;
  }

  static String lista(List<String> l) {
    return Json.of(l);
  }

  // O Java responde HTTP mesmo com o Xvfb morto, mas toda validação falharia
  // com AWTError; por isso a saúde conecta no socket do display de verdade.
  static boolean displayVivo() {
    String d = System.getenv().getOrDefault("DISPLAY", ":99");
    String n = d.substring(d.indexOf(':') + 1).split("\\.")[0];
    try (var ch = java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of("/tmp/.X11-unix/X" + n))) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static String saude() {
    boolean ok = displayVivo();
    return "{\"ok\":" + ok + ",\"display\":" + ok + ",\"versaoPva\":" + js(versao) + ",\"tabelas\":" + arquivosTabelas().size()
        + ",\"tabelasAtualizadasEm\":" + js(ultimaAtualizacao.isEmpty() ? null : ultimaAtualizacao) + "}";
  }

  static void responder(HttpExchange ex, int status, String corpo) throws java.io.IOException {
    byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  static final java.util.Map<java.awt.Window, Long> abertos = new java.util.WeakHashMap<>();

  // O diálogo modal de progresso da assinatura às vezes abre depois que a
  // tarefa acabou e nunca fecha, prendendo a validação (e o lock) para sempre.
  // A checagem só lê o arquivo; 30 s aberto é travamento.
  static void fecharProgressoPreso() {
    long agora = System.currentTimeMillis();
    for (java.awt.Window w : java.awt.Window.getWindows()) {
      if (!w.isVisible() || !w.getClass().getName().endsWith("DialogoProgressoAssinatura")) {
        abertos.remove(w);
        continue;
      }
      if (agora - abertos.computeIfAbsent(w, k -> agora) > 30_000) {
        System.err.println("fechando diálogo de progresso preso: " + w.getClass().getName());
        abertos.remove(w);
        javax.swing.SwingUtilities.invokeLater(w::dispose);
      }
    }
  }

  interface Tratador {
    String tratar(Path arq) throws Exception;
  }

  static Map<String, String> parametros(HttpExchange ex) {
    Map<String, String> m = new java.util.HashMap<>();
    String q = ex.getRequestURI().getRawQuery();
    if (q == null) return m;
    for (String par : q.split("&")) {
      int i = par.indexOf('=');
      if (i > 0) m.put(URLDecoder.decode(par.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
    }
    return m;
  }

  static void comArquivo(HttpExchange ex, Tratador t) throws java.io.IOException {
    if (!"POST".equals(ex.getRequestMethod())) {
      responder(ex, 405, "{\"erro\":\"use POST com o arquivo no corpo\"}");
      return;
    }
    Path arq = Files.createTempFile("pva-", ".bin");
    try (InputStream in = ex.getRequestBody()) {
      long n = Files.copy(in, arq, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      if (n == 0) {
        responder(ex, 400, "{\"erro\":\"arquivo vazio\"}");
        return;
      }
      if (n > LIMITE_BYTES) {
        responder(ex, 413, "{\"erro\":\"arquivo maior que o limite (PVA_LIMITE_MB)\"}");
        return;
      }
      String r = t.tratar(arq);
      responder(ex, r.startsWith("{\"erro\"") ? 400 : 200, r);
    } catch (Exception e) {
      responder(ex, 500, Json.of(Json.obj("erro", String.valueOf(e))));
    } finally {
      Files.deleteIfExists(arq);
    }
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Catalogo.carregar();
    long t0 = System.currentTimeMillis();
    InicializacaoSistemaSPEDFiscalPVA.getSingleton().iniciarSPEDFiscalPVA();
    ui = proxy(IInteracaoImportacaoEscrituracao.class);
    progresso = proxy(IMostrarProgresso.class);
    controle = FabricaControle.getSingleton().getServico(IControleImportacaoExportacaoEscrituracao.class);
    Package p = Class.forName("br.gov.serpro.sped.fiscalpva.fronteira.ppgd.PgdApp").getPackage();
    versao = p != null && p.getImplementationVersion() != null ? p.getImplementationVersion() : "";
    System.err.println("PVA " + versao + " pronto em " + (System.currentTimeMillis() - t0) + "ms");

    int porta = Integer.parseInt(System.getenv().getOrDefault("PVA_PORTA", "8095"));
    HttpServer srv = HttpServer.create(new InetSocketAddress(porta), 16);
    srv.createContext("/saude", ex -> {
      String r = saude();
      responder(ex, r.startsWith("{\"ok\":true") ? 200 : 503, r);
    });
    srv.createContext("/tabelas/atualizar", ex -> {
      if (!"POST".equals(ex.getRequestMethod())) {
        responder(ex, 405, "{\"erro\":\"use POST\"}");
        return;
      }
      String r = atualizarTabelas();
      responder(ex, r.contains("\"ok\":true") ? 200 : 502, r);
    });
    srv.createContext("/validar", ex -> comArquivo(ex, arq -> Json.of(processar(arq, null))));
    srv.createContext("/analisar", ex -> comArquivo(ex, arq -> Json.of(processar(arq, ANALISE))));
    srv.createContext("/consultar", ex -> {
      String sql = parametros(ex).getOrDefault("sql", "").trim();
      if (!sqlPermitido(sql)) {
        responder(ex, 400, "{\"erro\":\"informe ?sql= com um único SELECT (sem ;, INTO, OUTFILE, LOAD_FILE)\"}");
        return;
      }
      int limite = Math.min(Integer.parseInt(parametros(ex).getOrDefault("limite", "1000")), 10000);
      comArquivo(ex, arq -> Json.of(processar(arq, consulta(sql, limite))));
    });
    srv.createContext("/cruzar", ex -> comArquivo(ex, zip -> {
      Cruzamento.Lote lote = new Cruzamento.Lote();
      Path txt;
      try {
        txt = desempacotar(zip, lote);
      } catch (Exception e) {
        return Json.of(Json.obj("erro", "ZIP inválido: " + e.getMessage()));
      }
      try {
        return Json.of(processar(txt, (per, out) -> {
          ANALISE.executar(per, out);
          Map<String, Object> est = new java.util.LinkedHashMap<>();
          List<Map<String, Object>> achados = Cruzamento.cruzar(per, lote, (Map<String, Object>) out.get("resumo"), est);
          out.put("cruzamento", Json.obj("estatistica", est, "achados", achados));
        }));
      } finally {
        Files.deleteIfExists(txt);
      }
    }));
    srv.createContext("/mensagens", ex -> {
      String cod = ex.getRequestURI().getPath().replaceFirst("^/mensagens/?", "");
      if (cod.isEmpty()) {
        responder(ex, 200, Json.of(Catalogo.mensagens()));
      } else {
        String m = Catalogo.mensagem(cod);
        responder(ex, m == null ? 404 : 200, Json.of(Json.obj("codigo", cod, "descricao", m)));
      }
    });
    srv.createContext("/tabelas", ex -> {
      String nome = ex.getRequestURI().getPath().replaceFirst("^/tabelas/?", "");
      try {
        if (nome.isEmpty()) {
          responder(ex, 200, Json.of(Catalogo.listarTabelas()));
        } else {
          Map<String, String> q = parametros(ex);
          Map<String, Object> r = Catalogo.consultarTabela(nome, q.get("uf"), q.get("codigo"), q.get("data"));
          responder(ex, ((List<?>) r.get("pacotes")).isEmpty() ? 404 : 200, Json.of(r));
        }
      } catch (Exception e) {
        responder(ex, 400, Json.of(Json.obj("erro", String.valueOf(e))));
      }
    });
    srv.createContext("/mcp", Mcp::tratar);
    Mcp.iniciar();
    srv.setExecutor(Executors.newFixedThreadPool(4));
    srv.start();
    Executors.newSingleThreadScheduledExecutor()
        .scheduleWithFixedDelay(PvaServer::fecharProgressoPreso, 5, 5, TimeUnit.SECONDS);

    // As tabelas do instalador congelam na data do build; sem isso o gate
    // aprovaria código de ajuste que a Receita já retirou (ou recusaria um novo).
    long horas = Long.parseLong(System.getenv().getOrDefault("PVA_ATUALIZAR_TABELAS_HORAS", "24"));
    if (horas > 0) {
      Executors.newSingleThreadScheduledExecutor()
          .scheduleWithFixedDelay(PvaServer::atualizarTabelas, 0, horas, TimeUnit.HOURS);
    }
  }
}
