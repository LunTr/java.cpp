package io.github.yaulun.javacpp.python;

/**
 * Rich comparison protocol. {@link #pyCompare} returns a Boolean outcome or {@link
 * Py#NOT_IMPLEMENTED} to let the runtime try the reflected operation on the other operand.
 */
public interface PyComparable {
  Object pyCompare(Object other, Py.Op operation);
}
