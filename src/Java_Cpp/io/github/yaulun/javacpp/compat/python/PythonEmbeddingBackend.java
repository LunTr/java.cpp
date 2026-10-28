package io.github.yaulun.javacpp.compat.python;

/** Model-owned embedding batch operations used by the original Python embed algorithm. */
public interface PythonEmbeddingBackend {
  int dimensions();

  int batchSize();

  int sequenceLimit();

  int poolingType();

  boolean enabled();

  void resetTimings();

  void printTimings();

  void resetBatch();

  void addSequence(int[] tokens, int sequence, boolean allOutputs);

  void clearMemory();

  void decode();

  double[] embeddings();

  double[] sequenceEmbedding(int sequence);
}
