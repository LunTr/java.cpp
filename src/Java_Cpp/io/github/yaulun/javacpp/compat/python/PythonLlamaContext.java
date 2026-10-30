package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonErrorBridge.error;

import io.github.yaulun.javacpp.python.PythonErrorType;
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

  public static Object defaultParams(PythonInternalsPort port) {
    return port.contextDefaultParams();
  }
}
