package io.github.yaulun.javacpp.python;

import java.util.Iterator;

/**
 * Python iterator protocol: {@link #next()} raises a {@link PythonException} of type {@link
 * PythonErrorType#STOP_ITERATION} when exhausted instead of returning a sentinel, and {@link #send}
 * carries generator input for implementations that support it.
 */
public interface PyIterator extends Iterator<Object> {
  /**
   * Returns the next yielded value or throws {@link PythonErrorType#STOP_ITERATION} when the
   * iteration is finished. {@link #hasNext()} stays advisory for Java callers.
   */
  @Override
  Object next();

  /**
   * Sends a value into the iterator. Plain iterators have no send slot in Python, so the default
   * raises the same AttributeError CPython reports.
   */
  default Object send(Object value) {
    throw Py.error(
        PythonErrorType.ATTRIBUTE_ERROR,
        "'" + Py.typeName(this) + "' object has no attribute 'send'");
  }
}
