package io.github.yaulun.javacpp;

/** Observes model loading on a native thread. */
@FunctionalInterface
public interface LoadProgressListener {
  /** Returns false to cancel; progress is in [0, 1]; thrown exceptions cancel loading. */
  boolean onProgress(float progress);
}
