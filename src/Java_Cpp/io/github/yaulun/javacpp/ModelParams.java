package io.github.yaulun.javacpp;

/** Immutable ModelParams; builders validate values before any native call. */
public final class ModelParams {
  private final int gpuLayers;
  private final int mainGpu;
  private final boolean vocabOnly;
  private final LoadProgressListener progressListener;

  private ModelParams(Builder builder) {
    gpuLayers = builder.gpuLayers;
    mainGpu = builder.mainGpu;
    vocabOnly = builder.vocabOnly;
    progressListener = builder.progressListener;
  }

  /** Creates a new builder with documented defaults. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns default parameters. */
  public static ModelParams defaults() {
    return builder().build();
  }

  /** Returns GPU layer count; -1 selects all available layers, zero selects CPU. */
  public int gpuLayers() {
    return gpuLayers;
  }

  /** Returns the zero-based main GPU index. */
  public int mainGpu() {
    return mainGpu;
  }

  /** Returns whether loading omits model weights. */
  public boolean vocabOnly() {
    return vocabOnly;
  }

  /** Returns the optional loading observer; null uses native default progress reporting. */
  public LoadProgressListener progressListener() {
    return progressListener;
  }

  /** Mutable builder; each built configuration is an independent immutable snapshot. */
  public static final class Builder {
    private Builder() {}

    private int gpuLayers = -1;
    private int mainGpu = 0;
    private boolean vocabOnly = false;
    private LoadProgressListener progressListener = null;

    /** Sets gpuLayers; default: {@code -1}. */
    public Builder gpuLayers(int value) {
      gpuLayers = Checks.range(value, -1, Integer.MAX_VALUE, "gpuLayers");
      return this;
    }

    /** Sets mainGpu; default: {@code 0}. */
    public Builder mainGpu(int value) {
      mainGpu = Checks.range(value, 0, Integer.MAX_VALUE, "mainGpu");
      return this;
    }

    /** Sets vocabOnly; default: {@code false}. */
    public Builder vocabOnly(boolean value) {
      vocabOnly = value;
      return this;
    }

    /** Sets progressListener; default: {@code null}. */
    public Builder progressListener(LoadProgressListener value) {
      progressListener = value;
      return this;
    }

    /** Returns a validated immutable snapshot. */
    public ModelParams build() {
      return new ModelParams(this);
    }
  }
}
