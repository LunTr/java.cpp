package io.github.yaulun.javacpp.compat.python;

import java.util.List;
import io.github.yaulun.javacpp.python.Py;

/** Token sequence operations from the pinned Python API. */
public final class PythonTokens {
  private PythonTokens() {}

  /**
   * Ports Llama.longest_token_prefix; comparison stops at the first pair that is != under Python
   * equality, so elements may be arbitrary objects.
   */
  public static int longestTokenPrefix(List<?> first, List<?> second) {
    int count = Math.min(first.size(), second.size());
    int index = 0;
    while (index < count && Py.equal(first.get(index), second.get(index))) index++;
    return index;
  }
}
