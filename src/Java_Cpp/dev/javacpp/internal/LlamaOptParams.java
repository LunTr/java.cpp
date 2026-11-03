package dev.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * Python-mirrored layout: upstream llama-cpp-python 0.3.36 omits the trailing optimizer_type field
 * present in the b11669 C header; kept identical on purpose.
 */
@Structure.FieldOrder({
  "n_ctx_train",
  "param_filter",
  "param_filter_ud",
  "get_opt_pars",
  "get_opt_pars_ud"
})
public class LlamaOptParams extends Structure {
  public int n_ctx_train;
  public Pointer param_filter;
  public Pointer param_filter_ud;
  public Pointer get_opt_pars;
  public Pointer get_opt_pars_ud;

  /** C struct passed by value to llama_opt_init. */
  public static final class ByValue extends LlamaOptParams implements Structure.ByValue {}
}
