package io.github.yaulun.javacpp.compat.python;

/**
 * Attribute protocol for duck-typed objects. {@link Py#getAttr} delegates to {@link #attr};
 * implementations either return the attribute value, return {@link Py#ABSENT} so the caller
 * produces the canonical AttributeError, or throw {@link PythonException} themselves.
 */
public interface PyAttributed {
  Object attr(String name);

  /** Python class name used in error text; defaults to the Java simple name. */
  default String pyType() {
    return getClass().getSimpleName();
  }
}
