package io.github.yaulun.javacpp;

import com.sun.jna.Pointer;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import io.github.yaulun.javacpp.exception.NativeFailure;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import io.github.yaulun.javacpp.internal.LlamaModelParams;
import io.github.yaulun.javacpp.internal.NativeLogBridge;
import io.github.yaulun.javacpp.internal.NativeStrings;
import java.lang.ref.Reference;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Owns a native model and its child contexts. Read operations may run concurrently.
 *
 * <p>close waits for active operations and closes children before the model. Closing from a
 * callback raises IllegalStateException. Native model memory must be released with
 * try-with-resources.
 */
public final class LlamaModel implements AutoCloseable {
  private final LlamaLibrary library;
  private final Pointer handle;
  private final ModelParams params;
  private final Vocab vocab;
  private final ModelInfo info;
  private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock(true);
  private final Set<LlamaContext> contexts = ConcurrentHashMap.newKeySet();
  private final LlamaLibrary.ProgressCallback progressCallback;
  private volatile boolean closed;

  private LlamaModel(
      LlamaLibrary library,
      Pointer handle,
      Path file,
      ModelParams params,
      LlamaLibrary.ProgressCallback progressCallback) {
    this.library = library;
    this.handle = handle;
    this.params = params;
    this.progressCallback = progressCallback;
    Pointer vocabHandle = library.llama_model_get_vocab(handle);
    if (vocabHandle == null) {
      throw new ModelLoadException(
          ErrorCode.E205,
          "Model has no vocabulary",
          NativeLogBridge.instance().recentProblems(),
          null,
          new NativeFailure("llama_model_get_vocab", 0));
    }
    vocab = new Vocab(this, vocabHandle);
    String description =
        readString((buffer, size) -> library.llama_model_desc(handle, buffer, size))
            .orElse("unknown");
    info =
        new ModelInfo(
            metadata("general.architecture").orElse("unknown"),
            description,
            library.llama_model_n_layer(handle),
            library.llama_model_n_ctx_train(handle),
            library.llama_model_n_embd(handle),
            vocab.size(),
            library.llama_model_size(handle),
            library.llama_model_n_params(handle),
            library.llama_model_is_hybrid(handle) != 0,
            file);
  }

