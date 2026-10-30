package io.github.yaulun.javacpp.compat.python;

/**
 * C-level port for the pinned _internals.py wrappers. Method order, argument values and return
 * codes mirror the ctypes calls so a traced stub can certify the control flow without loading any
 * native library. Handles are opaque.
 */
public interface PythonInternalsPort {
  boolean exists(String path);

  /** Enters suppress_stdout_stderr(disable=verbose) before the native load call. */
  void suppressStdoutStderr(boolean disable);

  Object modelDefaultParams();

  Object modelLoadFromFile(String path, Object params);

  Object modelGetVocab(Object model);

  void modelFree(Object model);

  int modelNCtxTrain(Object model);

  /** Writes a NUL-terminated UTF-8 description into buffer. */
  void modelDesc(Object model, byte[] buffer);

  int modelMetaCount(Object model);

  int modelMetaKeyByIndex(Object model, int index, byte[] buffer);

  int modelMetaValStrByIndex(Object model, int index, byte[] buffer);

  int vocabBos(Object vocab);

  int tokenize(
      Object vocab, byte[] text, int[] tokens, int capacity, boolean addBos, boolean special);

  int tokenToPiece(Object vocab, int token, byte[] buffer, int lstrip, boolean special);

  Object contextDefaultParams();

  Object initFromModel(Object model, Object params);

  Object getMemory(Object context);

  void memoryClear(Object memory, boolean data);

  void contextFree(Object context);

  int decode(Object context, PythonInternalsBatch batch);

  PythonInternalsBatch batchInit(int nTokens, int embd, int nSeqMax);

  void batchFree(PythonInternalsBatch batch);

  Object samplerChainDefaultParams();

  Object samplerChainInit(Object params);

  void samplerChainAdd(Object chain, Object sampler);

  Object samplerChainRemove(Object chain, int index);

  Object samplerInitGreedy();

  Object samplerInitDist(long seed);

  Object samplerInitTopK(int k);

  /** Python float arguments are converted to c_float at the native boundary. */
  Object samplerInitTopP(double p, long minKeep);

  Object samplerInitMinP(double p, long minKeep);

  Object samplerInitTemp(double temperature);

  Object samplerInitPenalties(
      int nVocab, int penaltyLastN, double repeat, double freq, double present);

  /** Unsigned 32-bit seed as a nonnegative long. */
  long samplerGetSeed(Object sampler);

  int samplerSample(Object sampler, Object context, int index);

  void samplerFree(Object sampler);
}
