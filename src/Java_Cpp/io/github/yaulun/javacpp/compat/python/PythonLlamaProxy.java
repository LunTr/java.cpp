package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Ports server/model.py LlamaProxy container methods. Model settings are a port (alias/dump/
 * validate); model loading and close go through the loader port. __call__ falls back to the default
 * alias for missing models and closes the current model before loading a new one.
 */
public final class PythonLlamaProxy {
  /** ModelSettings port. */
  public interface ModelSettings {
    String modelAlias();

    void setModelAlias(String alias);

    String modelName();

    Object modelDump();
  }

  /** Loaded llama model port. */
  public interface LlamaModel {
    void close();
  }

  /** Settings validation port for __setitem__ string/bytes payloads. */
  public interface SettingsValidator {
    ModelSettings validateJson(String json);
  }

  private final Map<String, ModelSettings> modelSettingsDict = new LinkedHashMap<>();
  private LlamaModel currentModel;
  private String currentModelAlias;
  private final ModelSettings defaultModelSettings;
  private final String defaultModelAlias;
  private final Function<ModelSettings, LlamaModel> loader;

  public PythonLlamaProxy(List<ModelSettings> models, Function<ModelSettings, LlamaModel> loader) {
    if (models == null || models.isEmpty()) {
      throw Py.error(PythonErrorType.ASSERTION_ERROR, "No models provided!");
    }
    this.loader = loader;
    for (ModelSettings model : models) {
      if (model.modelAlias() == null) {
        model.setModelAlias(model.modelName());
      }
      modelSettingsDict.put(model.modelAlias(), model);
    }
    defaultModelSettings = models.get(0);
    defaultModelAlias = defaultModelSettings.modelAlias();
    currentModel = loader.apply(defaultModelSettings);
    currentModelAlias = defaultModelAlias;
  }

  /** Ports __call__. */
  public LlamaModel call(String model) {
    if (model == null) {
      model = defaultModelAlias;
    }
    if (!modelSettingsDict.containsKey(model)) {
      model = defaultModelAlias;
    }
    if (model.equals(currentModelAlias) && currentModel != null) {
      return currentModel;
    }
    if (currentModel != null) {
      currentModel.close();
    }
    currentModel = null;
    ModelSettings settings = modelSettingsDict.get(model);
    currentModel = loader.apply(settings);
    currentModelAlias = model;
    return currentModel;
  }

  /** Ports __getitem__: model_dump() result of the stored settings. */
  public Object get(String model) {
    ModelSettings settings = modelSettingsDict.get(model);
    if (settings == null) {
      throw Py.error(PythonErrorType.KEY_ERROR, "'" + model + "'");
    }
    return settings.modelDump();
  }

  /** Ports __setitem__: str/bytes payloads validate through the port. */
  public void set(String model, Object settings, SettingsValidator validator) {
    ModelSettings value;
    if (settings instanceof String json) {
      value = validator.validateJson(json);
    } else if (settings instanceof byte[] bytes) {
      value = validator.validateJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    } else {
      value = (ModelSettings) settings;
    }
    modelSettingsDict.put(model, value);
  }

  /** Ports __iter__: yields stored aliases in insertion order. */
  public Iterable<String> keys() {
    return List.copyOf(modelSettingsDict.keySet());
  }

  /** Ports free: close the current model and delete the reference. */
  public void free() {
    if (currentModel != null) {
      currentModel.close();
      currentModel = null;
    }
  }

  public LlamaModel currentModel() {
    return currentModel;
  }

  public String currentModelAlias() {
    return currentModelAlias;
  }
}
