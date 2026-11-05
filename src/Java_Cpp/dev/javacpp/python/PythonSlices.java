package dev.javacpp.python;

import static dev.javacpp.python.PythonValues.*;

import java.math.BigInteger;
import java.util.*;

public final class PythonSlices {
  private PythonSlices() {}

  public static Object slice(Object value, Object start, Object stop, Object step) {
    List<?> sequence =
        value instanceof String text ? text.codePoints().boxed().toList() : list(value);
    int size = sequence.size();
    BigInteger stride = step == null ? BigInteger.ONE : sliceIndex(step);
    if (stride.signum() == 0) throw error(PythonErrorType.VALUE_ERROR, "slice step cannot be zero");
    boolean reverse = stride.signum() < 0;
    int first =
        start == null ? (reverse ? size - 1 : 0) : Py.bound(sliceIndex(start), size, reverse);
    int last = stop == null ? (reverse ? -1 : size) : Py.bound(sliceIndex(stop), size, reverse);
    long distance = stride.abs().min(BigInteger.valueOf((long) size + 1)).longValue();
    if (reverse) distance = -distance;
    List<Object> result = new ArrayList<>();
    for (long i = first; reverse ? i > last : i < last; i += distance)
      result.add(sequence.get((int) i));
    if (value instanceof String) {
      StringBuilder text = new StringBuilder();
      for (Object cp : result) text.appendCodePoint((Integer) cp);
      return text.toString();
    }
    if (value instanceof PyTuple) return PyTuple.wrap(result);
    return result;
  }

  private static BigInteger sliceIndex(Object value) {
    try {
      return Py.index(value);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "slice indices must be integers or None or have an __index__ method");
    }
  }
}
