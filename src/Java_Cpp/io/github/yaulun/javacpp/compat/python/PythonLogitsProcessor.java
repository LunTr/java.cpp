package io.github.yaulun.javacpp.compat.python;

/** Callback corresponding to a Python LogitsProcessor for intc IDs and single-precision scores. */
@FunctionalInterface
public interface PythonLogitsProcessor {
  /** Applies a processor; returned score references are passed to the next processor unchanged. */
  float[] apply(int[] inputIds, float[] scores);
}
