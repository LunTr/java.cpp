package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import java.math.*;

/**
 * Python numeric operations at the Java compatibility boundary, including their error categories.
 */
public final class PythonNumbers {
  private PythonNumbers() {}

  public static BigInteger integer(Object value) {
    return Py.integer(value);
  }

  public static double floating(Object value) {
    return Py.floating(value);
  }

  public static BigInteger index(Object value) {
    return Py.index(value);
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
    return Py.equal(value, number);
  }

  static Object subtract(Object value, int amount) {
    return Py.subtract(value, BigInteger.valueOf(amount));
  }
}
