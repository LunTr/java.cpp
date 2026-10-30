package io.github.yaulun.javacpp.python;

/** Length protocol (the __len__/sq_length slot) used by {@link Py#len} and truthiness. */
public interface PySized {
  int pyLen();
}
