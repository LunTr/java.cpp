package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonErrorBridge.error;

import io.github.yaulun.javacpp.python.PythonErrorType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** _internals.LlamaSamplingContext dataclass: prev/cur history plus grammar reset. */
public final class PythonLlamaSamplingContext {
  private final PythonLlamaSamplingParams params;
  private final float[] mirostatMu;
  private PythonGrammar grammar;
  private List<Integer> prev;
  private final List<Object> cur;

  public PythonLlamaSamplingContext() {
    this(new PythonLlamaSamplingParams());
  }

  public PythonLlamaSamplingContext(PythonLlamaSamplingParams params) {
    this.params = params;
    this.mirostatMu = new float[] {0.0f};
    this.grammar = null;
    this.prev = new ArrayList<>();
    this.cur = new ArrayList<>();
  }

  private PythonLlamaSamplingContext(
      PythonLlamaSamplingParams params,
      float[] mirostatMu,
      PythonGrammar grammar,
      List<Integer> prev,
      List<Object> cur) {
    this.params = params;
    this.mirostatMu = mirostatMu;
    this.grammar = grammar;
    this.prev = prev;
    this.cur = cur;
  }

  /** reset rebinds prev/cur to fresh lists and resets the grammar when present. */
  public void reset() {
    prev = new ArrayList<>();
    cur.clear();
    if (grammar != null) {
      grammar.reset();
    }
  }

  /** cp shares params, mirostat_mu and grammar but copies prev and cur. */
  public PythonLlamaSamplingContext cp() {
    return new PythonLlamaSamplingContext(
        params, mirostatMu, grammar, new ArrayList<>(prev), new ArrayList<>(cur));
  }

  public Integer last() {
    return prev.isEmpty() ? null : prev.get(prev.size() - 1);
  }

  /** prev[-n:] detokenized through the context's model, then UTF-8 decoded. */
  public String prevStr(PythonLlamaContext ctxMain, int n) {
    int start = Math.max(0, prev.size() - n);
    byte[] text =
        ctxMain.model().detokenize(new ArrayList<>(prev.subList(start, prev.size())), false);
    return new String(text, StandardCharsets.UTF_8);
  }

  /** Deprecated in the original; raises NotImplementedError unconditionally. */
  public Object sample(PythonLlamaContext ctxMain, int idx, Object logitsArray) {
    throw error(
        PythonErrorType.NOT_IMPLEMENTED_ERROR,
        "LlamaSamplingContext.sample is deprecated, use LlamaSampler instead");
  }

  public void accept(PythonLlamaContext ctxMain, int id, boolean applyGrammar) {
    prev.add(id);
  }

  public PythonLlamaSamplingParams params() {
    return params;
  }

  public float[] mirostatMu() {
    return mirostatMu;
  }

  public PythonGrammar grammar() {
    return grammar;
  }

  public void setGrammar(PythonGrammar grammar) {
    this.grammar = grammar;
  }

  public List<Integer> prev() {
    return prev;
  }

  public List<Object> cur() {
    return cur;
  }
}
