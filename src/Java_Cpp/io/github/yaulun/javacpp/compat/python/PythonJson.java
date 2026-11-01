package io.github.yaulun.javacpp.compat.python;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Python json.dumps subset used by the chat-format ports: true/false/null literals, int vs float
 * repr, ensure_ascii \\uXXXX escaping (with the ASCII control shorthand table), item/key
 * separators, indent and sort_keys.
 */
public final class PythonJson {
  private PythonJson() {}

  /** Ports json.dumps with the kwargs the pinned call sites pass. */
  public static String dumps(
      Object value,
      boolean ensureAscii,
      Integer indent,
      List<String> separators,
      boolean sortKeys) {
    String itemSep = separators != null ? separators.get(0) : null;
    String keySep = separators != null ? separators.get(1) : null;
    StringBuilder out = new StringBuilder();
    write(value, ensureAscii, indent, itemSep, keySep, sortKeys, out, 0);
    return out.toString();
  }

  /** json.dumps(x) with CPython defaults. */
  public static String dumps(Object value) {
    return dumps(value, true, null, null, false);
  }

  private static void write(
      Object value,
      boolean ascii,
      Integer indent,
      String itemSep,
      String keySep,
      boolean sortKeys,
      StringBuilder out,
      int level) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof Boolean b) {
      out.append(b ? "true" : "false");
    } else if (value instanceof Map<?, ?> map) {
      Map<?, ?> ordered = sortKeys ? new TreeMap<>(map) : map;
      if (ordered.isEmpty()) {
        out.append("{}");
        return;
      }
      String[] sep = separators(itemSep, keySep, indent);
      out.append('{');
      String comma = "";
      for (Map.Entry<?, ?> entry : ordered.entrySet()) {
        out.append(comma).append(newline(indent, level + 1));
        write(String.valueOf(entry.getKey()), ascii, null, null, null, false, out, 0);
        out.append(sep[1]);
        write(entry.getValue(), ascii, indent, itemSep, keySep, sortKeys, out, level + 1);
        comma = sep[0];
      }
      out.append(newline(indent, level)).append('}');
    } else if (value instanceof Iterable<?> list) {
      String[] sep = separators(itemSep, keySep, indent);
      out.append('[');
      String comma = "";
      boolean empty = true;
      for (Object item : list) {
        empty = false;
        out.append(comma).append(newline(indent, level + 1));
        write(item, ascii, indent, itemSep, keySep, sortKeys, out, level + 1);
        comma = sep[0];
      }
      if (empty) {
        out.append(']');
      } else {
        out.append(newline(indent, level)).append(']');
      }
    } else if (value instanceof CharSequence || value instanceof Character) {
      writeString(String.valueOf(value), ascii, out);
    } else if (value instanceof Number number) {
      out.append(numberRepr(number));
    } else {
      throw new IllegalArgumentException("not JSON serializable: " + value.getClass());
    }
  }

  private static String[] separators(String itemSep, String keySep, Integer indent) {
    if (indent == null) {
      return new String[] {itemSep != null ? itemSep : ", ", keySep != null ? keySep : ": "};
    }
    return new String[] {itemSep != null ? itemSep : ",", keySep != null ? keySep : ": "};
  }

  private static String newline(Integer indent, int level) {
    if (indent == null) return "";
    return "\n" + " ".repeat(indent * level);
  }

  private static void writeString(String text, boolean ascii, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20 || (ascii && c > 0x7e)) {
            if (Character.isHighSurrogate(c)
                && i + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(i + 1))) {
              int cp = Character.toCodePoint(c, text.charAt(i + 1));
              int high = 0xd800 + ((cp - 0x10000) >> 10);
              int low = 0xdc00 + ((cp - 0x10000) & 0x3ff);
              out.append(String.format("\\u%04x\\u%04x", high, low));
              i++;
            } else {
              out.append(String.format("\\u%04x", (int) c));
            }
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** Python repr for JSON numbers: ints verbatim, floats shortest-roundtrip-ish. */
  private static String numberRepr(Number number) {
    if (number instanceof Integer
        || number instanceof Long
        || number instanceof Short
        || number instanceof Byte
        || number instanceof BigInteger) {
      return number.toString();
    }
    double value = number.doubleValue();
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return new BigDecimal(value).stripTrailingZeros().toPlainString() + ".0";
    }
    return Double.toString(value);
  }

  /** Minimal json.loads returning Map/List/String/Number/Boolean/null. */
  public static Object loads(String text) {
    int[] pos = {0};
    Object v = parse(text, pos);
    skipWs(text, pos);
    if (pos[0] != text.length()) {
      throw new IllegalArgumentException("Extra data");
    }
    return v;
  }

  private static void skipWs(String t, int[] pos) {
    while (pos[0] < t.length() && " \t\r\n".indexOf(t.charAt(pos[0])) >= 0) pos[0]++;
  }

  private static Object parse(String t, int[] pos) {
    skipWs(t, pos);
    char c = t.charAt(pos[0]);
    if (c == '{') {
      pos[0]++;
      java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
      skipWs(t, pos);
      if (t.charAt(pos[0]) == '}') {
        pos[0]++;
        return m;
      }
      while (true) {
        skipWs(t, pos);
        String k = (String) parseString(t, pos);
        skipWs(t, pos);
        pos[0]++; // ':'
        m.put(k, parse(t, pos));
        skipWs(t, pos);
        char d = t.charAt(pos[0]++);
        if (d == '}') return m;
      }
    } else if (c == '[') {
      pos[0]++;
      java.util.List<Object> l = new java.util.ArrayList<>();
      skipWs(t, pos);
      if (t.charAt(pos[0]) == ']') {
        pos[0]++;
        return l;
      }
      while (true) {
        l.add(parse(t, pos));
        skipWs(t, pos);
        char d = t.charAt(pos[0]++);
        if (d == ']') return l;
      }
    } else if (c == '"') {
      return parseString(t, pos);
    } else if (c == 't' || c == 'f' || c == 'n') {
      if (t.startsWith("true", pos[0])) {
        pos[0] += 4;
        return Boolean.TRUE;
      }
      if (t.startsWith("false", pos[0])) {
        pos[0] += 5;
        return Boolean.FALSE;
      }
      pos[0] += 4;
      return null;
    } else {
      int start = pos[0];
      while (pos[0] < t.length() && "+-0123456789.eE".indexOf(t.charAt(pos[0])) >= 0) pos[0]++;
      String num = t.substring(start, pos[0]);
      if (num.contains(".") || num.contains("e") || num.contains("E")) {
        return Double.parseDouble(num);
      }
      return Long.parseLong(num);
    }
  }

  private static Object parseString(String t, int[] pos) {
    pos[0]++;
    StringBuilder b = new StringBuilder();
    while (true) {
      char c = t.charAt(pos[0]++);
      if (c == '"') return b.toString();
      if (c == '\\') {
        char e = t.charAt(pos[0]++);
        switch (e) {
          case 'u' -> {
            b.append((char) Integer.parseInt(t.substring(pos[0], pos[0] + 4), 16));
            pos[0] += 4;
          }
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case 't' -> b.append('\t');
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case '/' -> b.append('/');
          default -> b.append(e);
        }
      } else {
        b.append(c);
      }
    }
  }
}
