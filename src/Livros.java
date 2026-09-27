import br.gov.serpro.comum.relatorio.IRelatorio;
import br.gov.serpro.sped.fiscalpva.dominio.entidades.EscrituracaoFiscal;
import br.gov.serpro.sped.fiscalpva.nucleo.controle.fabrica.FabricaControle;
import br.gov.serpro.sped.fiscalpva.persistencia.PersistenciaFiscalPVA;
import br.gov.serpro.sped.fiscalpva.relatorios.ato002.comun.entidades.ParametroPesquisa;
import br.gov.serpro.sped.fiscalpva.relatorios.controle.IControleGerarRelatorio;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintPage;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperPrint;

// Livros e relatórios oficiais que o PVA gera na tela (menu Relatórios), montados pelos mesmos controladores.
final class Livros {
  private Livros() {}

  interface Periodos {
    List<ParametroPesquisa> de(IControleGerarRelatorio c) throws Exception;
  }

  interface Gerar {
    IRelatorio com(IControleGerarRelatorio c, ParametroPesquisa p) throws Exception;
  }

  record Livro(String titulo, Periodos periodos, Gerar gerar) {}

  static final Map<String, Livro> LIVROS = new LinkedHashMap<>();

  static {
    LIVROS.put("apuracao_icms", new Livro("Registro de Apuração do ICMS (E100/E110)",
        IControleGerarRelatorio::getPeriodosDeApuracaoDoICMS_E100,
        (c, p) -> c.gerarRelatorioRegistroFiscaisApuracaoICMSOP(p, PvaServer.progresso)));
    LIVROS.put("apuracao_st", new Livro("Registro de Apuração do ICMS-ST (E200/E210)",
        IControleGerarRelatorio::getPeriodosDeApuracaoDoICMS_E200,
        (c, p) -> c.gerarRelatorioRegistroFiscaisApuracaoICMSST(p, PvaServer.progresso)));
    LIVROS.put("difal", new Livro("Apuração do DIFAL/FCP (E300/E310)",
        IControleGerarRelatorio::getPeriodosDeApuracaoDoICMS_E300,
        (c, p) -> c.gerarRelatorioDIFAL(p, PvaServer.progresso)));
    LIVROS.put("apuracao_ipi", new Livro("Registro de Apuração do IPI (E500/E520)",
        IControleGerarRelatorio::getPeriodosDeApuracaoDoICMS_E500,
        (c, p) -> c.gerarRelatorioRegistroFiscaisApuracaoDosValoresDoIPI(p, PvaServer.progresso)));
    LIVROS.put("inventario", new Livro("Registro de Inventário (H005/H010)",
        IControleGerarRelatorio::getDataInvetarioH005,
        (c, p) -> c.gerarRelatorioIventario(p, PvaServer.progresso)));
    LIVROS.put("ciap", new Livro("Controle de Crédito do Ativo Permanente (G110)",
        IControleGerarRelatorio::getPeriodosDeApuracaoCiap_G110,
        (c, p) -> c.gerarRelatorioRegistroFiscaisCiap(p, PvaServer.progresso)));
    LIVROS.put("entradas", new Livro("Registro de Entradas",
        IControleGerarRelatorio::getPeriodosDeApuracaoDoICMS_E100,
        (c, p) -> c.gerarRelatorioDocumentosFiscaisEntrada(p, PvaServer.progresso)));
    LIVROS.put("saidas", new Livro("Registro de Saídas",
        IControleGerarRelatorio::getPeriodosDeApuracaoDoICMS_E100,
        (c, p) -> c.gerarRelatorioDocumentosFiscaisSaida(p, PvaServer.progresso)));
    LIVROS.put("producao_estoque", new Livro("Controle da Produção e do Estoque (K100)",
        IControleGerarRelatorio::getPeriodosDeApuracao_K100,
        (c, p) -> c.gerarRelatorioProducaoEstoque(p, PvaServer.progresso)));
    LIVROS.put("creditos_fiscais", new Livro("Controle de Créditos Fiscais (1200)", c -> List.of(new ParametroPesquisa()),
        (c, p) -> c.gerarRelatorioControleCreditosFiscais(PvaServer.progresso)));
  }

  static Map<String, Object> periodo(ParametroPesquisa p) {
    return Json.obj("rotulo", p.getLabel(), "inicio", p.getDT_INI(), "fim", p.getDT_FIN(), "uf", p.getUF(),
        "indApur", p.getIND_APUR(), "dataInventario", p.getDT_INV());
  }

  static IControleGerarRelatorio abrir(EscrituracaoFiscal esc) {
    IControleGerarRelatorio c = FabricaControle.getSingleton().getServico(IControleGerarRelatorio.class);
    c.inicializarControleGerarRelatorio(esc, PersistenciaFiscalPVA.getSingleton().abrirPersistenciaEscrituracaoFiscal(esc));
    c.carregarDadosCabecalhoRodape(esc);
    c.setVersaoCorrenteAplicativo(PvaServer.versao);
    return c;
  }

