package io.github.yaulun.javacpp.compat.python;

import java.util.List;
import java.util.Objects;

/** Python llama_cpp.py compatibility wrappers with real function bodies. */
public final class PythonLlamaCpp {
  /** Injectable llama_cpp boundary so contract tests replay without the native library. */
  public interface LlamaCppAccess {
    Object loadModelFromFile(Object pathModel, Object params);

    Object freeModel(Object model);

    Object newContextWithModel(Object model, Object params);

    Object nCtxTrain(Object model);

    Object nEmbd(Object model);

    Object nLayer(Object model);

    Object nHead(Object model);

    Object nVocab(Object vocab);

    Object getStateSize(Object ctx);

    Object copyStateData(Object ctx, Object dst);

    Object setStateData(Object ctx, Object src);

    Object setAdaptersLora(Object ctx, Object adapters, Object nAdapters, Object scales);

    Object setAdapterCvec(
        Object ctx, Object data, Object len, Object nEmbd, Object ilStart, Object ilEnd);

    Object loadSessionFile(
        Object ctx,
        Object pathSession,
        Object tokensOut,
        Object nTokenCapacity,
        Object nTokenCountOut);

    Object saveSessionFile(Object ctx, Object pathSession, Object tokens, Object nTokenCount);
  }

  private final LlamaCppAccess access;
  private final PythonWarnings warnings;

  public PythonLlamaCpp(LlamaCppAccess access, PythonWarnings warnings) {
    this.access = Objects.requireNonNull(access);
    this.warnings = Objects.requireNonNull(warnings);
  }

  /** Deprecated wrapper renamed upstream to llama_model_load_from_file. */
  public Object loadModelFromFile(Object pathModel, Object params, PythonWarningLocation location) {
    warnings.deprecated(
        "llama_load_model_from_file", "use llama_model_load_from_file instead", location);
    return access.loadModelFromFile(pathModel, params);
  }

  /** Deprecated wrapper renamed upstream to llama_model_free. */
  public Object freeModel(Object model, PythonWarningLocation location) {
    warnings.deprecated("llama_free_model", "use llama_model_free instead", location);
    return access.freeModel(model);
  }

  /** Deprecated wrapper renamed upstream to llama_init_from_model. */
  public Object newContextWithModel(Object model, Object params, PythonWarningLocation location) {
    warnings.deprecated(
        "llama_new_context_with_model", "use llama_init_from_model instead", location);
    return access.newContextWithModel(model, params);
  }

  /** Deprecated wrapper renamed upstream to llama_model_n_ctx_train. */
  public Object nCtxTrain(Object model, PythonWarningLocation location) {
    warnings.deprecated("llama_n_ctx_train", "use llama_model_n_ctx_train instead", location);
    return access.nCtxTrain(model);
  }

  /** Deprecated wrapper renamed upstream to llama_model_n_embd. */
  public Object nEmbd(Object model, PythonWarningLocation location) {
    warnings.deprecated("llama_n_embd", "use llama_model_n_embd instead", location);
    return access.nEmbd(model);
  }

  /** Deprecated wrapper renamed upstream to llama_model_n_layer. */
  public Object nLayer(Object model, PythonWarningLocation location) {
    warnings.deprecated("llama_n_layer", "use llama_model_n_layer instead", location);
    return access.nLayer(model);
  }

  /** Deprecated wrapper renamed upstream to llama_model_n_head. */
  public Object nHead(Object model, PythonWarningLocation location) {
    warnings.deprecated("llama_n_head", "use llama_model_n_head instead", location);
    return access.nHead(model);
  }

  /** Deprecated wrapper renamed upstream to llama_vocab_n_tokens. */
  public Object nVocab(Object vocab, PythonWarningLocation location) {
    warnings.deprecated("llama_n_vocab", "use llama_vocab_n_tokens instead", location);
    return access.nVocab(vocab);
  }

  /** Deprecated wrapper renamed upstream to llama_state_get_size. */
  public Object getStateSize(Object ctx, PythonWarningLocation location) {
    warnings.deprecated("llama_get_state_size", "use llama_state_get_size instead", location);
    return access.getStateSize(ctx);
  }

  /** Deprecated wrapper renamed upstream to llama_state_get_data. */
  public Object copyStateData(Object ctx, Object dst, PythonWarningLocation location) {
    warnings.deprecated("llama_copy_state_data", "use llama_state_get_data instead", location);
    return access.copyStateData(ctx, dst);
  }

  /** Deprecated wrapper renamed upstream to llama_state_set_data. */
  public Object setStateData(Object ctx, Object src, PythonWarningLocation location) {
    warnings.deprecated("llama_set_state_data", "use llama_state_set_data instead", location);
    return access.setStateData(ctx, src);
  }

  /** Python-only wrapper packing single-element arrays for llama_set_adapters_lora. */
  public Object setAdapterLora(
      Object ctx, Object adapter, Object scale, PythonWarningLocation location) {
    warnings.deprecated("llama_set_adapter_lora", "use llama_set_adapters_lora instead", location);
    return access.setAdaptersLora(ctx, List.of(adapter), 1, List.of(scale));
  }

  /** Python-only wrapper forwarding to llama_set_adapter_cvec. */
  public Object applyAdapterCvec(
      Object ctx,
      Object data,
      Object len,
      Object nEmbd,
      Object ilStart,
      Object ilEnd,
      PythonWarningLocation location) {
    warnings.deprecated("llama_apply_adapter_cvec", "use llama_set_adapter_cvec instead", location);
    return access.setAdapterCvec(ctx, data, len, nEmbd, ilStart, ilEnd);
  }

  /** Deprecated wrapper renamed upstream to llama_state_load_file. */
  public Object loadSessionFile(
      Object ctx,
      Object pathSession,
      Object tokensOut,
      Object nTokenCapacity,
      Object nTokenCountOut,
      PythonWarningLocation location) {
    warnings.deprecated("llama_load_session_file", "use llama_state_load_file instead", location);
    return access.loadSessionFile(ctx, pathSession, tokensOut, nTokenCapacity, nTokenCountOut);
  }

  /** Deprecated wrapper renamed upstream to llama_state_save_file. */
  public Object saveSessionFile(
      Object ctx,
      Object pathSession,
      Object tokens,
      Object nTokenCount,
      PythonWarningLocation location) {
    warnings.deprecated("llama_save_session_file", "use llama_state_save_file instead", location);
    return access.saveSessionFile(ctx, pathSession, tokens, nTokenCount);
  }
}
