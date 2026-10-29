package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;

/**
 * Mutable LlamaState fields from Python 0.3.36. Arrays and byte storage retain their supplied
 * references. Callers own synchronization, as with the original state object.
 */
public final class PythonLlamaState {
  private int[] inputIds;
  private float[][] scores;
  private int nTokens;
  private byte[] llamaState;
  private BigInteger llamaStateSize;
  private Object seed;

  /** Stores the six supplied fields without validation or copying, as LlamaState.__init__ does. */
  public PythonLlamaState(
      int[] inputIds,
      float[][] scores,
      int nTokens,
      byte[] llamaState,
      BigInteger llamaStateSize,
      long seed) {
    this.inputIds = inputIds;
    this.scores = scores;
    this.nTokens = nTokens;
    this.llamaState = llamaState;
    this.llamaStateSize = llamaStateSize;
    this.seed = BigInteger.valueOf(seed);
  }

  /** Stores an arbitrary-precision seed for PythonLlama save/load. */
  public PythonLlamaState(
      int[] inputIds,
      float[][] scores,
      int nTokens,
      byte[] llamaState,
      BigInteger llamaStateSize,
      BigInteger seed) {
    this.inputIds = inputIds;
    this.scores = scores;
    this.nTokens = nTokens;
    this.llamaState = llamaState;
    this.llamaStateSize = llamaStateSize;
    this.seed = seed;
  }

  /** Returns the input_ids reference. */
  public int[] inputIds() {
    return inputIds;
  }

  /** Replaces input_ids without copying. */
  public void setInputIds(int[] value) {
    inputIds = value;
  }

  /** Returns the scores reference. */
  public float[][] scores() {
    return scores;
  }

  /** Replaces scores without copying. */
  public void setScores(float[][] value) {
    scores = value;
  }

  /** Returns n_tokens. */
  public int nTokens() {
    return nTokens;
  }

  /** Replaces n_tokens. */
  public void setNTokens(int value) {
    nTokens = value;
  }

  /** Returns the llama_state bytes reference. */
  public byte[] llamaState() {
    return llamaState;
  }

  /** Replaces llama_state without copying. */
  public void setLlamaState(byte[] value) {
    llamaState = value;
  }

  /** Returns the arbitrary-precision llama_state_size field. */
  public BigInteger llamaStateSize() {
    return llamaStateSize;
  }

  /** Replaces llama_state_size; caches observe the change on their next size query. */
  public void setLlamaStateSize(BigInteger value) {
    llamaStateSize = value;
  }

  /** Returns seed. */
  public long seed() {
    return seedValue().longValueExact();
  }

  /** Replaces seed. */
  public void setSeed(long value) {
    seed = BigInteger.valueOf(value);
  }

  public BigInteger seedValue() {
    return PythonNumbers.integer(seed);
  }

  public Object seedObject() {
    return seed;
  }

  public PythonLlamaState(
      int[] inputIds, float[][] scores, int nTokens, byte[] data, BigInteger size, Object seed) {
    this.inputIds = inputIds;
    this.scores = scores;
    this.nTokens = nTokens;
    llamaState = data;
    llamaStateSize = size;
    this.seed = seed;
  }

  public void setSeedValue(BigInteger value) {
    seed = value;
  }
}
