package io.github.yaulun.javacpp;

import java.util.Objects;

/** Immutable ContextParams; builders validate values before any native call. */
public final class ContextParams {
  private final int contextLength;
  private final int batchSize;
  private final int threads;
  private final int threadsBatch;
  private final FlashAttention flashAttention;
  private final boolean embeddings;
  private final int poolingType;
  private final int sequenceLimit;

  private ContextParams(Builder builder) {
    contextLength = builder.contextLength;
    batchSize = builder.batchSize;
    threads = builder.threads;
    threadsBatch = builder.threadsBatch == 0 ? builder.threads : builder.threadsBatch;
    flashAttention = builder.flashAttention;
    embeddings = builder.embeddings;
    poolingType = builder.poolingType;
    sequenceLimit = builder.sequenceLimit;
  }

  /** Creates a new builder with documented defaults. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns default parameters. */
  public static ContextParams defaults() {
    return builder().build();
  }

  /** Returns the context capacity measured in tokens. */
  public int contextLength() {
    return contextLength;
  }

  /** Returns the maximum number of tokens accepted by one native batch. */
  public int batchSize() {
    return batchSize;
  }

  /** Returns the number of CPU threads used for generation. */
  public int threads() {
    return threads;
  }

  /** Returns the number of CPU threads used for prompt prefill. */
  public int threadsBatch() {
    return threadsBatch;
  }

  /** Returns the selected flash attention policy. */
  public FlashAttention flashAttention() {
    return flashAttention;
  }

  public boolean embeddings() {
    return embeddings;
  }

  public int poolingType() {
    return poolingType;
  }

  public int sequenceLimit() {
    return sequenceLimit;
  }

  /** Returns the physical batch limit, capped at 512 tokens. */
  public int microBatchSize() {
    return Math.min(batchSize, 512);
  }

  /** Mutable builder; each built configuration is an independent immutable snapshot. */
  public static final class Builder {
    private Builder() {}

    private int contextLength = 4096;
    private int batchSize = 512;
    private int threads =
        Math.max(
            Math.min(2, Runtime.getRuntime().availableProcessors()),
            Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    private int threadsBatch = 0;
    private FlashAttention flashAttention = FlashAttention.AUTO;
    private boolean embeddings;
    private int poolingType = -1;
    private int sequenceLimit = 1;

    public Builder embeddings(boolean value) {
      embeddings = value;
      return this;
    }

    public Builder poolingType(int value) {
      poolingType = value;
      return this;
    }

    public Builder sequenceLimit(int value) {
      sequenceLimit = value;
      return this;
    }

    /** Sets contextLength; default: {@code 4096}. */
    public Builder contextLength(int value) {
      contextLength = Checks.range(value, 16, 262144, "contextLength");
      return this;
    }

    /** Sets batchSize; default: {@code 512}. */
    public Builder batchSize(int value) {
      batchSize = Checks.range(value, 1, 8192, "batchSize");
      return this;
    }

    /** Sets threads >= 1; the default uses up to eight processors, reserving half for the host. */
    public Builder threads(int value) {
      threads = Checks.range(value, 1, Integer.MAX_VALUE, "threads");
      return this;
    }

    /** Sets prefill threads >= 1; the default follows the generation thread count. */
    public Builder threadsBatch(int value) {
      threadsBatch = Checks.range(value, 1, Integer.MAX_VALUE, "threadsBatch");
      return this;
    }

    /** Sets flashAttention; default: {@code FlashAttention.AUTO}. */
    public Builder flashAttention(FlashAttention value) {
      flashAttention = Objects.requireNonNull(value, "flashAttention");
      return this;
    }

    /** Returns a validated immutable snapshot. */
    public ContextParams build() {
      return new ContextParams(this);
    }
  }
}
