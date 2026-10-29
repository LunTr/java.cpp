package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Python numeric operations at the Java compatibility boundary, including their error categories.
 */
public final class PythonNumbers {
  private PythonNumbers() {}

  public static BigInteger integer(Object value) {
    if (value instanceof Boolean flag) return flag ? BigInteger.ONE : BigInteger.ZERO;
    if (value instanceof BigInteger number) return number;
    if (value instanceof BigDecimal number) return number.toBigInteger();
    if (value instanceof Float || value instanceof Double) {
      double number = ((Number) value).doubleValue();
      if (Double.isNaN(number))
        throw error(PythonErrorType.VALUE_ERROR, "cannot convert float NaN to integer");
      if (Double.isInfinite(number))
        throw error(PythonErrorType.OVERFLOW_ERROR, "cannot convert float infinity to integer");
      return new BigDecimal(number).toBigInteger();
    }
    if (value instanceof Number number) return BigInteger.valueOf(number.longValue());
    if (value instanceof String || value instanceof byte[]) {
      String text = decimalText(value);
      if (text.matches("[+-]?[0-9]+(?:_[0-9]+)*")) return new BigInteger(text.replace("_", ""));
      throw error(
          PythonErrorType.VALUE_ERROR,
          "invalid literal for int() with base 10: " + representation(value));
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "int() argument must be a string, a bytes-like object or a real number, not '"
            + typeName(value)
            + "'");
  }

  public static double floating(Object value) {
    if (value instanceof Boolean flag) return flag ? 1 : 0;
    if (value instanceof Number number) {
      double result = number.doubleValue();
      if (value instanceof BigInteger && Double.isInfinite(result))
        throw error(PythonErrorType.OVERFLOW_ERROR, "int too large to convert to float");
      return result;
    }
    if (value instanceof String || value instanceof byte[]) {
      String text = decimalText(value), lower = text.toLowerCase(Locale.ROOT);
      if (lower.matches("[+-]?nan"))
        return lower.startsWith("-") ? Double.longBitsToDouble(0xfff8000000000000L) : Double.NaN;
      if (lower.matches("[+-]?inf(?:inity)?"))
        return lower.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
      String digits = "[0-9](?:_?[0-9])*";
      if (text.matches(
          "[+-]?(?:"
              + digits
              + "(?:\\.(?:"
              + digits
              + ")?)?|\\."
              + digits
              + ")(?:[eE][+-]?"
              + digits
              + ")?")) return Double.parseDouble(text.replace("_", ""));
      throw error(
          PythonErrorType.VALUE_ERROR,
          "could not convert string to float: " + representation(value));
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "float() argument must be a string or a real number, not '" + typeName(value) + "'");
  }

  public static BigInteger index(Object value) {
    if (value instanceof Boolean
        || value instanceof BigInteger
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) return integer(value);
    throw error(
        PythonErrorType.TYPE_ERROR,
        "'" + typeName(value) + "' object cannot be interpreted as an integer");
  }

  public static int arrayIndex(BigInteger index, int size) {
    if (index.bitLength() > 63)
      throw error(
          PythonErrorType.INDEX_ERROR,
          "only integers, slices (`:`), ellipsis (`...`), numpy.newaxis (`None`) and integer or boolean arrays are valid indices");
    BigInteger position = index.signum() < 0 ? index.add(BigInteger.valueOf(size)) : index;
    if (position.signum() < 0 || position.compareTo(BigInteger.valueOf(size)) >= 0)
      throw error(
          PythonErrorType.INDEX_ERROR,
          "index " + index + " is out of bounds for axis 0 with size " + size);
    return position.intValue();
  }

  public static int cInt(Object value, int argument) {
    try {
      return index(value).intValue();
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.ARGUMENT_ERROR, "argument " + argument + ": TypeError: wrong type");
    }
  }

  public static float cFloat(Object value, int argument) {
    if (!(value instanceof Number) && !(value instanceof Boolean))
      throw error(
          PythonErrorType.ARGUMENT_ERROR, "argument " + argument + ": TypeError: wrong type");
    try {
      return (float) floating(value);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.ARGUMENT_ERROR,
          "argument "
              + argument
              + ": "
              + failure.type().pythonName()
              + ": "
              + failure.getMessage());
    }
  }

  static double compareNumber(Object value, String operator, String rightType) {
    if (value instanceof Number || value instanceof Boolean) return floating(value);
    throw error(
        PythonErrorType.TYPE_ERROR,
        "'"
            + operator
            + "' not supported between instances of '"
            + typeName(value)
            + "' and '"
            + rightType
            + "'");
  }

  static boolean equal(Object value, int number) {
    return (value instanceof Number || value instanceof Boolean) && floating(value) == number;
  }

  static Object subtract(Object value, int amount) {
    if (value instanceof Float || value instanceof Double)
      return ((Number) value).doubleValue() - amount;
    if (value instanceof Number || value instanceof Boolean)
      return integer(value).subtract(BigInteger.valueOf(amount));
    throw error(
        PythonErrorType.TYPE_ERROR,
        "unsupported operand type(s) for -: '" + typeName(value) + "' and 'int'");
  }

  private static String representation(Object value) {
    return value instanceof byte[] bytes ? PythonBytes.repr(bytes) : repr(value);
  }

  private static String decimalText(Object value) {
    String text =
        value instanceof byte[] bytes
            ? new String(bytes, StandardCharsets.ISO_8859_1)
            : (String) value;
    int start = 0, end = text.length();
    while (start < end && space(text.codePointAt(start)))
      start += Character.charCount(text.codePointAt(start));
    while (end > start && space(text.codePointBefore(end)))
      end -= Character.charCount(text.codePointBefore(end));
    StringBuilder result = new StringBuilder();
    for (int cp : text.substring(start, end).codePoints().toArray()) {
      if (value instanceof String && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER)
        result.append(Character.digit(cp, 10));
      else result.appendCodePoint(cp);
    }
    return result.toString();
  }

  private static boolean space(int cp) {
    return cp < 128
        ? cp == 32 || (cp >= 9 && cp <= 13)
        : Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
  }
}
