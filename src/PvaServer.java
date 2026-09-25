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
  static final Path DIR_TABELAS = Path.of("recursos/TabelasExternas");
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
    if (o == null) return "null";
    StringBuilder b = new StringBuilder("\"");
    for (char ch : o.toString().toCharArray()) {
      switch (ch) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n', '\r', '\t' -> b.append(' ');
        default -> {
          if (ch < 0x20) b.append(' ');
          else b.append(ch);
        }
      }
    }
    return b.append('"').toString();
  }

  static String inconsistencias(EscrituracaoFiscal esc) throws Exception {
    IPersistencia per = PersistenciaFiscalPVA.getSingleton().abrirPersistencia(esc);
    StringBuilder b = new StringBuilder("[");
    try {
      String tabela = null;
      try (ResultSet t = per.executarComandoSql("SHOW TABLES")) {
        while (t.next()) {
          String n = t.getString(1);
          if (n.toLowerCase().contains("inconsist")) tabela = n;
        }
      }
      if (tabela == null) return "[]";
      try (ResultSet r = per.executarComandoSql("SELECT TIPO, ID_MENSAGEM, NOME_REGISTRO, ID_CAMPO, NUMERO_LINHA,"
          + " VALOR_CAMPO, VALOR_ESPERADO_CAMPO, CONTEUDO_LINHA FROM " + tabela + " ORDER BY NUMERO_LINHA LIMIT 500")) {
        int n = 0;
        while (r.next()) {
          if (n++ > 0) b.append(',');
          b.append("{\"tipo\":").append(js(r.getString(1)))
              .append(",\"codigo\":").append(js(r.getString(2)))
              .append(",\"registro\":").append(js(r.getString(3)))
              .append(",\"campo\":").append(js(r.getString(4)))
              .append(",\"linha\":").append(r.getLong(5))
              .append(",\"valor\":").append(js(r.getString(6)))
              .append(",\"esperado\":").append(js(r.getString(7)))
              .append(",\"conteudo\":").append(js(r.getString(8)))
              .append('}');
        }
      }
    } finally {
      per.fecharPersistencia();
    }
    return b.append(']').toString();
  }

  // Arquivo não integrado: o PVA descarta o mapa de erros da importação e só
  // abre um relatório de tela. Reimporta pela fachada para devolver esses erros.
  static String errosDeImportacao(Path arq, EscrituracaoFiscal esc) throws Exception {
    ResultadoValidacao r;
    try (InputStream in = Files.newInputStream(arq)) {
      ILeitorEscrituracao l = new LeitorArquivoHierarquicoInputStreamFiscal(in, esc.getDescritor());
      r = FachadaValidadorSPEDFiscal.importarEscrituracao(l, esc, progresso, 1000, 1000, new SessaoVep());
    }
    StringBuilder b = new StringBuilder("[");
    int n = 0;
    if (r.getMapErros() != null) {
      for (List<Inconsistencia> is : r.getMapErros().values()) {
        for (Inconsistencia i : is) {
          if (n++ > 0) b.append(',');
          b.append("{\"tipo\":").append(js(i.getTipo() == null ? null : i.getTipo().name().substring(0, 1)))
              .append(",\"codigo\":").append(js(i.getIdentificador()))
              .append(",\"registro\":").append(js(i.getNomeRegistro()))
              .append(",\"campo\":").append(js(i.getIdentificadorCampo()))
              .append(",\"linha\":").append(i.getLinhaArquivo() == null ? 0 : i.getLinhaArquivo())
              .append(",\"valor\":").append(js(i.getValorCampo()))
              .append(",\"esperado\":").append(js(i.getValorEsperado()))
              .append(",\"conteudo\":").append(js(i.getValorRegistro()))
              .append('}');
        }
      }
    }
    return b.append(']').toString();
  }

  static synchronized String validar(Path arq) {
    capturada = null;
    mensagens.clear();
    long t = System.currentTimeMillis();
    StringBuilder out = new StringBuilder("{\"versaoPva\":").append(js(versao));
    try {
      controle.importarEscrituracao(arq.toString(), ui, progresso, false, 1000, 1000);
      String estado = capturada == null ? null : String.valueOf(capturada.getEstado());
      boolean ok = "VALIDADA".equals(estado) || "GERADA_PARA_ENTREGA".equals(estado);
      out.append(",\"estado\":").append(js(estado)).append(",\"valido\":").append(ok);
      String erros = "[]";
      if (capturada != null && capturada.getEstado() == null) {
        try {
          erros = errosDeImportacao(arq, capturada);
        } catch (Throwable e) {
          out.append(",\"falha\":").append(js("erros de importação indisponíveis: " + e));
        }
      } else if (capturada != null && !ok) {
        // Arquivo recusado na importação não chega a ter banco para o relatório.
        try {
          erros = inconsistencias(capturada);
        } catch (Throwable e) {
          out.append(",\"falha\":").append(js("relatório de inconsistências indisponível: " + e));
        }
      }
      out.append(",\"erros\":").append(erros);
    } catch (Throwable e) {
      out.append(",\"valido\":false,\"falha\":").append(js(e));
    } finally {
      if (capturada != null) {
        try {
          ControleEscrituracaoFiscal.getSingleton().apagarEscrituracaoBanco(capturada);
        } catch (Throwable e) {
          System.err.println("apagar escrituracao: " + e);
        }
      }
    }
    out.append(",\"mensagens\":[");
    for (int i = 0; i < mensagens.size(); i++) out.append(i > 0 ? "," : "").append(js(mensagens.get(i)));
    return out.append("],\"ms\":").append(System.currentTimeMillis() - t).append('}').toString();
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
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < l.size(); i++) b.append(i > 0 ? "," : "").append(js(l.get(i)));
    return b.append(']').toString();
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

  public static void main(String[] args) throws Exception {
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
    srv.createContext("/validar", ex -> {
      if (!"POST".equals(ex.getRequestMethod())) {
        responder(ex, 405, "{\"erro\":\"use POST\"}");
        return;
      }
      Path arq = Files.createTempFile("efd-", ".txt");
      try (InputStream in = ex.getRequestBody()) {
        long n = Files.copy(in, arq, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (n == 0 || n > LIMITE_BYTES) {
          responder(ex, 413, "{\"erro\":\"arquivo vazio ou maior que o limite (PVA_LIMITE_MB)\"}");
          return;
        }
        responder(ex, 200, validar(arq));
      } finally {
        Files.deleteIfExists(arq);
      }
    });
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
