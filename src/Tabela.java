import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Tabelas em Markdown prontas para o cliente MCP mostrar ao usuário: achados, erros e correções lado a lado.
final class Tabela {
  private Tabela() {}

  static final int MAX_CELULA = 90;
  static final int MAX_LINHAS = 50;

  private final List<String> cab = new ArrayList<>();
  private final List<List<Object>> linhas = new ArrayList<>();
  private int omitidas;

  static Tabela com(String... colunas) {
    Tabela t = new Tabela();
    t.cab.addAll(List.of(colunas));
    return t;
  }

  Tabela linha(Object... valores) {
    if (linhas.size() >= MAX_LINHAS) {
      omitidas++;
    } else {
      linhas.add(List.of(java.util.Arrays.stream(valores).map(v -> v == null ? "" : v).toArray()));
    }
    return this;
  }

  boolean vazia() {
    return linhas.isEmpty();
  }

  static final java.text.DecimalFormat BR = new java.text.DecimalFormat("#,##0.00",
      java.text.DecimalFormatSymbols.getInstance(java.util.Locale.forLanguageTag("pt-BR")));

  static String celula(Object v) {
    if (v instanceof java.math.BigDecimal b) {
      synchronized (BR) {
        v = BR.format(b);
      }
    }
    String s = v == null ? "" : String.valueOf(v).replaceAll("\\s+", " ").trim().replace("|", "\\|");
    return s.length() > MAX_CELULA ? s.substring(0, MAX_CELULA - 1) + "…" : s;
  }

  String md() {
    StringBuilder b = new StringBuilder("| ");
    b.append(String.join(" | ", cab.stream().map(Tabela::celula).toList())).append(" |\n|");
    for (int i = 0; i < cab.size(); i++) b.append("---|");
    b.append('\n');
    for (List<Object> l : linhas) {
      b.append("| ").append(String.join(" | ", l.stream().map(Tabela::celula).toList())).append(" |\n");
    }
    if (omitidas > 0) b.append("\n(+").append(omitidas).append(" linhas; pagine para ver o resto)\n");
    return b.toString();
  }

  // Seções com título; as vazias ficam de fora.
  static String juntar(Object... tituloETabela) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < tituloETabela.length; i += 2) {
      if (!(tituloETabela[i + 1] instanceof Tabela t) || t.vazia()) continue;
      if (b.length() > 0) b.append('\n');
      b.append("**").append(tituloETabela[i]).append("**\n\n").append(t.md());
    }
    return b.toString();
  }

  // Linhas genéricas (SQL, ocorrências): colunas da primeira linha, no máximo 10.
  static Tabela deMapas(List<?> itens) {
    if (itens.isEmpty() || !(itens.get(0) instanceof Map<?, ?> primeiro)) return com();
    List<String> cols = new ArrayList<>();
    for (Object k : primeiro.keySet()) {
      if (cols.size() < 10) cols.add(String.valueOf(k));
    }
    Tabela t = com(cols.toArray(String[]::new));
    for (Object o : itens) {
      Map<?, ?> m = (Map<?, ?>) o;
      t.linha(cols.stream().map(m::get).toArray());
    }
    return t;
  }
}
