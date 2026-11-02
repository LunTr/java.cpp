package io.github.yaulun.javacpp.compat.python;

import java.util.ArrayList;
import java.util.List;

/** Module-level helpers from _internals.py. */
public final class PythonInternals {
  private PythonInternals() {}

  /** Zero-norm embeddings pass through unchanged; otherwise each element is scaled by 1/norm. */
  public static List<Double> normalizeEmbedding(List<? extends Number> embedding) {
    double sumSquares = 0.0;
    for (Number value : embedding) {
      double v = value.doubleValue();
      sumSquares += v * v;
    }
    double norm = Math.sqrt(sumSquares);
    if (norm == 0.0) {
      List<Double> result = new ArrayList<>(embedding.size());
      for (Number value : embedding) {
        result.add(value.doubleValue());
      }
      return result;
    }
    List<Double> result = new ArrayList<>(embedding.size());
    for (Number value : embedding) {
      result.add(value.doubleValue() / norm);
    }
    return result;
  }
}
