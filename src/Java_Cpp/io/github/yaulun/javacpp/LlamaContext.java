package io.github.yaulun.javacpp;

import com.sun.jna.Pointer;
import io.github.yaulun.javacpp.exception.ContextOverflowException;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.InferenceException;
import io.github.yaulun.javacpp.exception.NativeFailure;
import io.github.yaulun.javacpp.internal.LlamaContextParams;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import io.github.yaulun.javacpp.internal.NativeLogBridge;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Single-sequence inference state. Each operation pins its parent model then acquires this context.
 *
 * <p>Concurrent operations and callback reentry raise IllegalStateException; close waits for active
 * generation. Clear the context after a listener or native generation failure before reusing it.
 */
public final class LlamaContext implements AutoCloseable {
  private final LlamaModel model;
  private final LlamaLibrary library;
  private final Pointer handle;
  private final Pointer memory;
  private final Pointer batch;
  private final int contextLength;
  private final int batchSize;
  private final boolean embeddings;
  private final int sequenceLimit;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile boolean closed;
  private boolean logitsReady;
  private int outputCount;
  private boolean needsClear;

  LlamaContext(LlamaModel model, ContextParams params) {
    this(model, params, null);
  }

  LlamaContext(LlamaModel model, java.util.Map<String, Object> options) {
    this(model, ContextParams.defaults(), options);
  }

  private LlamaContext(
      LlamaModel model, ContextParams params, java.util.Map<String, Object> options) {
    this.model = model;
    library = model.library();
    LlamaContextParams.ByValue nativeParams = library.llama_context_default_params();
    nativeParams.n_ctx = params.contextLength();
    nativeParams.n_batch = params.batchSize();
    nativeParams.n_ubatch = params.microBatchSize();
    nativeParams.n_seq_max = params.sequenceLimit();
    nativeParams.embeddings = (byte) (params.embeddings() ? 1 : 0);
    nativeParams.pooling_type = params.poolingType();
    nativeParams.kv_unified = (byte) (params.embeddings() ? 1 : nativeParams.kv_unified);
    nativeParams.n_threads = params.threads();
    nativeParams.n_threads_batch = params.threadsBatch();
    nativeParams.flash_attn_type = params.flashAttention().nativeValue();
    if (options != null) PythonNativeLlama.assign(nativeParams, options);
    embeddings = nativeParams.embeddings != 0;
    sequenceLimit = nativeParams.n_seq_max;
    handle = library.llama_init_from_model(model.handle(), nativeParams);
    if (handle == null) {
      throw new InferenceException(
          ErrorCode.E301,
          "llama_init_from_model returned NULL",
          NativeLogBridge.instance().recentProblems(),
          null,
          new NativeFailure("llama_init_from_model", 0));
    }
    Pointer allocatedBatch = null;
    try {
      memory = library.llama_get_memory(handle);
      allocatedBatch = library.llama_batch_ext_init(handle);
      if (memory == null || allocatedBatch == null) {
        throw failure(ErrorCode.E301, "Context has no memory/batch");
      }
      batch = allocatedBatch;
      contextLength = library.llama_n_ctx(handle);
      batchSize = library.llama_n_batch(handle);
      if (contextLength < 1 || batchSize < 1) {
        throw failure(ErrorCode.E301, "Invalid actual context dimensions");
      }
    } catch (RuntimeException | Error failure) {
      if (allocatedBatch != null) {
        library.llama_batch_ext_free(allocatedBatch);
      }
      library.llama_free(handle);
      throw failure;
    }
  }

  /** Returns the context capacity measured in tokens. */
  public int contextLength() {
    return guarded(() -> contextLength);
  }

  /** Returns the maximum number of tokens accepted by one native batch. */
  public int batchSize() {
    return guarded(() -> batchSize);
  }

  public boolean embeddingsEnabled() {
    return guarded(() -> embeddings);
  }

  public int sequenceLimit() {
    return guarded(() -> sequenceLimit);
  }

