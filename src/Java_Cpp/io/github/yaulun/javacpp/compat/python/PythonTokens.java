package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Token sequence operations from the pinned Python API. */
public final class PythonTokens {
  private PythonTokens() {}

  /** Ports Llama.longest_token_prefix; comparison stops at the first different token. */
  public static int longestTokenPrefix(List<BigInteger> first, List<BigInteger> second) {
    int count = Math.min(first.size(), second.size());
    int index = 0;
    while (index < count && Objects.equals(first.get(index), second.get(index))) index++;
    return index;
  }
}
