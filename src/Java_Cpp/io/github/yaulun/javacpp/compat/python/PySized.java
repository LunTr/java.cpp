package io.github.yaulun.javacpp.compat.python;

/** Length protocol (the __len__/sq_length slot) used by {@link Py#len} and truthiness. */
public interface PySized {
  int pyLen();
}
