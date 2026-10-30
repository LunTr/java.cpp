package io.github.yaulun.javacpp.compat.python;

/**
 * Handle for a native llama_batch. Element indices resolve like ctypes arrays:
 * negative values address from the allocated capacity.
 */
public interface PythonInternalsBatch {
  int capacity();

  int nTokens();

  void nTokens(int value);

  void token(int index, int value);

  void pos(int index, int value);

  void seqId(int index, int slot, int value);

  void nSeqId(int index, int value);

  void logits(int index, boolean value);
}
