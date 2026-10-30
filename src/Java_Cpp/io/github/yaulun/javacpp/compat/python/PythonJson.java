package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.*;

import java.util.*;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonNumbers;
import io.github.yaulun.javacpp.python.PythonValues;

/** json.dumps formatting used by the pinned chat-template tojson filter. */
final class PythonJson {
  private final boolean ascii, sort;
  private final String indent, itemSeparator, keySeparator;
  private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());

  PythonJson(boolean ascii, Object indent, Object separators, boolean sort) {
    this.ascii = ascii;
    this.sort = sort;
    this.indent =
        indent == null
            ? null
            : indent instanceof String text
                ? text
                : " ".repeat(Math.max(0, PythonNumbers.index(indent).intValue()));
    List<?> parts =
        separators == null ? List.of(this.indent == null ? ", " : ",", ": ") : list(separators);
    itemSeparator = (String) parts.get(0);
    keySeparator = (String) parts.get(1);
  }

  String write(Object value) {
    return write(value, 0);
  }

  private String write(Object value, int depth) {
    if (value == null) return "null";
    if (value instanceof Boolean flag) return flag ? "true" : "false";
    if (value instanceof String text) return quote(text);
    if (value instanceof Number) {
      String text = repr(value);
      return text.equals("nan")
          ? "NaN"
          : text.equals("inf") ? "Infinity" : text.equals("-inf") ? "-Infinity" : text;
    }
    if (value instanceof Map<?, ?> map) {
      enter(value);
      try {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        if (sort) entries.sort((a, b) -> ((String) a.getKey()).compareTo((String) b.getKey()));
        List<String> output = new ArrayList<>();
        for (var entry : entries) {
          Object key = entry.getKey();
          if (key != null
              && !(key instanceof String)
              && !(key instanceof Number)
              && !(key instanceof Boolean))
            throw error(
                PythonErrorType.TYPE_ERROR,
                "keys must be str, int, float, bool or None, not " + typeName(key));
          String name =
              key instanceof String text
                  ? text
                  : key == null
                      ? "null"
                      : key instanceof Boolean flag
                          ? flag ? "true" : "false"
                          : write(key, depth + 1);
          output.add(quote(name) + keySeparator + write(entry.getValue(), depth + 1));
        }
        return container("{", "}", output, depth);
      } finally {
        active.remove(value);
      }
    }
    if (value instanceof List<?> list) {
      enter(value);
      try {
        return container(
            "[", "]", list.stream().map(item -> write(item, depth + 1)).toList(), depth);
      } finally {
        active.remove(value);
      }
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "Object of type " + typeName(value) + " is not JSON serializable");
  }

  private void enter(Object value) {
    if (!active.add(value)) throw error(PythonErrorType.VALUE_ERROR, "Circular reference detected");
  }

  private String container(String start, String end, List<String> values, int depth) {
    if (values.isEmpty()) return start + end;
    if (indent == null) return start + String.join(itemSeparator, values) + end;
    return start
        + "\n"
        + indent.repeat(depth + 1)
        + String.join(itemSeparator + "\n" + indent.repeat(depth + 1), values)
        + "\n"
        + indent.repeat(depth)
        + end;
  }

  private String quote(String value) {
    StringBuilder text = new StringBuilder("\"");
    for (char c : value.toCharArray())
      switch (c) {
        case '"', '\\' -> text.append('\\').append(c);
        case '\n' -> text.append("\\n");
        case '\r' -> text.append("\\r");
        case '\t' -> text.append("\\t");
        case '\b' -> text.append("\\b");
        case '\f' -> text.append("\\f");
        default -> {
          if (c < 32 || (ascii && c > 127))
            text.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else text.append(c);
        }
      }
    return text.append('"').toString();
  }
}