  public io.github.yaulun.javacpp.compat.python.PythonEmbeddingBackend embeddingBackend() {
    return new io.github.yaulun.javacpp.compat.python.PythonEmbeddingBackend() {
      private final java.util.List<int[]> pending = new java.util.ArrayList<>();
      private int decoded;

      public int dimensions() {
        return model.info().embedding();
      }

      public int batchSize() {
        return LlamaContext.this.batchSize();
      }

      public int sequenceLimit() {
        return LlamaContext.this.sequenceLimit();
      }

      public int poolingType() {
        return LlamaContext.this.poolingType();
      }

      public boolean enabled() {
        return embeddingsEnabled();
      }

      public void resetTimings() {
        guarded(
            () -> {
              library.llama_perf_context_reset(handle);
              return null;
            });
      }

      public void printTimings() {
        guarded(
            () -> {
              library.llama_perf_context_print(handle);
              return null;
            });
      }

      public void resetBatch() {
        pending.clear();
      }

      public void addSequence(int[] tokens, int sequence, boolean outputs) {
        pending.add(tokens);
      }

      public void clearMemory() {
        clear();
      }

      public void decode() {
        decoded = pending.stream().mapToInt(a -> a.length).sum();
        io.github.yaulun.javacpp.compat.python.PythonErrorBridge.run(
            io.github.yaulun.javacpp.compat.python.PythonCall.decode(),
            () -> decodeEmbedding(pending));
      }

      public double[] embeddings() {
        return guarded(
            () ->
                asDoubles(
                    library.llama_get_embeddings(handle).getFloatArray(0, decoded * dimensions())));
      }

      public double[] sequenceEmbedding(int sequence) {
        return guarded(
            () ->
                asDoubles(
                    library
                        .llama_get_embeddings_seq(handle, sequence)
                        .getFloatArray(0, dimensions())));
      }
    };
  }

  private static double[] asDoubles(float[] values) {
    double[] result = new double[values.length];
    for (int i = 0; i < values.length; i++) result[i] = values[i];
    return result;
  }

  private void decodeEmbedding(java.util.List<int[]> sequences) {
    guarded(
        () -> {
          library.llama_batch_ext_clear(batch);
          int total = 0;
          for (int seq = 0; seq < sequences.size(); seq++)
            for (int pos = 0; pos < sequences.get(seq).length; pos++) {
              int slot = library.llama_batch_ext_add_token(batch, seq, sequences.get(seq)[pos]);
              library.llama_batch_ext_set_pos(batch, slot, new int[] {pos});
              library.llama_batch_ext_set_output_logits(batch, slot, (byte) 1);
              total++;
            }
          if (total != 0) {
            int result = library.llama_process(handle, 1, batch);
            if (result != 0)
              throw new InferenceException(
                  ErrorCode.E303,
                  "llama_process returned " + result,
                  NativeLogBridge.instance().recentProblems(),
                  null,
                  new NativeFailure("llama_process", result));
          }
          return null;
        });
  }

  /** Returns the number of positions currently stored for the single sequence. */
  public int usedTokens() {
    return guarded(this::usedLocked);
  }

  /** Returns the remaining single-sequence context capacity in tokens. */
  public int remainingTokens() {
    return guarded(() -> contextLength - usedLocked());
  }

  /** Clears sequence memory, logits and the failed-generation marker. */
  public void clear() {
    guarded(
        () -> {
          library.llama_memory_clear(memory, (byte) 1);
          logitsReady = false;
          outputCount = 0;
          needsClear = false;
          return null;
        });
  }

  /** Validates and decodes tokens in contiguous-position batches. */
  public void decode(int[] tokens) {
    int[] copy = Objects.requireNonNull(tokens, "tokens").clone();
    guarded(
        () -> {
          ensureReady();
          decodeLocked(copy);
          return null;
        });
  }

  /** Samples once from valid last-position logits, accepting the sampled token exactly once. */
  public int sample(Sampler sampler) {
    Objects.requireNonNull(sampler, "sampler");
    return guarded(
        () -> {
          ensureReady();
          if (!logitsReady) {
            throw new IllegalStateException("Decode input before sampling");
          }
          sampler.acquire();
          try {
            return sampler.sampleLocked(handle, model);
          } finally {
            sampler.release();
          }
        });
  }

  /**
   * Generates text, propagating listener exceptions. All four StopReason values are normal returns.
   * A cancelled call before prefill returns zero prompt tokens and leaves memory unchanged.
   */
  public GenerationResult generate(
      int[] prompt, Sampler sampler, GenerationConfig config, TokenListener listener) {
    int[] copy = Objects.requireNonNull(prompt, "prompt").clone();
    Objects.requireNonNull(sampler, "sampler");
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(listener, "listener");
    return guarded(
        () -> {
          ensureReady();
          validateTokens(copy);
          if ((long) copy.length + 1 > contextLength - usedLocked()) {
            throw new ContextOverflowException(copy.length + 1, contextLength - usedLocked());
          }
          sampler.acquire();
          try {
            return generateLocked(copy, sampler, config, listener);
          } catch (RuntimeException | Error failure) {
            needsClear = true;
            throw failure;
          } finally {
            sampler.release();
          }
        });
  }

  /** Returns whether this native resource has been released. */
  public int poolingType() {
    return guarded(() -> library.llama_pooling_type(handle));
  }

  /** Removes sequence positions at and after position, including recurrent-model refusal. */
  public boolean removeSequence(int position) {
    return guarded(
        () -> {
          int used = usedLocked();
          boolean removed = library.llama_memory_seq_rm(memory, -1, position, -1) != 0;
          if (removed && position < used) {
            logitsReady = false;
            outputCount = 0;
          }
          return removed;
        });
  }

