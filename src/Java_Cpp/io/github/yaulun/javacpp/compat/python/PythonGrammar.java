package io.github.yaulun.javacpp.compat.python;

/** Minimal llama_grammar.LlamaGrammar view used by _internals wrappers. */
public interface PythonGrammar {
  /** grammar._grammar source text (encoded to UTF-8 at the native boundary). */
  String grammar();

  /** grammar._root rule name. */
  String root();

  void reset();
}