  // Quais livros a escrituração tem e com quais períodos (o índice escolhe o período em gerar).
  static List<Map<String, Object>> disponiveis(EscrituracaoFiscal esc) throws Exception {
    IControleGerarRelatorio c = abrir(esc);
    List<Map<String, Object>> out = new ArrayList<>();
    try {
      for (Map.Entry<String, Livro> e : LIVROS.entrySet()) {
        List<Map<String, Object>> ps = new ArrayList<>();
        String falha = null;
        try {
          for (ParametroPesquisa p : e.getValue().periodos().de(c)) ps.add(periodo(p));
        } catch (Throwable t) {
          falha = String.valueOf(t);
        }
        Map<String, Object> m = Json.obj("livro", e.getKey(), "titulo", e.getValue().titulo(), "periodos", ps);
        if (falha != null) m.put("falha", falha);
        out.add(m);
      }
    } finally {
      c.encerrarControleGerarRelatorio();
    }
    return out;
  }

  static final java.util.Locale PT_BR = java.util.Locale.forLanguageTag("pt-BR");

  // O PVA roda na tela com locale pt-BR: sem isso os valores saem 1,000.00. Só troca durante a geração,
  // que roda com o PVA travado.
  static JasperPrint gerar(EscrituracaoFiscal esc, String livro, int indice, boolean detalhar) throws Exception {
    java.util.Locale antes = java.util.Locale.getDefault();
    java.util.Locale.setDefault(PT_BR);
    try {
      return gerarPtBr(esc, livro, indice, detalhar);
    } finally {
      java.util.Locale.setDefault(antes);
    }
  }

  private static JasperPrint gerarPtBr(EscrituracaoFiscal esc, String livro, int indice, boolean detalhar) throws Exception {
    Livro l = LIVROS.get(livro);
    if (l == null) throw new IllegalArgumentException("livro deve ser um de " + LIVROS.keySet());
    IControleGerarRelatorio c = abrir(esc);
    try {
      List<ParametroPesquisa> ps = l.periodos().de(c);
      if (ps == null || ps.isEmpty()) throw new IllegalArgumentException("a escrituração não tem " + l.titulo());
      if (indice < 0 || indice >= ps.size()) throw new IllegalArgumentException("periodo vai de 0 a " + (ps.size() - 1));
      ps.get(indice).setExibirDetalheDocumentos(detalhar);
      IRelatorio r = l.gerar().com(c, ps.get(indice));
      if (r == null) throw new IllegalArgumentException("o PVA não gerou " + l.titulo() + " para esse período");
      return r.getTodasAsPaginas();
    } finally {
      c.encerrarControleGerarRelatorio();
    }
  }

  static void pdf(JasperPrint jp, Path destino) throws Exception {
    JasperExportManager.exportReportToPdfFile(jp, destino.toString());
  }

  // Texto por página: elementos agrupados em linhas pela posição vertical, colunas separadas por " | ".
  static List<List<String>> texto(JasperPrint jp) {
    List<List<String>> paginas = new ArrayList<>();
    for (JRPrintPage pg : jp.getPages()) {
      List<int[]> pos = new ArrayList<>();
      List<String> txt = new ArrayList<>();
      coletar(pg.getElements(), 0, 0, pos, txt);
      Integer[] ordem = new Integer[txt.size()];
      for (int i = 0; i < ordem.length; i++) ordem[i] = i;
      java.util.Arrays.sort(ordem, Comparator.<Integer>comparingInt(i -> pos.get(i)[1]).thenComparingInt(i -> pos.get(i)[0]));
      List<String> linhas = new ArrayList<>();
      StringBuilder b = null;
      int y = 0;
      for (int i : ordem) {
        int yi = pos.get(i)[1];
        if (b == null || yi - y > 3) {
          if (b != null) linhas.add(b.toString());
          b = new StringBuilder();
          y = yi;
        } else {
          b.append(" | ");
        }
        b.append(txt.get(i));
      }
      if (b != null) linhas.add(b.toString());
      paginas.add(linhas);
    }
    return paginas;
  }

  private static void coletar(List<JRPrintElement> els, int dx, int dy, List<int[]> pos, List<String> txt) {
    for (JRPrintElement e : els) {
      if (e instanceof JRPrintFrame f) {
        coletar(f.getElements(), dx + f.getX(), dy + f.getY(), pos, txt);
      } else if (e instanceof JRPrintText t) {
        String s = t.getFullText();
        if (s == null || s.isBlank()) continue;
        pos.add(new int[] {dx + t.getX(), dy + t.getY()});
        txt.add(s.replaceAll("\\s+", " ").trim());
      }
    }
  }
}
