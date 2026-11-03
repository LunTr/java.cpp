package dev.javacpp.compat.python;

import dev.javacpp.python.Py;
import dev.javacpp.python.PythonErrorType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ports the pinned Llama.__init__ assembly order: model/context param field writes, ExitStack enter
 * order, LoRA callback wiring, metadata/Jinja chat-handler population and failure branches.
 */
public final class PythonLlamaInit {
  private PythonLlamaInit() {}

  /** Field-recording params object; every assignment appends [name, key, value] to the trace. */
  public interface Params {
    void set(String key, Object value);

    Object get(String key);
  }

  /** Native/chat dependency ports; each call is traceable. */
  public interface Deps {
    void backendInit();

    void numaInit(Object strategy);

    Params modelDefaultParams();

    Params contextDefaultParams();

    List<Map<String, Object>> kvOverrideArray(int length);

    boolean pathExists(String path);

    Object enterModel(String path, Params params, Object verbose);

    Object enterContext(Object model, Params params, Object verbose);

    Object enterBatch(int nTokens, int embd, Object nSeqMax, Object verbose);

    Object loraInit(Object model, byte[] path);

    void loraFree(Object adapter);

    int setAdaptersLora(Object ctx, List<Object> adapters, int count, float[] scales);

    void callback(Object closure);

    /** ctypes.memmove for kv override str values. */
    void memmove(int bytes);

    Object candidates(int nVocab);

    Object makeTokenizer(Object self);

    int cpuCount();

    Object maxParallelSequences();

    Object systemInfo();

    int modelIsRecurrent(Object model);

    int modelIsHybrid(Object model);

    int nCtxTrain(Object model);

    Map<String, Object> metadata(Object model);

    Object tokenGetText(Object model, Object token);

    Object nVocab(Object self);

    Object nCtx(Object self);

    Object tokenNl(Object self);

    Object tokenEos(Object self);

    Object tokenBos(Object self);

    Object jinjaHandler(String template, String eos, String bos, List<Object> stopIds);

    Object guessChatFormat(Map<String, Object> metadata);

    void printErr(String line);

    void setVerbose(Object verbose);

    void suppress(Object disable, Runnable body);
  }

  /** The initialized instance; fields map attribute names in definition order. */
  public static final class Instance {
    public final Map<String, Object> fields = new LinkedHashMap<>();
    public final Map<String, Object> chatHandlers = new LinkedHashMap<>();
    public Params modelParams;
    public Params contextParams;
    public boolean chatHandlersSet;
    public Object model;
    public Object ctx;
    public Object self = "LLAMA";
  }

