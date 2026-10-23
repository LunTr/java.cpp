package io.github.yaulun.javacpp.compat.python;

/** Python stopping callback whose return value is evaluated using Python truth rules. */
@FunctionalInterface
public interface PythonStoppingCriteria {
  /** Receives the original arrays; the result may be a boolean or another Python-shaped value. */
  Object test(int[] inputIds, float[] logits);
}
