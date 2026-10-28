package io.github.yaulun.javacpp.compat.python;

/** Caller-supplied tokenizer used by the high-level Python Llama facade. */
public interface PythonTokenizer {
  int[] tokenize(byte[] text, boolean addBos, boolean special);

  byte[] detokenize(int[] tokens, int[] previousTokens, boolean special);
}
