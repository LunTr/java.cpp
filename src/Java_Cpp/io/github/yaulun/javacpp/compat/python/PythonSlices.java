package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import java.math.BigInteger;
import java.util.*;

final class PythonSlices {
  private PythonSlices() {}

  static Object slice(Object value, Object start, Object stop, Object step) {
    List<?> sequence =
        value instanceof String text ? text.codePoints().boxed().toList() : list(value);
    int size = sequence.size();
    BigInteger stride = step == null ? BigInteger.ONE : PythonNumbers.index(step);
    if (stride.signum() == 0) throw error(PythonErrorType.VALUE_ERROR, "slice step cannot be zero");
    boolean reverse = stride.signum() < 0;
    int first =
        start == null ? (reverse ? size - 1 : 0) : bound(PythonNumbers.index(start), size, reverse);
    int last =
        stop == null ? (reverse ? -1 : size) : bound(PythonNumbers.index(stop), size, reverse);
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
    return result;
  }

  private static int bound(BigInteger value, int size, boolean reverse) {
    if (value.signum() < 0) value = value.add(BigInteger.valueOf(size));
    return value
        .max(BigInteger.valueOf(reverse ? -1 : 0))
        .min(BigInteger.valueOf(reverse ? size - 1 : size))
        .intValue();
  }
}
