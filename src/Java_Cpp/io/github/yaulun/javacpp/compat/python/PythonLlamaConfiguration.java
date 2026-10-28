package io.github.yaulun.javacpp.compat.python;

import java.util.Map;
import java.util.function.Consumer;

/** Original constructor-configuration snapshot and reconstruction delegation. */
public final class PythonLlamaConfiguration {
  private final PythonLlama llama;
  private final Map<String, Object> values;
  private final Map<String, Object> modelParameters;
  private final Map<String, Object> contextParameters;
  private final Consumer<Map<String, Object>> initializer;

  public PythonLlamaConfiguration(
      PythonLlama llama,
      Map<String, Object> values,
      Map<String, Object> modelParameters,
      Map<String, Object> contextParameters,
      Consumer<Map<String, Object>> initializer) {
    this.llama = llama;
    this.values = values;
    this.modelParameters = modelParameters;
    this.contextParameters = contextParameters;
    this.initializer = initializer;
  }

  public Map<String, Object> getState() {
    values.put("_seed", llama.seed());
    values.put("_logits_all", llama.logitsAllEnabled());
    return PythonLlamaDefaults.snapshot(values, modelParameters, contextParameters);
  }

  public void setState(Map<String, Object> state) {
    initializer.accept(state);
  }
}
