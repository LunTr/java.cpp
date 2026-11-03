package dev.javacpp.internal;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

/** llama_sampler_i.apply callback signature. */
public interface LlamaSamplerApply extends Callback {
  void invoke(Pointer sampler, Pointer candidates);
}
