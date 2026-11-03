package dev.javacpp.compat.python;

import java.util.List;

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

  int vocabType(Object vocab);

  double modelRopeFreqScaleTrain(Object model);

  String vocabGetText(Object vocab, int token);

  double vocabGetScore(Object vocab, int token);

  int vocabGetAttr(Object vocab, int token);

  int vocabEos(Object vocab);

  int vocabSep(Object vocab);

  int vocabNl(Object vocab);

  int vocabFimPre(Object vocab);

  int vocabFimSuf(Object vocab);

  int vocabFimMid(Object vocab);

  int vocabEot(Object vocab);

  boolean vocabGetAddBos(Object vocab);

  boolean vocabGetAddEos(Object vocab);

  Object contextDefaultParams();

  Object initFromModel(Object model, Object params);

  Object getMemory(Object context);

  void memoryClear(Object memory, boolean data);

  boolean memorySeqRm(Object memory, int seqId, int p0, int p1);

  void memorySeqCp(Object memory, int seqIdSrc, int seqIdDst, int p0, int p1);

  void memorySeqKeep(Object memory, int seqId);

  void memorySeqAdd(Object memory, int seqId, int p0, int p1, int shift);

  long stateGetSize(Object context);

  int encode(Object context, PythonInternalsBatch batch);

  void setNThreads(Object context, int nThreads, int nThreadsBatch);

  Object getLogits(Object context);

  Object getLogitsIth(Object context, int index);

  Object getEmbeddings(Object context);

  Object getEmbeddingsIth(Object context, int index);

  Object getEmbeddingsSeq(Object context, int seqId);

  int poolingType(Object context);

  void perfContextReset(Object context);

  void perfContextPrint(Object context);

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

  int samplerChainN(Object chain);

  void samplerAccept(Object sampler, int token);

  void samplerReset(Object sampler);

  Object samplerClone(Object sampler);

  Object samplerInitTypical(double p, long minKeep);

  Object samplerInitTempExt(double t, double delta, double exponent);

  Object samplerInitXtc(double p, double t, long minKeep, long seed);

  Object samplerInitTopNSigma(double n);

  Object samplerInitMirostat(int nVocab, long seed, double tau, double eta, int m);

  Object samplerInitMirostatV2(long seed, double tau, double eta);

  Object samplerInitGrammar(Object vocab, byte[] grammar, byte[] root);

  Object samplerInitGrammarLazyPatterns(
      Object vocab,
      byte[] grammar,
      byte[] root,
      List<byte[]> triggerPatterns,
      List<Integer> triggerTokens);

  Object samplerInitDry(
      Object vocab,
      double dryMultiplier,
      double dryBase,
      int dryAllowedLength,
      int dryPenaltyLastN,
      List<byte[]> seqBreakers);

  Object samplerInitLogitBias(int nVocab, int[] tokens, double[] biases);

  Object samplerInitInfill(Object vocab);

  /** llama_sampler_i() struct allocation. */
  Object newSamplerInterface();

  /** llama_sampler_i_<field>(arg) callback factory; arg is a callable or an int. */
  Object samplerIFunction(String field, Object arg);

  /** Attribute write on the sampler_i struct; not observable on the trace. */
  void samplerIAssign(Object samplerI, String field, Object value);

  /** llama_sampler() struct allocation. */
  Object newLlamaSampler();

  /** ctypes.pointer(target); the returned handle names its target. */
  Object pointerOf(Object target);

  /** Attribute write on the llama_sampler struct (iface/ctx). */
  void samplerAssign(Object sampler, String field, Object value);

  /** candidates_data backing storage for LlamaTokenDataArray. */
  Object candidatesData(int nVocab);

  /** llama_token_data_array(data=..., size=..., sorted=...) construction. */
  Object tokenDataArray(Object data, int size, boolean sorted);

  /** Writes id/logit/p fields into the candidates_data storage; not traced. */
  void candidatesStore(Object data, int[] ids, float[] logits, float[] probs);

  /** Attribute write on the candidates struct (sorted/size); not traced. */
  void candidatesAssign(Object candidates, String field, Object value);
}
