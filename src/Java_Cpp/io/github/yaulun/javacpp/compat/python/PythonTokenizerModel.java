package io.github.yaulun.javacpp.compat.python;

/** Model-level byte codec; implementations retain ownership of their model resources. */
public interface PythonTokenizerModel {
  int[] tokenize(byte[] text, boolean addBos, boolean special);

  byte[] detokenize(int[] tokens, boolean special);
}
