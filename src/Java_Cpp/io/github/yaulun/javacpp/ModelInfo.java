package io.github.yaulun.javacpp;

import java.nio.file.Path;

/** Immutable model metadata snapshot; native resource ownership stays with LlamaModel. */
public record ModelInfo(
    String architecture,
    String description,
    int layers,
    int contextTrain,
    int embedding,
    int vocabSize,
    long sizeBytes,
    long parameters,
    boolean hybrid,
    Path file) {
  /** Returns a readable summary with model size in MiB. */
  public String summary() {
    return architecture
        + " · "
        + layers
        + " layers · ctx "
        + contextTrain
        + " · vocab "
        + vocabSize
        + " · "
        + sizeBytes / (1024 * 1024)
        + " MiB";
  }
}
