package io.github.yaulun.javacpp.compat.python;

/** Sampling-chain port; operations are emitted in original Python order. */
public interface PythonSamplerBuilder {
  void add(String operation, Object... arguments);

  PythonLlamaSampler build();
}
