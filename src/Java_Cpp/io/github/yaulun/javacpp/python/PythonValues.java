package io.github.yaulun.javacpp.python;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared operations on the JSON-shaped values accepted by Python-facing chat functions. */
public final class PythonValues {
  private PythonValues() {}

  public static PythonException error(PythonErrorType type, String message) {
    return Py.error(type, message);
  }

  public static boolean truth(Object value) {
    return Py.truth(value);
  }

  public static String typeName(Object value) {
    return Py.typeName(value);
  }

  public static Object get(Object value, String key) {
    return Py.getItem(value, key);
  }

  public static List<?> list(Object value) {
    return Py.list(value);
  }

  public static Object first(Object value) {
    return Py.getItem(value, 0);
  }

  public static String string(Object value) {
    return Py.str(value);
  }

  public static String concat(Object value) {
    if (value instanceof String text) return text;
    throw error(
        PythonErrorType.TYPE_ERROR,
        "can only concatenate str (not \"" + typeName(value) + "\") to str");
  }

  public static String leftString(Object value) {
    if (value instanceof String text) return text;
    if (value instanceof List<?>) {
      throw error(PythonErrorType.TYPE_ERROR, "can only concatenate list (not \"str\") to list");
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "unsupported operand type(s) for +: '" + typeName(value) + "' and 'str'");
  }

  public static Map<String, Object> dict(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public static String repr(Object value) {
    return Py.repr(value);
  }
}
