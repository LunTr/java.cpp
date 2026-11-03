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

  // ---- load_llama_from_model_settings --------------------------------------

  /** External dependencies of the pinned loader; every construction call is recorded. */
  public interface LoaderDeps {
    Object chatHandler(String cls, Object clipModelPath, Object verbose);

    Object chatHandlerFromPretrained(String cls, Object repoId, Object filename, Object verbose);

    Object hfAutotokenizerHandler(Object nameOrPath);

    /** json.load(open(path)) for hf-tokenizer-config. */
    Object readJsonFile(Object path);

    Object hfTokenizerConfigHandler(Object config);

    /** llama_tokenizer.LlamaHFTokenizer.from_pretrained. */
    Object hfTokenizer(Object nameOrPath);

    /** llama_speculative.LlamaPromptLookupDecoding. */
    Object draftModel(Object numPredTokens);

    /** functools.partial(Llama.from_pretrained, repo_id, filename) invocation. */
    Object llamaFromPretrained(Object repoId, Object filename, Map<String, Object> kwargs);

    /** llama_cpp.Llama(**kwargs) invocation. */
    Object llamaCreate(Map<String, Object> kwargs);

    Object diskCache(Object capacityBytes);

    Object ramCache(Object capacityBytes);

    void print(Object message);

    /** Invokes a chat handler object with (*args, **kwargs), tracing the call. */
    Object callChatHandler(Object handler, List<Object> args, Map<String, Object> kwargs);

    /** llama_cpp.llama_chat_format.get_chat_completion_handler(chat_format). */
    Object registryChatHandler(Object chatFormat);
  }

  /** The constructed llama model; attribute reads/writes on the original instance. */
  public interface LoadedLlama {
    Object chatHandler();

    void setChatHandler(Object handler);

    Map<String, Object> chatHandlers();

    Object chatFormat();

    void setCache(Object cache);
  }

  /**
   * Ports the nested chat_handler_with_kwargs closure: merges settings.chat_template_kwargs under
   * the caller kwargs and delegates to the captured base handler.
   */
  public static final class ChatHandlerWithKwargs {
    private final LoadedLlama model;
    private final Object baseHandler;
    private final Map<String, Object> templateKwargs;
    private final LoaderDeps deps;

    private ChatHandlerWithKwargs(
        LoadedLlama model,
        Object baseHandler,
        Map<String, Object> templateKwargs,
        LoaderDeps deps) {
      this.model = model;
      this.baseHandler = baseHandler;
      this.templateKwargs = templateKwargs;
      this.deps = deps;
    }

    public Object call(List<Object> args, Map<String, Object> kwargs) {
      Map<String, Object> merged = new LinkedHashMap<>(templateKwargs);
      merged.putAll(kwargs);
      return deps.callChatHandler(baseHandler, args, merged);
    }
  }

  /** Ports server/model.py load_llama_from_model_settings. */
  @SuppressWarnings("unchecked")
  public static LoadedLlama loadLlamaFromModelSettings(
      Map<String, Object> settings, LoaderDeps deps) {
    Object chatHandler = null;
    String chatFormat = (String) settings.get("chat_format");
    String chatHandlerCls =
        switch (chatFormat == null ? "" : chatFormat) {
          case "llava-1-5" -> "Llava15ChatHandler";
          case "obsidian" -> "ObsidianChatHandler";
          case "llava-1-6" -> "Llava16ChatHandler";
          case "mtmd" -> "MTMDChatHandler";
          case "gemma4" -> "Gemma4ChatHandler";
          case "moondream" -> "MoondreamChatHandler";
          case "nanollava" -> "NanoLlavaChatHandler";
          case "llama-3-vision-alpha" -> "Llama3VisionAlpha";
          case "minicpm-v-2.6" -> "MiniCPMv26ChatHandler";
          case "qwen2.5-vl" -> "Qwen25VLChatHandler";
          default -> null;
        };
    if (chatHandlerCls != null) {
      if (settings.get("clip_model_path") == null) {
        throw Py.error(PythonErrorType.ASSERTION_ERROR, "clip model not found");
      }
      if (settings.get("hf_model_repo_id") != null) {
        chatHandler =
            deps.chatHandlerFromPretrained(
                chatHandlerCls,
                settings.get("hf_model_repo_id"),
                settings.get("clip_model_path"),
                settings.get("verbose"));
      } else {
        chatHandler =
            deps.chatHandler(
                chatHandlerCls, settings.get("clip_model_path"), settings.get("verbose"));
      }
    } else if ("hf-autotokenizer".equals(chatFormat)) {
      if (settings.get("hf_pretrained_model_name_or_path") == null) {
        throw Py.error(
            PythonErrorType.ASSERTION_ERROR,
            "hf_pretrained_model_name_or_path must be set for hf-autotokenizer");
      }
      chatHandler = deps.hfAutotokenizerHandler(settings.get("hf_pretrained_model_name_or_path"));
    } else if ("hf-tokenizer-config".equals(chatFormat)) {
      if (settings.get("hf_tokenizer_config_path") == null) {
        throw Py.error(
            PythonErrorType.ASSERTION_ERROR,
            "hf_tokenizer_config_path must be set for hf-tokenizer-config");
      }
      chatHandler =
          deps.hfTokenizerConfigHandler(
              deps.readJsonFile(settings.get("hf_tokenizer_config_path")));
    }

    Object tokenizer = null;
    if (settings.get("hf_pretrained_model_name_or_path") != null) {
      tokenizer = deps.hfTokenizer(settings.get("hf_pretrained_model_name_or_path"));
    }

    Object draftModel = null;
    if (settings.get("draft_model") != null) {
      draftModel = deps.draftModel(settings.get("draft_model_num_pred_tokens"));
    }

    Map<String, Object> kvOverrides = null;
    if (settings.get("kv_overrides") != null) {
      if (!(settings.get("kv_overrides") instanceof List)) {
        throw Py.error(PythonErrorType.ASSERTION_ERROR, "");
      }
      kvOverrides = new LinkedHashMap<>();
      for (Object item : (List<Object>) settings.get("kv_overrides")) {
        String[] kv = ((String) item).split("=", -1);
        if (kv.length != 2) {
          throw Py.error(PythonErrorType.VALUE_ERROR, "too many values to unpack (expected 2)");
        }
        String key = kv[0];
        String value = kv[1];
        if (value.contains(":")) {
          String[] typed = value.split(":", -1);
          if (typed.length != 2) {
            throw Py.error(PythonErrorType.VALUE_ERROR, "too many values to unpack (expected 2)");
          }
          String valueType = typed[0];
          value = typed[1];
          switch (valueType) {
            case "bool" -> kvOverrides.put(key, List.of("true", "1").contains(value.toLowerCase()));
            case "int" -> kvOverrides.put(key, Long.parseLong(value));
            case "float" -> kvOverrides.put(key, Double.parseDouble(value));
            case "str" -> kvOverrides.put(key, value);
            default ->
                throw Py.error(PythonErrorType.VALUE_ERROR, "Unknown value type " + valueType);
          }
        }
      }
    }

    Map<String, Object> kwargs = new LinkedHashMap<>();
    Object repoId = settings.get("hf_model_repo_id");
    if (repoId == null) {
      kwargs.put("model_path", settings.get("model"));
    }
    String[] keys = {
      "n_gpu_layers",
      "split_mode",
      "main_gpu",
      "tensor_split",
      "vocab_only",
      "use_mmap",
      "use_mlock",
      "kv_overrides",
      "rpc_servers",
      "seed",
      "n_ctx",
      "n_batch",
      "n_ubatch",
      "n_threads",
      "n_threads_batch",
      "rope_scaling_type",
      "rope_freq_base",
      "rope_freq_scale",
      "yarn_ext_factor",
      "yarn_attn_factor",
      "yarn_beta_fast",
      "yarn_beta_slow",
      "yarn_orig_ctx",
      "mul_mat_q",
      "logits_all",
      "embedding",
      "offload_kqv",
      "flash_attn",
      "last_n_tokens_size",
      "lora_base",
      "lora_path",
      "numa",
      "chat_format",
      "chat_handler",
      "draft_model",
      "type_k",
      "type_v",
      "tokenizer",
      "verbose"
    };
    for (String key : keys) {
      switch (key) {
        case "kv_overrides" -> kwargs.put(key, kvOverrides);
        case "chat_handler" -> kwargs.put(key, chatHandler);
        case "draft_model" -> kwargs.put(key, draftModel);
        case "tokenizer" -> kwargs.put(key, tokenizer);
        default -> kwargs.put(key, settings.get(key));
      }
    }

    LoadedLlama model =
        repoId != null
            ? (LoadedLlama) deps.llamaFromPretrained(repoId, settings.get("model"), kwargs)
            : (LoadedLlama) deps.llamaCreate(kwargs);

    Object templateKwargs = settings.get("chat_template_kwargs");
    if (templateKwargs instanceof Map<?, ?> tplMap ? !tplMap.isEmpty() : templateKwargs != null) {
      Object baseHandler = model.chatHandler();
      if (baseHandler == null) {
        baseHandler = model.chatHandlers().get(model.chatFormat());
      }
      if (baseHandler == null) {
        baseHandler = deps.registryChatHandler(model.chatFormat());
      }
      ChatHandlerWithKwargs wrapped =
          new ChatHandlerWithKwargs(model, baseHandler, (Map<String, Object>) templateKwargs, deps);
      model.setChatHandler(wrapped);
    }
    if (Boolean.TRUE.equals(settings.get("cache"))) {
      Object cache;
      if ("disk".equals(settings.get("cache_type"))) {
        if (Boolean.TRUE.equals(settings.get("verbose"))) {
          deps.print("Using disk cache with size " + settings.get("cache_size"));
        }
        cache = deps.diskCache(settings.get("cache_size"));
      } else {
        if (Boolean.TRUE.equals(settings.get("verbose"))) {
          deps.print("Using ram cache with size " + settings.get("cache_size"));
        }
        cache = deps.ramCache(settings.get("cache_size"));
      }
      model.setCache(cache);
    }
    return model;
  }
}
