import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

// Textos oficiais das mensagens do validador e leitura das tabelas externas
// que o PVA baixa da Receita, direto dos arquivos que ele mesmo usa.
final class Catalogo {
  private Catalogo() {}

  static final Path DIR_TABELAS = Path.of("recursos/TabelasExternas");
  private static final Properties MENSAGENS = new Properties();
  private static final DateTimeFormatter DDMMAAAA = DateTimeFormatter.ofPattern("ddMMyyyy");

  static void carregar() {
    try (InputStream in = Catalogo.class.getClassLoader().getResourceAsStream("descritor/comum/mensagens/validador.prop")) {
      if (in != null) MENSAGENS.load(in);
    } catch (IOException e) {
      System.err.println("catalogo de mensagens: " + e);
    }
  }

  static String mensagem(String codigo) {
    if (codigo == null) return null;
    String m = MENSAGENS.getProperty(codigo);
    return m == null ? null : m.trim();
  }

  static Map<String, String> mensagens() {
    Map<String, String> out = new TreeMap<>();
    for (String k : MENSAGENS.stringPropertyNames()) out.put(k, MENSAGENS.getProperty(k).trim());
    return out;
  }

  // Arquivos "<PACOTE>$<TABELA>$<versão>$<id>", ex.: SPEDFISCAL_CE$AJ_APUR_DED$51$36.
  static List<Map<String, Object>> listarTabelas() throws IOException {
    List<Map<String, Object>> out = new ArrayList<>();
    try (var ds = Files.newDirectoryStream(DIR_TABELAS)) {
      for (Path f : ds) {
        String[] p = f.getFileName().toString().split("\\$");
        if (p.length < 3) continue;
        out.add(Json.obj("pacote", p[0], "tabela", p[1], "versao", p[2]));
      }
    }
    out.sort((a, b) -> (a.get("tabela") + "" + a.get("pacote")).compareTo(b.get("tabela") + "" + b.get("pacote")));
    return out;
  }

  // Filtra por UF (descarta os pacotes SPEDFISCAL_<outra UF>), prefixo do código e data de vigência.
  static Map<String, Object> consultarTabela(String tabela, String uf, String codigo, String data) throws IOException {
    LocalDate dia = data == null || data.isBlank() ? null : LocalDate.parse(data);
    List<Map<String, Object>> linhas = new ArrayList<>();
    List<String> pacotes = new ArrayList<>();
    try (var ds = Files.newDirectoryStream(DIR_TABELAS)) {
      for (Path f : ds) {
        String[] p = f.getFileName().toString().split("\\$");
        if (p.length < 3 || !p[1].equalsIgnoreCase(tabela)) continue;
        if (uf != null && !uf.isBlank() && p[0].matches("(?i)SPEDFISCAL_[A-Z]{2}") && !p[0].equalsIgnoreCase("SPEDFISCAL_" + uf)) continue;
        pacotes.add(p[0] + " v" + p[2]);
        List<String> texto = Files.readAllLines(f, StandardCharsets.ISO_8859_1);
        if (texto.isEmpty()) continue;
        String cab = texto.get(0);
        String[] colunas = (cab.contains(" ") ? cab.substring(cab.indexOf(' ') + 1) : cab).split(",\\s*");
        int iIni = indice(colunas, "DT_INI"), iFim = indice(colunas, "DT_FIM");
        for (String l : texto.subList(1, texto.size())) {
          if (l.isBlank()) continue;
          String[] v = l.split("\\|", -1);
          if (codigo != null && !codigo.isBlank() && !v[0].toUpperCase().startsWith(codigo.toUpperCase())) continue;
          if (dia != null && !vigente(v, iIni, iFim, dia)) continue;
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("pacote", p[0]);
          for (int i = 0; i < colunas.length && i < v.length; i++) m.put(colunas[i].trim(), v[i]);
          linhas.add(m);
        }
      }
    }
    return Json.obj("tabela", tabela.toUpperCase(), "pacotes", pacotes, "total", linhas.size(),
        "linhas", linhas.size() > 2000 ? linhas.subList(0, 2000) : linhas);
  }

  private static int indice(String[] colunas, String nome) {
    for (int i = 0; i < colunas.length; i++) if (colunas[i].trim().equalsIgnoreCase(nome)) return i;
    return -1;
  }

  private static boolean vigente(String[] v, int iIni, int iFim, LocalDate dia) {
    try {
      if (iIni >= 0 && iIni < v.length && !v[iIni].isBlank() && LocalDate.parse(v[iIni], DDMMAAAA).isAfter(dia)) return false;
      if (iFim >= 0 && iFim < v.length && !v[iFim].isBlank() && LocalDate.parse(v[iFim], DDMMAAAA).isBefore(dia)) return false;
    } catch (RuntimeException e) {
      return true;
    }
    return true;
  }
}
