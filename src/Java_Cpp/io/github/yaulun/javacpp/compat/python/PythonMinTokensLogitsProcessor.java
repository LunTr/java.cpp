package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonValues;
import java.math.BigInteger;

/** Stateful MinTokensLogitsProcessor for the original intc/single array types; use per sequence. */
public final class PythonMinTokensLogitsProcessor implements PythonLogitsProcessor {
  private BigInteger minTokens;
  private int tokenEos;
  private Integer promptTokens;

  /** Ports __init__, leaving prompt_tokens unset until the first call. */
  public PythonMinTokensLogitsProcessor(BigInteger minTokens, int tokenEos) {
    this.minTokens = minTokens;
    this.tokenEos = tokenEos;
  }

  /** Returns min_tokens. */
  public BigInteger minTokens() {
    return minTokens;
  }

  /** Replaces min_tokens without resetting prompt_tokens. */
  public void setMinTokens(BigInteger value) {
    minTokens = value;
  }

  /** Returns token_eos, including negative NumPy-style indices. */
  public int tokenEos() {
    return tokenEos;
  }

  /** Replaces token_eos. */
  public void setTokenEos(int value) {
    tokenEos = value;
  }

  /** Returns prompt_tokens, or null before the first call. */
  public Integer promptTokens() {
    return promptTokens;
  }

  /** Replaces prompt_tokens; null starts a new initial-length measurement. */
  public void setPromptTokens(Integer value) {
    promptTokens = value;
  }

  /** Ports __call__, modifying scores in place and returning exactly the supplied reference. */
  @Override
  public float[] apply(int[] inputIds, float[] scores) {
    if (inputIds == null)
      throw PythonValues.error(
          PythonErrorType.TYPE_ERROR, "object of type 'NoneType' has no len()");
    if (promptTokens == null) promptTokens = inputIds.length;
    if (BigInteger.valueOf((long) inputIds.length - promptTokens).compareTo(minTokens) < 0) {
      if (scores == null)
        throw PythonValues.error(
            PythonErrorType.TYPE_ERROR, "'NoneType' object does not support item assignment");
      long index = tokenEos < 0 ? (long) scores.length + tokenEos : tokenEos;
      if (index < 0 || index >= scores.length)
        throw PythonValues.error(
            PythonErrorType.INDEX_ERROR,
            "index " + tokenEos + " is out of bounds for axis 0 with size " + scores.length);
      scores[(int) index] = Float.NEGATIVE_INFINITY;
    }
    return scores;
  }
}