  /** Evaluates one explicit-position batch; outputLogits copies its rows separately. */
  public void evaluateAt(int[] tokens, int position, boolean logitsAll) {
    int[] copy = Objects.requireNonNull(tokens, "tokens").clone();
    guarded(
        () -> {
          ensureReady();
          if (copy.length == 0) return null;
          validateTokens(copy);
          if (position < 0
              || (long) position + copy.length > contextLength
              || copy.length > batchSize)
            throw new IllegalArgumentException("Explicit batch outside context dimensions");
          logitsReady = false;
          outputCount = 0;
          library.llama_batch_ext_clear(batch);
          for (int i = 0; i < copy.length; i++) {
            int slot = library.llama_batch_ext_add_token(batch, 0, copy[i]);
            if (slot < 0
                || library.llama_batch_ext_set_pos(batch, slot, new int[] {position + i}) == 0
                || library.llama_batch_ext_set_output_logits(
                        batch, slot, (byte) (logitsAll || i == copy.length - 1 ? 1 : 0))
                    == 0) {
              needsClear = true;
              throw failure(ErrorCode.E303, "Explicit batch rejected");
            }
          }
          int result = library.llama_process(handle, 1, batch);
          if (result != 0) {
            needsClear = true;
            throw new InferenceException(
                ErrorCode.E303,
                "llama_process returned " + result,
                NativeLogBridge.instance().recentProblems(),
                null,
                new NativeFailure("llama_process", result));
          }
          logitsReady = copy.length > 0;
          outputCount = logitsAll ? copy.length : 1;
          return null;
        });
  }

  /** Copies live output rows before the next decode. */
  public float[][] outputLogits(int rows) {
    return guarded(
        () -> {
          if (!logitsReady || rows < 0 || rows > outputCount)
            throw new IllegalStateException("Live logits required");
          float[][] output = new float[rows][];
          for (int i = 0; i < output.length; i++) {
            Pointer row = library.llama_get_logits_ith(handle, i);
            if (row == null) throw failure(ErrorCode.E303, "Missing output logits");
            output[i] = row.getFloatArray(0, model.vocab().size());
          }
          return output;
        });
  }

  public long stateSize() {
    return guarded(() -> library.llama_state_get_size(handle));
  }

  /** Copies state to a caller buffer and returns the actual native count. */
  public long copyState(byte[] target) {
    Objects.requireNonNull(target, "target");
    return guarded(() -> library.llama_state_get_data(handle, target, target.length));
  }

  /** Restores state; restored logits require evaluation before sampling. */
  public long restoreState(byte[] source) {
    byte[] copy = Objects.requireNonNull(source, "source").clone();
    return guarded(
        () -> {
          logitsReady = false;
          outputCount = 0;
          long count = library.llama_state_set_data(handle, copy, copy.length);
          needsClear = count != copy.length;
          return count;
        });
  }

  /** Returns whether this native resource has been released. */
  public boolean isClosed() {
    return closed;
  }

  /** Frees batch then context; repeated calls are harmless, including after parent close. */
  @Override
  public void close() {
    NativeLogBridge.ensureOutsideCallback();
    if (lock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Cannot close context from callback");
    }
    if (closed) {
      return;
    }
    try {
      model.acquire();
    } catch (IllegalStateException failure) {
      if (closed) {
        return;
      }
      throw failure;
    }
    try {
      lock.lock();
      try {
        if (closed) {
          return;
        }
        library.llama_batch_ext_free(batch);
        library.llama_free(handle);
        closed = true;
        model.unregister(this);
      } finally {
        lock.unlock();
      }
    } finally {
      model.release();
    }
  }

  Vocab vocab() {
    return model.vocab();
  }

  int samplePolicy(
      Pointer chain, LlamaModel owner, int index, java.util.function.Consumer<float[]> processor) {
    return guarded(
        () -> {
          if (owner != model)
            throw new IllegalArgumentException("Sampler belongs to another model");
          ensureReady();
          Pointer logits = library.llama_get_logits_ith(handle, index);
          float[] values = logits.getFloatArray(0, model.vocab().size());
          if (processor != null) processor.accept(values);
          try (com.sun.jna.Memory data = new com.sun.jna.Memory((long) values.length * 12)) {
            for (int i = 0; i < values.length; i++) {
              data.setInt((long) i * 12, i);
              data.setFloat((long) i * 12 + 4, values[i]);
              data.setFloat((long) i * 12 + 8, 0);
            }
            var candidates = new io.github.yaulun.javacpp.internal.LlamaTokenDataArray();
            candidates.data = data;
            candidates.size = values.length;
            candidates.selected = -1;
            library.llama_sampler_apply(chain, candidates);
            int token = candidates.data.getInt(candidates.selected * 12);
            library.llama_sampler_accept(chain, token);
            return token;
          }
        });
  }