  /** Ports the pinned __init__ body; kwargs already contain every keyword argument. */
  @SuppressWarnings("unchecked")
  public static Instance init(Instance self, String modelPath, Map<String, Object> k, Deps d) {
    Map<String, Object> f = self.fields;
    Object verbose = k.get("verbose");
    f.put("verbose", verbose);
    List<Runnable> callbacks = new ArrayList<>();
    d.setVerbose(verbose);
    d.suppress(verbose, d::backendInit);
    Object numa = k.get("numa");
    f.put("numa", numa instanceof Boolean b ? (b ? 2 : 0) : numa);
    if (!Integer.valueOf(0).equals(f.get("numa"))) {
      Object strategy = f.get("numa");
      d.suppress(verbose, () -> d.numaInit(strategy));
    }
    f.put("model_path", modelPath);
    self.modelParams = d.modelDefaultParams();
    Object nGpu = k.get("n_gpu_layers");
    self.modelParams.set(
        "n_gpu_layers", (nGpu instanceof Number n && n.intValue() == -1) ? 0x7FFFFFFF : nGpu);
    self.modelParams.set("split_mode", k.get("split_mode"));
    self.modelParams.set("main_gpu", k.get("main_gpu"));
    f.put("tensor_split", k.get("tensor_split"));
    if (k.get("tensor_split") instanceof List<?> ts) {
      if (ts.size() > 4) {
        throw Py.error(
            PythonErrorType.VALUE_ERROR,
            "Attempt to split tensors that exceed maximum supported devices. Current LLAMA_MAX_DEVICES=4");
      }
      Object arr = new ArrayList<>(ts);
      f.put("_c_tensor_split", arr);
      self.modelParams.set("tensor_split", arr);
    }
    self.modelParams.set("vocab_only", k.get("vocab_only"));
    Object useMmap = k.get("use_mmap");
    Object loraPath = k.get("lora_path");
    boolean mmap = Boolean.TRUE.equals(useMmap) && loraPath == null;
    boolean mlock = Boolean.TRUE.equals(k.get("use_mlock"));
    self.modelParams.set("load_mode", mmap && mlock ? 3 : mlock ? 2 : mmap ? 1 : 0);
    f.put("kv_overrides", k.get("kv_overrides"));
    if (k.get("kv_overrides") instanceof Map<?, ?> kv) {
      List<Map<String, Object>> array = d.kvOverrideArray(kv.size() + 1);
      f.put("_kv_overrides_array", array);
      int i = 0;
      for (Map.Entry<?, ?> e : kv.entrySet()) {
        String key = (String) e.getKey();
        Object v = e.getValue();
        array.get(i).put("key", key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (v instanceof Boolean bv) {
          array.get(i).put("tag", 0);
          array.get(i).put("value", new LinkedHashMap<>(Map.of("val_bool", bv)));
        } else if (v instanceof Integer || v instanceof Long) {
          array.get(i).put("tag", 1);
          array.get(i).put("value", new LinkedHashMap<>(Map.of("val_i64", v)));
        } else if (v instanceof Double || v instanceof Float) {
          array.get(i).put("tag", 2);
          array.get(i).put("value", new LinkedHashMap<>(Map.of("val_f64", v)));
        } else if (v instanceof String s) {
          byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          if (bytes.length > 128) {
            throw Py.error(PythonErrorType.VALUE_ERROR, "Value for " + key + " is too long: " + s);
          }
          array.get(i).put("tag", 3);
          array.get(i).put("value", new LinkedHashMap<>());
          d.memmove(128);
        } else {
          throw Py.error(PythonErrorType.VALUE_ERROR, "Unknown value type for " + key + ": " + v);
        }
        i++;
      }
      array.get(array.size() - 1).put("key", new byte[] {0});
      self.modelParams.set("kv_overrides", array);
    }
    int nCtx = ((Number) k.get("n_ctx")).intValue();
    int nBatch = Math.min(nCtx, ((Number) k.get("n_batch")).intValue());
    f.put("n_batch", nBatch);
    Object nThreads = k.get("n_threads");
    f.put(
        "n_threads",
        nThreads instanceof Number n && n.intValue() != 0
            ? nThreads
            : Math.max(d.cpuCount() / 2, 1));
    Object nThreadsBatch = k.get("n_threads_batch");
    f.put(
        "n_threads_batch",
        nThreadsBatch instanceof Number n && n.intValue() != 0 ? nThreadsBatch : d.cpuCount());
    Object seed = k.get("seed");
    f.put("_seed", seed instanceof Number n && n.longValue() != 0 ? seed : 4294967295L);
    self.contextParams = d.contextDefaultParams();
    self.contextParams.set("n_ctx", nCtx);
    self.contextParams.set("n_batch", nBatch);
    self.contextParams.set("n_ubatch", Math.min(nBatch, ((Number) k.get("n_ubatch")).intValue()));
    self.contextParams.set("n_threads", f.get("n_threads"));
    self.contextParams.set("n_threads_batch", f.get("n_threads_batch"));
    self.contextParams.set(
        "rope_scaling_type", k.get("rope_scaling_type") != null ? k.get("rope_scaling_type") : -1);
    self.contextParams.set("pooling_type", k.get("pooling_type"));
    self.contextParams.set("attention_type", k.get("attention_type"));
    for (String key :
        new String[] {
          "rope_freq_base",
          "rope_freq_scale",
          "yarn_ext_factor",
          "yarn_attn_factor",
          "yarn_beta_fast",
          "yarn_beta_slow"
        }) {
      Object v = k.get(key);
      self.contextParams.set(key, v instanceof Number n && n.doubleValue() != 0.0 ? v : 0);
    }
    Object origCtx = k.get("yarn_orig_ctx");
    self.contextParams.set(
        "yarn_orig_ctx", origCtx instanceof Number n && n.intValue() != 0 ? origCtx : 0);
    f.put("_logits_all", k.get("draft_model") == null ? k.get("logits_all") : Boolean.TRUE);
    self.contextParams.set("embeddings", k.get("embedding"));
    self.contextParams.set("offload_kqv", k.get("offload_kqv"));
    self.contextParams.set("flash_attn_type", Boolean.TRUE.equals(k.get("flash_attn")) ? 1 : 0);
    if (k.get("op_offload") != null) self.contextParams.set("op_offload", k.get("op_offload"));
    if (k.get("swa_full") != null) self.contextParams.set("swa_full", k.get("swa_full"));
    if (k.get("type_k") != null) self.contextParams.set("type_k", k.get("type_k"));
    if (k.get("type_v") != null) self.contextParams.set("type_v", k.get("type_v"));
    self.contextParams.set("no_perf", k.get("no_perf"));
    f.put("last_n_tokens_size", k.get("last_n_tokens_size"));
    f.put("cache", null);
    f.put("lora_base", k.get("lora_base"));
    f.put("lora_scale", k.get("lora_scale"));
    f.put("lora_path", loraPath);
    f.put("spm_infill", k.get("spm_infill"));
    if (!d.pathExists(modelPath)) {
      throw Py.error(PythonErrorType.VALUE_ERROR, "Model path does not exist: " + modelPath);
    }
    self.model = d.enterModel(modelPath, self.modelParams, verbose);
    Object tokenizer = k.get("tokenizer");
    f.put("tokenizer_", tokenizer != null ? tokenizer : d.makeTokenizer(self.self));
    if (nCtx == 0) {
      nCtx = ((Number) d.nCtxTrain(self.model)).intValue();
      f.put("n_batch", Math.min(nCtx, ((Number) k.get("n_batch")).intValue()));
      self.contextParams.set("n_ctx", d.nCtxTrain(self.model));
      self.contextParams.set("n_batch", f.get("n_batch"));
      self.contextParams.set(
          "n_ubatch",
          Math.min((Integer) f.get("n_batch"), ((Number) k.get("n_ubatch")).intValue()));
    }
    if (Boolean.TRUE.equals(k.get("embedding"))) {
      self.contextParams.set(
          "n_seq_max",
          Math.min((Integer) f.get("n_batch"), ((Number) d.maxParallelSequences()).intValue()));
      self.contextParams.set("kv_unified", true);
    }
    self.ctx = d.enterContext(self.model, self.contextParams, verbose);
    d.enterBatch((Integer) f.get("n_batch"), 0, self.contextParams.get("n_ctx"), verbose);
    f.put("_lora_adapter", null);
    if (loraPath != null) {
      Object adapter =
          d.loraInit(
              self.model, ((String) loraPath).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      f.put("_lora_adapter", adapter);
      if (adapter == null) {
        throw Py.error(
            PythonErrorType.RUNTIME_ERROR,
            "Failed to initialize LoRA adapter from lora path: " + loraPath);
      }
      d.callback("free_lora_adapter");
      if (d.setAdaptersLora(
              "CTX", List.of(adapter), 1, new float[] {((Number) k.get("lora_scale")).floatValue()})
          != 0) {
        throw Py.error(
            PythonErrorType.RUNTIME_ERROR,
            "Failed to set LoRA adapter from lora path: " + loraPath);
      }
    }
    if (Boolean.TRUE.equals(verbose)) {
      d.printErr(String.valueOf(d.systemInfo()));
    }
    f.put("chat_format", k.get("chat_format"));
    f.put("chat_handler", k.get("chat_handler"));
    self.chatHandlersSet = true;
    f.put("draft_model", k.get("draft_model"));
    f.put("_n_vocab", d.nVocab(self.self));
    f.put("_n_ctx", d.nCtx(self.self));
    f.put("_token_nl", d.tokenNl(self.self));
    f.put("_token_eos", d.tokenEos(self.self));
    f.put("_candidates", d.candidates(((Number) f.get("_n_vocab")).intValue()));
    f.put("n_tokens", 0);
    f.put("_requires_eval", true);
    f.put("input_ids_shape", nCtx);
    f.put(
        "scores_shape",
        List.of(
            Boolean.TRUE.equals(k.get("logits_all")) ? nCtx : (Integer) f.get("n_batch"),
            f.get("_n_vocab")));
    f.put("_mirostat_mu", 2.0 * 5.0);
    Map<String, Object> metadata;
    try {
      metadata = d.metadata(self.model);
    } catch (Exception error) {
      metadata = new LinkedHashMap<>();
      if (Boolean.TRUE.equals(verbose)) {
        d.printErr("Failed to load metadata: " + error.getMessage());
      }
    }
    f.put("metadata", metadata);
    if (Boolean.TRUE.equals(verbose)) {
      d.printErr("Model metadata: " + Py.repr(metadata));
    }
    Object eosId = d.tokenEos(self.self);
    Object bosId = d.tokenBos(self.self);
    String eosToken =
        "-1".equals(String.valueOf(eosId)) ? "" : (String) d.tokenGetText(self.model, eosId);
    String bosToken =
        "-1".equals(String.valueOf(bosId)) ? "" : (String) d.tokenGetText(self.model, bosId);
    Map<String, Object> templateChoices = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : metadata.entrySet()) {
      if (e.getKey().startsWith("tokenizer.chat_template.")) {
        templateChoices.put(e.getKey().substring(10), e.getValue());
      }
    }
    if (metadata.containsKey("tokenizer.chat_template")) {
      templateChoices.put("chat_template.default", metadata.get("tokenizer.chat_template"));
    }
    if (Boolean.TRUE.equals(verbose) && !templateChoices.isEmpty()) {
      d.printErr(
          "Available chat formats from metadata: " + String.join(", ", templateChoices.keySet()));
    }
    for (Map.Entry<String, Object> e : templateChoices.entrySet()) {
      self.chatHandlers.put(
          e.getKey(), d.jinjaHandler((String) e.getValue(), eosToken, bosToken, List.of(eosId)));
    }
    if (f.get("chat_format") == null
        && f.get("chat_handler") == null
        && templateChoices.containsKey("chat_template.default")) {
      Object guessed = d.guessChatFormat(metadata);
      if (guessed != null) {
        f.put("chat_format", guessed);
        if (Boolean.TRUE.equals(verbose)) d.printErr("Guessed chat format: " + guessed);
      } else {
        if (Boolean.TRUE.equals(verbose)) {
          d.printErr("Using gguf chat template: " + templateChoices.get("chat_template.default"));
          d.printErr("Using chat eos_token: " + eosToken);
          d.printErr("Using chat bos_token: " + bosToken);
        }
        f.put("chat_format", "chat_template.default");
      }
    }
    if (f.get("chat_format") == null && f.get("chat_handler") == null) {
      f.put("chat_format", "llama-2");
      if (Boolean.TRUE.equals(verbose)) {
        d.printErr("Using fallback chat format: llama-2");
      }
    }
    f.put("_sampler", null);
    f.put("_is_recurrent", d.modelIsRecurrent("MODEL"));
    f.put("_is_hybrid", d.modelIsHybrid("MODEL"));
    f.put("_callbacks", callbacks);
    return self;
  }
}
