package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonErrorBridge.error;

import io.github.yaulun.javacpp.python.PythonErrorType;
import java.util.List;
import java.util.Objects;

/** _internals.LlamaBatch: single-sequence token batch with ctypes field semantics. */
public final class PythonLlamaBatch {
  private final PythonInternalsPort port;
  private final int nTokens;
  private final int embd;
  private final int nSeqMax;
  private final boolean verbose;
  private final PythonExitStack exitStack = new PythonExitStack();
  private PythonInternalsBatch batch;
  private Object sampler;

  public PythonLlamaBatch(
      PythonInternalsPort port, int nTokens, int embd, int nSeqMax, boolean verbose) {
    this.port = Objects.requireNonNull(port, "port");
    this.nTokens = nTokens;
    this.embd = embd;
    this.nSeqMax = nSeqMax;
    this.verbose = verbose;
    this.sampler = null;
    PythonInternalsBatch created = port.batchInit(nTokens, embd, nSeqMax);
    if (created == null) {
      throw error(PythonErrorType.VALUE_ERROR, "Failed to create llama_batch");
    }
    this.batch = created;
    exitStack.callback(this::freeBatch);
  }

  private void freeBatch() {
    if (batch == null) {
      return;
    }
    port.batchFree(batch);
    batch = null;
  }

  public int embd() {
    return embd;
  }

  public int nSeqMax() {
    return nSeqMax;
  }

  public boolean verbose() {
    return verbose;
  }

  public Object sampler() {
    return sampler;
  }

  /** The native batch handle written by set_batch and consumed by decode. */
  public PythonInternalsBatch batch() {
    return batch;
  }

  public void close() {
    exitStack.close();
  }

  public int nTokens() {
    return batch.nTokens();
  }

  public void reset() {
    batch.nTokens(0);
  }

  /** Writes a seq 0 run at consecutive positions; the last logits flag is always set. */
  public void setBatch(List<Integer> tokens, int nPast, boolean logitsAll) {
    int count = tokens.size();
    batch.nTokens(count);
    for (int i = 0; i < count; i++) {
      batch.token(i, tokens.get(i));
      batch.pos(i, nPast + i);
      batch.seqId(i, 0, 0);
      batch.nSeqId(i, 1);
      batch.logits(i, logitsAll);
    }
    batch.logits(count - 1, true);
  }
}
