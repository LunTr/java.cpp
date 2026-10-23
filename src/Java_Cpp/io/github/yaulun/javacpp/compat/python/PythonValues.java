package io.github.yaulun.javacpp.compat.python;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared operations on the JSON-shaped values accepted by Python-facing chat functions. */
final class PythonValues {
  private PythonValues() {}

  static PythonException error(PythonErrorType type, String message) {
    return new PythonException(type, message, null);
  }

  static boolean truth(Object value) {
    if (value == null) return false;
    if (value instanceof Boolean flag) return flag;
    if (value instanceof String text) return !text.isEmpty();
    if (value instanceof Collection<?> values) return !values.isEmpty();
    if (value instanceof Map<?, ?> values) return !values.isEmpty();
    if (value instanceof Number number) return number.doubleValue() != 0;
    return true;
  }

  static String typeName(Object value) {
    if (value == null) return "NoneType";
    if (value instanceof String) return "str";
    if (value instanceof Boolean) return "bool";
    if (value instanceof Float || value instanceof Double) return "float";
    if (value instanceof Number) return "int";
    if (value instanceof Map<?, ?>) return "dict";
    if (value instanceof List<?>) return "list";
    return value.getClass().getSimpleName();
  }

  static Object get(Object value, String key) {
    if (value instanceof Map<?, ?> map) {
      if (!map.containsKey(key)) throw error(PythonErrorType.KEY_ERROR, repr(key));
      return map.get(key);
    }
    if (value instanceof List<?>) {
      throw error(PythonErrorType.TYPE_ERROR, "list indices must be integers or slices, not str");
    }
    if (value instanceof String) {
      throw error(PythonErrorType.TYPE_ERROR, "string indices must be integers, not 'str'");
    }
    throw error(
        PythonErrorType.TYPE_ERROR, "'" + typeName(value) + "' object is not subscriptable");
  }

  static List<?> list(Object value) {
    if (value instanceof List<?> values) return values;
    if (value instanceof String text) {
      return text.codePoints().mapToObj(cp -> new String(Character.toChars(cp))).toList();
    }
    if (value instanceof Map<?, ?> values) return new java.util.ArrayList<>(values.keySet());
    throw error(PythonErrorType.TYPE_ERROR, "'" + typeName(value) + "' object is not iterable");
  }

  static Object first(Object value) {
    if (!(value instanceof List<?>)) {
      if (value instanceof Map<?, ?> map) {
        if (map.containsKey(0)) return map.get(0);
        throw error(PythonErrorType.KEY_ERROR, "0");
      }
      if (value instanceof String text) {
        if (text.isEmpty()) throw error(PythonErrorType.INDEX_ERROR, "string index out of range");
        return new String(Character.toChars(text.codePointAt(0)));
      }
      throw error(
          PythonErrorType.TYPE_ERROR, "'" + typeName(value) + "' object is not subscriptable");
    }
    List<?> values = (List<?>) value;
    if (values.isEmpty()) throw error(PythonErrorType.INDEX_ERROR, "list index out of range");
    return values.getFirst();
  }

  static String string(Object value) {
    if (value instanceof String text) return text;
    return repr(value);
  }

  static String concat(Object value) {
    if (value instanceof String text) return text;
    throw error(
        PythonErrorType.TYPE_ERROR,
        "can only concatenate str (not \"" + typeName(value) + "\") to str");
  }

  static String leftString(Object value) {
    if (value instanceof String text) return text;
    if (value instanceof List<?>) {
      throw error(PythonErrorType.TYPE_ERROR, "can only concatenate list (not \"str\") to list");
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "unsupported operand type(s) for +: '" + typeName(value) + "' and 'str'");
  }

  static Map<String, Object> dict(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  static String repr(Object value) {
    if (value == null) return "None";
    if (value instanceof Boolean flag) return flag ? "True" : "False";
    if (value instanceof Double || value instanceof Float)
      return floatRepr(((Number) value).doubleValue());
    if (value instanceof String text) {
      char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
      StringBuilder result = new StringBuilder().append(quote);
      text.codePoints()
          .forEach(
              cp -> {
                if (cp == quote || cp == '\\') result.append('\\').appendCodePoint(cp);
                else if (cp == '\n') result.append("\\n");
                else if (cp == '\r') result.append("\\r");
                else if (cp == '\t') result.append("\\t");
                else if (!PythonUnicode.printable(cp)) {
                  result.append(
                      String.format(
                          java.util.Locale.ROOT,
                          cp <= 255 ? "\\x%02x" : cp <= 65535 ? "\\u%04x" : "\\U%08x",
                          cp));
                } else result.appendCodePoint(cp);
              });
      return result.append(quote).toString();
    }
    if (value instanceof List<?> values) {
      return "[" + String.join(", ", values.stream().map(PythonValues::repr).toList()) + "]";
    }
    if (value instanceof Map<?, ?> values) {
      return "{"
          + String.join(
              ", ",
              values.entrySet().stream()
                  .map(e -> repr(e.getKey()) + ": " + repr(e.getValue()))
                  .toList())
          + "}";
    }
    return value.toString();
  }

  private static String floatRepr(double value) {
    if (Double.isNaN(value)) return "nan";
    if (value == Double.POSITIVE_INFINITY) return "inf";
    if (value == Double.NEGATIVE_INFINITY) return "-inf";
    if (value == 0) return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
    BigDecimal exact = new BigDecimal(value);
    BigDecimal shortest = exact;
    for (int precision = 1; precision <= 17; precision++) {
      BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
      if (Double.doubleToRawLongBits(candidate.doubleValue())
          == Double.doubleToRawLongBits(value)) {
        shortest = candidate.stripTrailingZeros();
        break;
      }
    }
    int exponent = shortest.precision() - shortest.scale() - 1;
    if (exponent < -4 || exponent >= 16) {
      String mantissa = shortest.movePointLeft(exponent).toPlainString();
      return mantissa
          + "e"
          + (exponent >= 0 ? "+" : "-")
          + String.format(java.util.Locale.ROOT, "%02d", Math.abs(exponent));
    }
    String result = shortest.toPlainString();
    return result.contains(".") ? result : result + ".0";
  }
}
