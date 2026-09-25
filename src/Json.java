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
