package io.github.yaulun.javacpp.compat.python;

/**
 * Subscription and membership protocol (the __getitem__/__setitem__/__contains__ slots). Key and
 * value arguments arrive unconverted so implementations keep CPython duck-typing semantics.
 */
public interface PyItemAccess {
  Object pyGet(Object key);

  void pySet(Object key, Object value);

  boolean pyContains(Object key);
}