  /** Loads a completed local GGUF file; progress cancellation raises E206 and retains its cause. */
  public static LlamaModel load(Path file, ModelParams params) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(params, "params");
    Path absolute = file.toAbsolutePath().normalize();
    Checks.text(absolute.toString(), "file");
    GgufHeader.read(absolute);
    LlamaLibrary library = LlamaRuntime.get().library();
    LlamaModelParams.ByValue nativeParams = library.llama_model_default_params();
    nativeParams.n_gpu_layers = params.gpuLayers();
    nativeParams.main_gpu = params.mainGpu();
    nativeParams.vocab_only = NativeStrings.cBool(params.vocabOnly());
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    LlamaLibrary.ProgressCallback callback = null;
    if (params.progressListener() != null) {
      callback =
          (progress, data) -> {
            try {
              boolean keepGoing = NativeLogBridge.progress(params.progressListener(), progress);
              if (!keepGoing) {
                cancelled.set(true);
              }
              return NativeStrings.cBool(keepGoing);
            } catch (Throwable failure) {
              callbackFailure.compareAndSet(null, failure);
              cancelled.set(true);
              return 0;
            }
          };
      nativeParams.progress_callback = callback;
    }
    NativeLogBridge.instance().clearRecent();
    Pointer handle = library.llama_model_load_from_file(absolute.toString(), nativeParams);
    Reference.reachabilityFence(callback);
    if (handle == null || cancelled.get()) {
      if (handle != null) {
        library.llama_model_free(handle);
      }
      throw new ModelLoadException(
          cancelled.get() ? ErrorCode.E206 : ErrorCode.E205,
          absolute.toString(),
          NativeLogBridge.instance().recentProblems(),
          callbackFailure.get(),
          handle == null ? new NativeFailure("llama_model_load_from_file", 0) : null);
    }
    try {
      return new LlamaModel(library, handle, absolute, params, callback);
    } catch (RuntimeException | Error failure) {
      library.llama_model_free(handle);
      throw failure;
    }
  }

  /**
   * Creates a single-sequence context, requiring weights and a length within model training range.
   */
  public LlamaContext newContext(ContextParams params) {
    Objects.requireNonNull(params, "params");
    return withOpen(
        () -> {
          if (this.params.vocabOnly()) {
            throw new IllegalStateException("vocabOnly model has no weights");
          }
          if (params.contextLength() > info.contextTrain()) {
            throw new IllegalArgumentException(
                "contextLength exceeds model contextTrain=" + info.contextTrain());
          }
          LlamaContext context = new LlamaContext(this, params);
          contexts.add(context);
          return context;
        });
  }

  /** Returns the model-owned vocabulary, valid until this model is closed. */
  public Vocab vocab() {
    return withOpen(() -> vocab);
  }

  /** Returns a copied model metadata snapshot while this model is open. */
  public ModelInfo info() {
    return withOpen(() -> info);
  }

  /** Returns an immutable metadata value copied while the model is alive. */
  public Optional<String> metadata(String key) {
    Checks.text(key, "key");
    return withOpen(
        () ->
            readString(
                (buffer, size) -> library.llama_model_meta_val_str(handle, key, buffer, size)));
  }

  /** Copies all metadata in native order into an immutable map. */
  public Map<String, String> metadata() {
    return withOpen(
        () -> {
          Map<String, String> values = new LinkedHashMap<>();
          int count = library.llama_model_meta_count(handle);
          for (int index = 0; index < count; index++) {
            int current = index;
            String key =
                readString(
                        (buffer, size) ->
                            library.llama_model_meta_key_by_index(handle, current, buffer, size))
                    .orElseThrow();
            String value =
                readString(
                        (buffer, size) ->
                            library.llama_model_meta_val_str_by_index(
                                handle, current, buffer, size))
                    .orElseThrow();
            values.put(key, value);
          }
          return Collections.unmodifiableMap(values);
        });
  }

  /** Copies the default model chat template; an absent template yields an empty optional. */
  public Optional<String> chatTemplate() {
    return withOpen(() -> Optional.ofNullable(library.llama_model_chat_template(handle, null)));
  }

  /** Returns whether this native resource has been released. */
  public boolean isClosed() {
    return closed;
  }

  /** Idempotently waits for native operations, closes child contexts, then frees model memory. */
  @Override
  public void close() {
    NativeLogBridge.ensureOutsideCallback();
    if (lifetime.getReadHoldCount() > 0 || lifetime.isWriteLockedByCurrentThread()) {
      throw new IllegalStateException("Cannot close model from an active operation or callback");
    }
    lifetime.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      for (LlamaContext context : List.copyOf(contexts)) {
        context.close();
      }
      library.llama_model_free(handle);
      closed = true;
      Reference.reachabilityFence(progressCallback);
    } finally {
      lifetime.writeLock().unlock();
    }
  }

  void acquire() {
    NativeLogBridge.ensureOutsideCallback();
    lifetime.readLock().lock();
    if (closed) {
      lifetime.readLock().unlock();
      throw new IllegalStateException("Model closed");
    }
  }

  void release() {
    lifetime.readLock().unlock();
  }

  <T> T withOpen(Supplier<T> action) {
    acquire();
    try {
      return action.get();
    } finally {
      release();
    }
  }

  LlamaLibrary library() {
    return library;
  }

  Pointer handle() {
    return handle;
  }

  void unregister(LlamaContext context) {
    contexts.remove(context);
  }

  private Optional<String> readString(NativeTextReader reader) {
    byte[] buffer = new byte[256];
    int length = reader.read(buffer, buffer.length);
    if (length == -1) {
      return Optional.empty();
    }
    if (length < -1) {
      throw failure(ErrorCode.E205, "Metadata size=" + length, null);
    }
    if (length >= buffer.length) {
      buffer = new byte[Math.addExact(length, 1)];
      length = reader.read(buffer, buffer.length);
    }
    if (length < 0 || length >= buffer.length) {
      throw failure(ErrorCode.E205, "Unstable metadata length=" + length, null);
    }
    return Optional.of(NativeStrings.fromBytes(buffer, length));
  }

  private static ModelLoadException failure(ErrorCode code, String detail, Throwable cause) {
    return new ModelLoadException(code, detail, NativeLogBridge.instance().recentProblems(), cause);
  }

  @FunctionalInterface
  private interface NativeTextReader {
    int read(byte[] buffer, long size);
  }
}
