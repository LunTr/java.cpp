package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonErrorBridge.error;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.util.Objects;

/** _internals.LlamaContext: context lifetime, memory check and llama_decode return contract. */
public final class PythonLlamaContext {
  private final PythonInternalsPort port;
  private final PythonLlamaModel model;
  private final Object params;
  private final boolean verbose;
  private final PythonExitStack exitStack = new PythonExitStack();
  private Object ctx;
  private final Object memory;
  private Object sampler;

  /**
   * @param params the caller-owned llama_context_params value, passed through unchanged.
   */
  public PythonLlamaContext(
      PythonInternalsPort port, PythonLlamaModel model, Object params, boolean verbose) {
    this.port = Objects.requireNonNull(port, "port");
    this.model = Objects.requireNonNull(model, "model");
    this.params = params;
    this.verbose = verbose;
    this.sampler = null;
    Object created = port.initFromModel(model.model(), params);
    if (created == null) {
      throw error(PythonErrorType.VALUE_ERROR, "Failed to create llama_context");
    }
    this.ctx = created;
    this.memory = port.getMemory(created);
    exitStack.callback(this::freeCtx);
    model.exitStack().callback(this::close);
  }

  private void freeCtx() {
    if (ctx == null) {
      return;
    }
    port.contextFree(ctx);
    ctx = null;
  }

  public PythonLlamaModel model() {
    return model;
  }

  public Object params() {
    return params;
  }

  public boolean verbose() {
    return verbose;
  }

  public Object context() {
    return ctx;
  }

  public Object memory() {
    return memory;
  }

  public Object sampler() {
    return sampler;
  }

  public void close() {
    exitStack.close();
  }

  /** Embedding models without memory return early, as in the original. */
  public void kvCacheClear() {
    if (memory == null) {
      return;
    }
    port.memoryClear(memory, true);
  }

  /** Nonzero return codes raise RuntimeError with the raw code in the message. */
  public void decode(PythonLlamaBatch batch) {
    int returnCode = port.decode(ctx, batch.batch());
    if (returnCode != 0) {
      throw error(PythonErrorType.RUNTIME_ERROR, "llama_decode returned " + returnCode);
    }
  }

  public int poolingType() {
    return port.poolingType(ctx);
  }

  private void requireMemory() {
    if (memory == null) {
      throw error(PythonErrorType.ASSERTION_ERROR, "Memory is not initialized");
    }
  }

  /** Negative seq_id clamps to 0, matching the original ternary. */
  public boolean kvCacheSeqRm(int seqId, int p0, int p1) {
    requireMemory();
    return port.memorySeqRm(memory, seqId >= 0 ? seqId : 0, p0, p1);
  }

  public void kvCacheSeqCp(int seqIdSrc, int seqIdDst, int p0, int p1) {
    requireMemory();
    port.memorySeqCp(memory, seqIdSrc, seqIdDst, p0, p1);
  }

  public void kvCacheSeqKeep(int seqId) {
    requireMemory();
    port.memorySeqKeep(memory, seqId);
  }

  /** The original implements shift through llama_memory_seq_add. */
  public void kvCacheSeqShift(int seqId, int p0, int p1, int shift) {
    requireMemory();
    port.memorySeqAdd(memory, seqId, p0, p1, shift);
  }

  public long getStateSize() {
    return port.stateGetSize(ctx);
  }

  /** Nonzero return codes raise RuntimeError with the raw code in the message. */
  public void encode(PythonLlamaBatch batch) {
    int returnCode = port.encode(ctx, batch.batch());
    if (returnCode != 0) {
      throw error(PythonErrorType.RUNTIME_ERROR, "llama_encode returned " + returnCode);
    }
  }

  public void setNThreads(int nThreads, int nThreadsBatch) {
    port.setNThreads(ctx, nThreads, nThreadsBatch);
  }

  public Object getLogits() {
    return port.getLogits(ctx);
  }

  public Object getLogitsIth(int i) {
    return port.getLogitsIth(ctx, i);
  }

  public Object getEmbeddings() {
    return port.getEmbeddings(ctx);
  }

  public Object getEmbeddingsIth(int i) {
    return port.getEmbeddingsIth(ctx, i);
  }

  public Object getEmbeddingsSeq(int seqId) {
    return port.getEmbeddingsSeq(ctx, seqId);
  }

  public void resetTimings() {
    port.perfContextReset(ctx);
  }

  public void printTimings() {
    port.perfContextPrint(ctx);
  }

  private static PythonException deprecated(String name) {
    return error(
        PythonErrorType.NOT_IMPLEMENTED_ERROR, name + " is deprecated, use LlamaSampler instead");
  }

  public void setRngSeed(int seed) {
    throw deprecated("set_rng_seed");
  }

  public void sampleRepetitionPenalties(
      Object candidates,
      Object lastTokensData,
      int penaltyLastN,
      double penaltyRepeat,
      double penaltyFreq,
      double penaltyPresent) {
    throw deprecated("sample_repetition_penalties");
  }

  public void sampleSoftmax(Object candidates) {
    throw deprecated("sample_softmax");
  }

  public void sampleTopK(Object candidates, int k, int minKeep) {
    throw deprecated("sample_top_k");
  }

  public void sampleTopP(Object candidates, double p, int minKeep) {
    throw deprecated("sample_top_p");
  }

  public void sampleMinP(Object candidates, double p, int minKeep) {
    throw deprecated("sample_min_p");
  }

  public void sampleTypical(Object candidates, double p, int minKeep) {
    throw deprecated("sample_typical");
  }

  public void sampleTemp(Object candidates, double temp) {
    throw deprecated("sample_temp");
  }

  public void sampleGrammar(Object candidates, Object grammar) {
    throw deprecated("sample_grammar");
  }

  public void sampleTokenMirostat(Object candidates, double tau, double eta, int m, Object mu) {
    throw deprecated("sample_token_mirostat");
  }

  public void sampleTokenMirostatV2(Object candidates, double tau, double eta, Object mu) {
    throw deprecated("sample_token_mirostat_v2");
  }

  public int sampleTokenGreedy(Object candidates) {
    throw deprecated("sample_token_greedy");
  }

  public int sampleToken(Object candidates) {
    throw deprecated("sample_token");
  }

  public void grammarAcceptToken(Object grammar, int token) {
    throw deprecated("grammar_accept_token");
  }

  public static Object defaultParams(PythonInternalsPort port) {
    return port.contextDefaultParams();
  }
}
