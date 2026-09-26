import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

// Serializa Map/Collection/String/Number/Boolean em JSON, sem dependências.
final class Json {
  private Json() {}

  static Map<String, Object> obj(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  static String of(Object o) {
    StringBuilder b = new StringBuilder();
    write(b, o);
    return b.toString();
  }

  private static void write(StringBuilder b, Object o) {
    if (o == null) {
      b.append("null");
    } else if (o instanceof Map<?, ?> m) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) b.append(',');
        first = false;
        str(b, String.valueOf(e.getKey()));
        b.append(':');
        write(b, e.getValue());
      }
      b.append('}');
    } else if (o instanceof Collection<?> c) {
      b.append('[');
      boolean first = true;
      for (Object x : c) {
        if (!first) b.append(',');
        first = false;
        write(b, x);
      }
      b.append(']');
    } else if (o instanceof BigDecimal d) {
      b.append(d.toPlainString());
    } else if (o instanceof Number || o instanceof Boolean) {
      b.append(o);
    } else {
      str(b, o.toString());
    }
  }

  // Objeto vira LinkedHashMap, lista vira ArrayList, número vira BigDecimal.
  static Object parse(String s) {
    int[] i = {0};
    Object v = valor(s, i);
    espacos(s, i);
    if (i[0] != s.length()) throw new IllegalArgumentException("JSON com conteúdo depois do fim, posição " + i[0]);
    return v;
  }

  private static void espacos(String s, int[] i) {
    while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++;
  }

  private static Object valor(String s, int[] i) {
    espacos(s, i);
    if (i[0] >= s.length()) throw new IllegalArgumentException("JSON incompleto");
    char c = s.charAt(i[0]);
    if (c == '{') {
      Map<String, Object> m = new LinkedHashMap<>();
      i[0]++;
      espacos(s, i);
      if (s.charAt(i[0]) == '}') {
        i[0]++;
        return m;
      }
      while (true) {
        espacos(s, i);
        String k = texto(s, i);
        espacos(s, i);
        esperar(s, i, ':');
        m.put(k, valor(s, i));
        espacos(s, i);
        if (s.charAt(i[0]) == ',') {
          i[0]++;
        } else {
          esperar(s, i, '}');
          return m;
        }
      }
    }
    if (c == '[') {
      java.util.List<Object> l = new java.util.ArrayList<>();
      i[0]++;
      espacos(s, i);
      if (s.charAt(i[0]) == ']') {
        i[0]++;
        return l;
      }
      while (true) {
        l.add(valor(s, i));
        espacos(s, i);
        if (s.charAt(i[0]) == ',') {
          i[0]++;
        } else {
          esperar(s, i, ']');
          return l;
        }
      }
    }
    if (c == '"') return texto(s, i);
    for (String lit : new String[] {"true", "false", "null"}) {
      if (s.startsWith(lit, i[0])) {
        i[0] += lit.length();
        return lit.equals("null") ? null : Boolean.valueOf(lit);
      }
    }
    int ini = i[0];
    while (i[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(i[0])) >= 0) i[0]++;
    if (ini == i[0]) throw new IllegalArgumentException("JSON inválido na posição " + ini);
    return new BigDecimal(s.substring(ini, i[0]));
  }

  private static void esperar(String s, int[] i, char c) {
    if (i[0] >= s.length() || s.charAt(i[0]) != c) throw new IllegalArgumentException("esperado '" + c + "' na posição " + i[0]);
    i[0]++;
  }

  private static String texto(String s, int[] i) {
    esperar(s, i, '"');
    StringBuilder b = new StringBuilder();
    while (true) {
      char c = s.charAt(i[0]++);
      if (c == '"') return b.toString();
      if (c != '\\') {
        b.append(c);
        continue;
      }
      char e = s.charAt(i[0]++);
      switch (e) {
        case 'b' -> b.append('\b');
        case 'f' -> b.append('\f');
        case 'n' -> b.append('\n');
        case 'r' -> b.append('\r');
        case 't' -> b.append('\t');
        case 'u' -> {
          b.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16));
          i[0] += 4;
        }
        default -> b.append(e);
      }
    }
  }

  private static void str(StringBuilder b, String s) {
    b.append('"');
    for (char ch : s.toCharArray()) {
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
    b.append('"');
  }
}
