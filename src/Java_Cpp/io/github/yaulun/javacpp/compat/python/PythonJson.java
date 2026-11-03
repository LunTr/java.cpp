package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
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
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          "Object of type " + Py.typeName(value) + " is not JSON serializable");
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
    if (text == null) {
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          "the JSON object must be str, bytes or bytearray, not NoneType");
    }
    int[] pos = {0};
    Object v = parse(text, pos);
    skipWs(text, pos);
    if (pos[0] != text.length()) {
      throw fail(text, pos[0], "Extra data");
    }
    return v;
  }

  /**
   * JSONDecodeError with CPython's "line N column M (char P)" suffix computed from the failure
   * position.
   */
  private static PythonException fail(String text, int pos, String message) {
    int line = 1;
    int lastNewline = -1;
    for (int i = 0; i < pos && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
        lastNewline = i;
      }
    }
    int column = pos - lastNewline;
    return Py.error(
        PythonErrorType.JSON_DECODE_ERROR,
        message + ": line " + line + " column " + column + " (char " + pos + ")");
  }

  private static void skipWs(String t, int[] pos) {
    while (pos[0] < t.length() && " \t\r\n".indexOf(t.charAt(pos[0])) >= 0) pos[0]++;
  }

  private static Object parse(String t, int[] pos) {
    skipWs(t, pos);
    if (pos[0] >= t.length()) {
      throw fail(t, pos[0], "Expecting value");
    }
    char c = t.charAt(pos[0]);
    if (c == '{') {
      pos[0]++;
      java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
      skipWs(t, pos);
      if (pos[0] < t.length() && t.charAt(pos[0]) == '}') {
        pos[0]++;
        return m;
      }
      while (true) {
        skipWs(t, pos);
        if (pos[0] >= t.length() || t.charAt(pos[0]) != '"') {
          throw fail(t, pos[0], "Expecting property name enclosed in double quotes");
        }
        String k = parseString(t, pos);
        skipWs(t, pos);
        if (pos[0] >= t.length() || t.charAt(pos[0]) != ':') {
          throw fail(t, pos[0], "Expecting ':' delimiter");
        }
        pos[0]++;
        m.put(k, parse(t, pos));
        skipWs(t, pos);
        if (pos[0] >= t.length()) {
          throw fail(t, pos[0], "Expecting ',' delimiter");
        }
        char d = t.charAt(pos[0]++);
        if (d == '}') return m;
        if (d != ',') {
          throw fail(t, pos[0] - 1, "Expecting ',' delimiter");
        }
      }
    } else if (c == '[') {
      pos[0]++;
      java.util.List<Object> l = new java.util.ArrayList<>();
      skipWs(t, pos);
      if (pos[0] < t.length() && t.charAt(pos[0]) == ']') {
        pos[0]++;
        return l;
      }
      while (true) {
        l.add(parse(t, pos));
        skipWs(t, pos);
        if (pos[0] >= t.length()) {
          throw fail(t, pos[0], "Expecting ',' delimiter");
        }
        char d = t.charAt(pos[0]++);
        if (d == ']') return l;
        if (d != ',') {
          throw fail(t, pos[0] - 1, "Expecting ',' delimiter");
        }
      }
    } else if (c == '"') {
      return parseString(t, pos);
    } else if (t.startsWith("true", pos[0])) {
      pos[0] += 4;
      return Boolean.TRUE;
    } else if (t.startsWith("false", pos[0])) {
      pos[0] += 5;
      return Boolean.FALSE;
    } else if (t.startsWith("null", pos[0])) {
      pos[0] += 4;
      return null;
    } else if (t.startsWith("NaN", pos[0])) {
      pos[0] += 3;
      return Double.NaN;
    } else if (t.startsWith("Infinity", pos[0])) {
      pos[0] += 8;
      return Double.POSITIVE_INFINITY;
    } else if (t.startsWith("-Infinity", pos[0])) {
      pos[0] += 9;
      return Double.NEGATIVE_INFINITY;
    } else {
      return parseNumber(t, pos);
    }
  }

  private static final java.util.regex.Pattern NUMBER =
      java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?");

  private static Object parseNumber(String t, int[] pos) {
    java.util.regex.Matcher match = NUMBER.matcher(t).region(pos[0], t.length());
    if (!match.lookingAt()) {
      throw fail(t, pos[0], "Expecting value");
    }
    String num = match.group();
    pos[0] = match.end();
    if (match.group(1) != null || match.group(2) != null) {
      return Double.parseDouble(num);
    }
    try {
      return Long.parseLong(num);
    } catch (NumberFormatException overflow) {
      return new BigInteger(num);
    }
  }

  private static String parseString(String t, int[] pos) {
    int start = pos[0];
    pos[0]++;
    StringBuilder b = new StringBuilder();
    while (true) {
      if (pos[0] >= t.length()) {
        throw fail(t, start, "Unterminated string starting at");
      }
      char c = t.charAt(pos[0]++);
      if (c == '"') return b.toString();
      if (c == '\\') {
        int slash = pos[0] - 1;
        if (pos[0] >= t.length()) {
          throw fail(t, start, "Unterminated string starting at");
        }
        char e = t.charAt(pos[0]++);
        switch (e) {
          case '"':
            b.append('"');
            break;
          case '\\':
            b.append('\\');
            break;
          case 'n':
            b.append('\n');
            break;
          case 'r':
            b.append('\r');
            break;
          case 't':
            b.append('\t');
            break;
          case 'b':
            b.append('\b');
            break;
          case 'f':
            b.append('\f');
            break;
          case '/':
            b.append('/');
            break;
          case 'u':
            if (pos[0] + 4 > t.length()) {
              throw fail(t, slash, "Invalid \\uXXXX escape");
            }
            try {
              b.append((char) Integer.parseInt(t.substring(pos[0], pos[0] + 4), 16));
            } catch (NumberFormatException invalid) {
              throw fail(t, slash, "Invalid \\uXXXX escape");
            }
            pos[0] += 4;
            break;
          default:
            throw fail(t, slash, "Invalid \\escape");
        }
      } else {
        if (c < 0x20) {
          throw fail(t, pos[0] - 1, "Invalid control character at");
        }
        b.append(c);
      }
    }
  }
}