  private int usedLocked() {
    return Math.max(0, library.llama_memory_seq_pos_max(memory, 0) + 1);
  }

  int setLora(Pointer adapter, float scale) {
    return guarded(
        () ->
            library.llama_set_adapters_lora(
                handle, new Pointer[] {adapter}, 1, new float[] {scale}));
  }

  private void ensureReady() {
    if (needsClear) {
      throw new IllegalStateException("Call clear() after a failed generation/decode");
    }
  }

  private void validateTokens(int[] tokens) {
    if (tokens.length == 0) {
      throw new IllegalArgumentException("tokens must not be empty");
    }
    Vocab vocab = model.vocab();
    for (int token : tokens) {
      vocab.checkToken(token);
    }
  }

  private void decodeLocked(int[] tokens) {
    validateTokens(tokens);
    int used = usedLocked();
    if ((long) used + tokens.length > contextLength) {
      throw new ContextOverflowException(tokens.length, contextLength - used);
    }
    logitsReady = false;
    for (int offset = 0; offset < tokens.length; offset += batchSize) {
      int end = Math.min(tokens.length, offset + batchSize);
      library.llama_batch_ext_clear(batch);
      for (int index = offset; index < end; index++) {
        int slot = library.llama_batch_ext_add_token(batch, 0, tokens[index]);
        if (slot < 0
            || library.llama_batch_ext_set_pos(batch, slot, new int[] {used + index}) == 0) {
          needsClear = true;
          throw failure(ErrorCode.E303, "Batch token/position rejected at " + index);
        }
        if (index == tokens.length - 1
            && library.llama_batch_ext_set_output_logits(batch, slot, (byte) 1) == 0) {
          needsClear = true;
          throw failure(ErrorCode.E303, "Output logits rejected");
        }
      }
      int result = library.llama_process(handle, 1, batch);
      if (result != 0) {
        needsClear = true;
        if (result == 1) {
          throw new ContextOverflowException(
              end - offset,
              contextLength - usedLocked(),
              new NativeFailure("llama_process", result));
        }
        throw new InferenceException(
            result == 2 ? ErrorCode.E304 : ErrorCode.E303,
            "llama_process returned " + result,
            NativeLogBridge.instance().recentProblems(),
            null,
            new NativeFailure("llama_process", result));
      }
    }
    logitsReady = true;
    outputCount = 1;
  }

  private GenerationResult generateLocked(
      int[] prompt, Sampler sampler, GenerationConfig config, TokenListener listener) {
    if (config.cancel().getAsBoolean()) {
      return new GenerationResult("", 0, 0, StopReason.CANCELLED, 0, 0);
    }
    long start = System.nanoTime();
    decodeLocked(prompt);
    long prefilled = System.nanoTime();
    StringBuilder text = new StringBuilder();
    Utf8StreamDecoder decoder = new Utf8StreamDecoder();
    Vocab vocab = model.vocab();
    int generated = 0;
    StopReason reason;
    while (true) {
      if (config.cancel().getAsBoolean() || Thread.currentThread().isInterrupted()) {
        reason = StopReason.CANCELLED;
        break;
      }
      int token = sampler.sampleLocked(handle, model);
      vocab.checkToken(token);
      if (vocab.isEog(token)) {
        reason = StopReason.END_OF_GENERATION;
        break;
      }
      emit(decoder.decode(vocab.tokenToBytes(token, true)), text, listener);
      generated++;
      if (generated >= config.maxTokens()) {
        reason = StopReason.MAX_TOKENS;
        break;
      }
      if (usedLocked() >= contextLength) {
        reason = StopReason.CONTEXT_FULL;
        break;
      }
      decodeLocked(new int[] {token});
    }
    // No extra listener invocation on an exceptional path: preserve the original listener failure.
    emit(decoder.finish(), text, listener);
    return new GenerationResult(
        text.toString(),
        prompt.length,
        generated,
        reason,
        prefilled - start,
        System.nanoTime() - prefilled);
  }

  private static void emit(String piece, StringBuilder text, TokenListener listener) {
    if (!piece.isEmpty()) {
      text.append(piece);
      listener.onToken(piece);
    }
  }

  private <T> T guarded(Supplier<T> action) {
    model.acquire();
    try {
      if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
        throw new IllegalStateException(
            "Context already in use; concurrent/reentrant access rejected");
      }
      try {
        if (closed) {
          throw new IllegalStateException("Context closed");
        }
        return action.get();
      } finally {
        lock.unlock();
      }
    } finally {
      model.release();
    }
  }

  private static InferenceException failure(ErrorCode code, String detail) {
    return new InferenceException(code, detail, NativeLogBridge.instance().recentProblems());
  }
}
