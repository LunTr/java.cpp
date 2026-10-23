package io.github.yaulun.javacpp;

import com.sun.jna.Pointer;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.InferenceException;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import io.github.yaulun.javacpp.internal.NativeLogBridge;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns a native sampler chain. Concurrent use, callback reentry and use after close are rejected.
 * Child samplers transfer ownership to the chain as soon as they are added.
 */
public final class Sampler implements AutoCloseable {
  /**
   * Native sentinel requesting a random seed; other supported seeds are unsigned 32-bit integers.
   */
  public static final long RANDOM = 4294967295L;

  private final LlamaLibrary library;
  private final Pointer handle;
  private final LlamaModel model;
  private final long seed;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile boolean closed;

  private Sampler(Vocab vocab, Builder builder) {
    model = vocab.model();
    library = model.library();
    seed = builder.seed;
    Pointer chain = library.llama_sampler_chain_init(library.llama_sampler_chain_default_params());
    if (chain == null) {
      throw failure();
    }
    try {
      if (builder.penaltyLastN > 0
          && (builder.repeatPenalty != 1.0f
              || builder.frequencyPenalty != 0
              || builder.presencePenalty != 0)) {
        add(
            chain,
            library.llama_sampler_init_penalties(
                vocab.size(),
                builder.penaltyLastN,
                builder.repeatPenalty,
                builder.frequencyPenalty,
                builder.presencePenalty));
      }
      if (builder.greedy || builder.temperature == 0) {
        add(chain, library.llama_sampler_init_greedy());
      } else {
        if (builder.topK > 0) {
          add(chain, library.llama_sampler_init_top_k(builder.topK));
        }
        add(chain, library.llama_sampler_init_top_p(builder.topP, 1));
        add(chain, library.llama_sampler_init_min_p(builder.minP, 1));
        add(chain, library.llama_sampler_init_temp(builder.temperature));
        add(chain, library.llama_sampler_init_dist((int) builder.seed));
      }
      handle = chain;
    } catch (RuntimeException | Error failure) {
      library.llama_sampler_free(chain);
      throw failure;
    }
  }

  /** Creates a builder with the default parameter values. */
  public static Builder builder() {
    return new Builder();
  }

  /** Creates a deterministic greedy sampler owned by the caller. */
  public static Sampler greedy(Vocab vocab) {
    return builder().greedy(true).build(vocab);
  }

  /** Resets penalties and random generator state before a fresh generation. */
  public void reset() {
    acquire();
    try {
      library.llama_sampler_reset(handle);
    } finally {
      release();
    }
  }

  /** Returns the configured seed; RANDOM delegates seed selection to the native sampler. */
  public long seed() {
    acquire();
    try {
      return seed;
    } finally {
      release();
    }
  }

  /** Returns whether this native resource has been released. */
  public boolean isClosed() {
    return closed;
  }

  /** Waits for active generation and frees the entire chain once. */
  @Override
  public void close() {
    NativeLogBridge.ensureOutsideCallback();
    if (lock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Cannot close sampler from callback");
    }
    lock.lock();
    try {
      if (!closed) {
        library.llama_sampler_free(handle);
        closed = true;
      }
    } finally {
      lock.unlock();
    }
  }

  void acquire() {
    NativeLogBridge.ensureOutsideCallback();
    if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
      throw new IllegalStateException("Sampler already in use");
    }
    if (closed) {
      lock.unlock();
      throw new IllegalStateException("Sampler closed");
    }
  }

  void release() {
    lock.unlock();
  }

  int sampleLocked(Pointer context, LlamaModel expectedModel) {
    if (model != expectedModel) {
      throw new IllegalArgumentException("Sampler belongs to another model");
    }
    int token = library.llama_sampler_sample(handle, context, -1);
    if (token < 0) {
      throw new InferenceException(
          ErrorCode.E303,
          "Sampler returned token=" + token,
          NativeLogBridge.instance().recentProblems());
    }
    return token;
  }

  private void add(Pointer chain, Pointer child) {
    if (child == null) {
      throw failure();
    }
    library.llama_sampler_chain_add(chain, child);
  }

  private static InferenceException failure() {
    return new InferenceException(
        ErrorCode.E501,
        "Native sampler allocation failed",
        NativeLogBridge.instance().recentProblems());
  }

  /** Validated sampling policy; all setters reject nonfinite or out-of-range values. */
  public static final class Builder {
    private float temperature = 0.8f;
    private int topK = 40;
    private float topP = 0.95f;
    private float minP = 0.05f;
    private float repeatPenalty = 1.0f;
    private float frequencyPenalty;
    private float presencePenalty;
    private int penaltyLastN = 64;
    private long seed = RANDOM;
    private boolean greedy;

    private Builder() {}

    /** Sets finite temperature >= 0; zero selects greedy. Default 0.8. */
    public Builder temperature(float value) {
      Checks.finite(value, "temperature");
      if (value < 0) {
        throw new IllegalArgumentException("temperature must be >= 0");
      }
      temperature = value;
      return this;
    }

    /** Sets top-k >= 0; zero disables top-k. Default 40. */
    public Builder topK(int value) {
      topK = Checks.range(value, 0, Integer.MAX_VALUE, "topK");
      return this;
    }

    /** Sets finite top-p in (0, 1]. Default 0.95. */
    public Builder topP(float value) {
      Checks.finite(value, "topP");
      if (value <= 0 || value > 1) {
        throw new IllegalArgumentException("topP must be in (0, 1]");
      }
      topP = value;
      return this;
    }

    /** Sets finite min-p in [0, 1]. Default 0.05. */
    public Builder minP(float value) {
      Checks.finite(value, "minP");
      if (value < 0 || value > 1) {
        throw new IllegalArgumentException("minP must be in [0, 1]");
      }
      minP = value;
      return this;
    }

    /** Sets a finite repeat penalty > 0. Default 1.0. */
    public Builder repeatPenalty(float value) {
      Checks.finite(value, "repeatPenalty");
      if (value <= 0) {
        throw new IllegalArgumentException("repeatPenalty must be > 0");
      }
      repeatPenalty = value;
      return this;
    }

    /** Sets finite frequency penalty. Default zero. */
    public Builder frequencyPenalty(float value) {
      frequencyPenalty = Checks.finite(value, "frequencyPenalty");
      return this;
    }

    /** Sets finite presence penalty. Default zero. */
    public Builder presencePenalty(float value) {
      presencePenalty = Checks.finite(value, "presencePenalty");
      return this;
    }

    /** Sets penalty history >= 0; zero disables penalties. Default 64. */
    public Builder penaltyLastN(int value) {
      penaltyLastN = Checks.range(value, 0, Integer.MAX_VALUE, "penaltyLastN");
      return this;
    }

    /** Sets a seed in [0, 4294967295]; RANDOM uses native entropy. */
    public Builder seed(long value) {
      if (value < 0 || value > RANDOM) {
        throw new IllegalArgumentException("seed must be in [0, 4294967295]");
      }
      seed = value;
      return this;
    }

    /** Selects greedy sampling while retaining configured penalties. */
    public Builder greedy(boolean value) {
      greedy = value;
      return this;
    }

    /** Allocates a chain using this vocabulary; the caller must close it. */
    public Sampler build(Vocab vocab) {
      Objects.requireNonNull(vocab, "vocab");
      return vocab.model().withOpen(() -> new Sampler(vocab, this));
    }
  }
}
