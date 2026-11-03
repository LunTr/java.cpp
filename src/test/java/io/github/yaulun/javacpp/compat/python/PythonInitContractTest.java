package io.github.yaulun.javacpp.compat.python;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.yaulun.javacpp.python.PythonException;
import io.github.yaulun.javacpp.python.PythonValues;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class PythonInitContractTest {
  @TestFactory
  Stream<DynamicTest> matchesPinnedInit() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-init-contracts.tsv"),
            "Run generate_init_contracts.py")) {
      lines =
          new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII)
              .lines()
              .toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index ->
                DynamicTest.dynamicTest(
                    "init:" + index,
                    () -> {
                      String[] parts = lines.get(index).split("\t");
                      Object argument = read(parts[1]);
                      Object actual = replay((Map<String, Object>) argument);
                      var bytes = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(bytes), actual);
                      assertEquals(
                          parts[2],
                          Base64.getEncoder().encodeToString(bytes.toByteArray()),
                          "argument=" + argument + ", result=" + actual);
                    }));
  }

  private static Object read(String encoded) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))));
  }

  @SuppressWarnings("unchecked")
  private static Object replay(Map<String, Object> argument) {
    List<Object> trace = new ArrayList<>();
    Map<String, Object> files = new LinkedHashMap<>();
    for (Object f : (List<Object>) argument.getOrDefault("files", List.of()))
      files.put((String) f, Boolean.TRUE);
    class P implements PythonLlamaInit.Params {
      final String name;
      final Map<String, Object> fields = new LinkedHashMap<>();

      P(String name) {
        this.name = name;
      }

      public void set(String key, Object value) {
        fields.put(key, value);
        trace.add(List.of(name, key, mark(value)));
      }

      public Object get(String key) {
        return fields.get(key);
      }
    }
    List<String> stderr = new ArrayList<>();
    var deps =
        new PythonLlamaInit.Deps() {
          public void backendInit() {
            trace.add(List.of("backend_init"));
          }

          public void numaInit(Object s) {
            trace.add(List.of("numa_init", mark(s)));
          }

          public P modelDefaultParams() {
            trace.add(List.of("model_default_params"));
            return new P("model_params");
          }

          public P contextDefaultParams() {
            trace.add(List.of("context_default_params"));
            return new P("context_params");
          }

          public List<Map<String, Object>> kvOverrideArray(int n) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(new LinkedHashMap<>());
            return out;
          }

          public boolean pathExists(String path) {
            trace.add(List.of("exists", path));
            return files.containsKey(path);
          }

          public Object enterModel(String path, PythonLlamaInit.Params params, Object verbose) {
            trace.add(List.of("LlamaModel", path, mark(((P) params).fields), verbose));
            return new Object() {
              public String toString() {
                return "MODEL";
              }
            };
          }

          public Object enterContext(Object model, PythonLlamaInit.Params params, Object verbose) {
            trace.add(List.of("LlamaContext", "MODEL", mark(((P) params).fields), verbose));
            return "CTX";
          }

          public Object enterBatch(int nTokens, int embd, Object nSeqMax, Object verbose) {
            trace.add(List.of("LlamaBatch", nTokens, embd, nSeqMax, verbose));
            return "BATCH";
          }

          public Object loraInit(Object model, byte[] path) {
            trace.add(List.of("lora_init", "MODEL", Map.of("bytes", hex(path))));
            return Boolean.TRUE.equals(argument.get("lora_fail")) ? null : "ADAPTER";
          }

          public void loraFree(Object adapter) {
            trace.add(List.of("lora_free", adapter));
          }

          public int setAdaptersLora(Object ctx, List<Object> adapters, int count, float[] scales) {
            List<Object> sc = new ArrayList<>();
            for (float v : scales) sc.add((double) v);
            trace.add(List.of("set_adapters", ctx, adapters, count, sc));
            return ((Number) argument.getOrDefault("adapters_fail", 0L)).intValue();
          }

          public void callback(Object closure) {}

          public void memmove(int bytes) {
            trace.add(List.of("memmove", bytes));
          }

          public Object candidates(int nVocab) {
            trace.add(List.of("candidates", nVocab));
            return "CAND";
          }

          public Object makeTokenizer(Object self) {
            trace.add(List.of("LlamaTokenizer"));
            return "TOK";
          }

          public int cpuCount() {
            return n(argument.getOrDefault("cpus", 8L));
          }

          public Object maxParallelSequences() {
            return argument.getOrDefault("max_seq", 4L);
          }

          public Object systemInfo() {
            return "sysinfo";
          }

          public int modelIsRecurrent(Object model) {
            return Boolean.TRUE.equals(argument.get("recurrent")) ? 1 : 0;
          }

          public int modelIsHybrid(Object model) {
            return Boolean.TRUE.equals(argument.get("hybrid")) ? 1 : 0;
          }

          public int nCtxTrain(Object model) {
            trace.add(List.of("n_ctx_train"));
            return n(argument.getOrDefault("n_ctx_train", 64L));
          }

          public Map<String, Object> metadata(Object model) {
            trace.add(List.of("metadata"));
            if (Boolean.TRUE.equals(argument.get("metadata_fail")))
              throw PythonValues.error(
                  io.github.yaulun.javacpp.python.PythonErrorType.RUNTIME_ERROR, "meta fail");
            return new LinkedHashMap<>(
                (Map<String, Object>) argument.getOrDefault("metadata", Map.of()));
          }

          public Object tokenGetText(Object model, Object token) {
            trace.add(List.of("token_get_text", token));
            Map<String, Object> texts =
                (Map<String, Object>) argument.getOrDefault("tok_text", Map.of());
            return texts.getOrDefault(String.valueOf(token), "T" + token);
          }

          public Object nVocab(Object self) {
            trace.add(List.of("n_vocab"));
            return 4;
          }

          public Object nCtx(Object self) {
            trace.add(List.of("n_ctx"));
            return 64;
          }

          public Object tokenNl(Object self) {
            trace.add(List.of("token_nl"));
            return 13;
          }

          public Object tokenEos(Object self) {
            trace.add(List.of("token_eos"));
            return 2;
          }

          public Object tokenBos(Object self) {
            trace.add(List.of("token_bos"));
            return 1;
          }

          public Object jinjaHandler(
              String template, String eos, String bos, List<Object> stopIds) {
            Map<String, Object> handler = new LinkedHashMap<>();
            handler.put("jinja", template);
            handler.put("eos", eos);
            handler.put("bos", bos);
            handler.put("stop", stopIds);
            return handler;
          }

          public Object guessChatFormat(Map<String, Object> metadata) {
            return argument.get("guess");
          }

          public void printErr(String line) {
            stderr.add(line);
          }

          public void setVerbose(Object verbose) {
            trace.add(List.of("set_verbose", verbose));
          }

          public void suppress(Object disable, Runnable body) {
            trace.add(List.of("suppress", disable));
            body.run();
          }
        };
    Map<String, Object> kwargs = new LinkedHashMap<>((Map<String, Object>) argument.get("kwargs"));
    Object result;
    Map<String, Object> snapshot = new LinkedHashMap<>();
    PythonLlamaInit.Instance self = new PythonLlamaInit.Instance();
    try {
      PythonLlamaInit.init(
          self, (String) argument.getOrDefault("model_path", "m.gguf"), kwargs, deps);
      result = null;
    } catch (PythonException error) {
      result = dict2("error", error.type().pythonName(), "message", error.getMessage());
    }
    {
      for (String key :
          new String[] {
            "verbose",
            "numa",
            "model_path",
            "tensor_split",
            "n_batch",
            "n_threads",
            "n_threads_batch",
            "_seed",
            "last_n_tokens_size",
            "lora_base",
            "lora_scale",
            "lora_path",
            "spm_infill",
            "_logits_all",
            "n_tokens",
            "_requires_eval",
            "chat_format",
            "_n_vocab",
            "_n_ctx",
            "_token_nl",
            "_token_eos",
            "_mirostat_mu",
            "_is_recurrent",
            "_is_hybrid",
            "tokenizer_"
          }) {
        if (self.fields.containsKey(key)) snapshot.put(key, mark(self.fields.get(key)));
      }
      if (self.modelParams != null)
        snapshot.put("model_params", mark(((P) self.modelParams).fields));
      if (self.contextParams != null)
        snapshot.put("context_params", mark(((P) self.contextParams).fields));
      if (self.fields.containsKey("metadata"))
        snapshot.put("metadata", mark(self.fields.get("metadata")));
      if (self.chatHandlersSet) snapshot.put("chat_handlers", mark(self.chatHandlers));
      if (self.fields.containsKey("cache")) snapshot.put("cache", mark(self.fields.get("cache")));
      if (self.fields.containsKey("draft_model"))
        snapshot.put("draft_model", mark(self.fields.get("draft_model")));
      if (self.fields.containsKey("_lora_adapter"))
        snapshot.put("lora_adapter", mark(self.fields.get("_lora_adapter")));
      if (self.fields.containsKey("kv_overrides"))
        snapshot.put("kv_overrides", mark(self.fields.get("kv_overrides")));
      if (self.fields.containsKey("_kv_overrides_array"))
        snapshot.put("kvo_array", mark(self.fields.get("_kv_overrides_array")));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("result", result);
    out.put("snapshot", snapshot);
    out.put("trace", trace);
    out.put("stderr", stderr);
    return out;
  }

  private static Map<String, Object> dict2(Object... kv) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
    return map;
  }

  private static int n(Object value) {
    return ((Number) value).intValue();
  }

  private static String hex(byte[] data) {
    StringBuilder out = new StringBuilder();
    for (byte b : data) out.append(String.format("%02x", b));
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static Object mark(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) return value;
    if (value instanceof byte[] bytes) return Map.of("bytes", hex(bytes));
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(mark(k), mark(v)));
      return out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object item : list) out.add(mark(item));
      return out;
    }
    return String.valueOf(value);
  }
}
