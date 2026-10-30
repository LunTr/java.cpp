package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonInternalsContractTest {
  @TestFactory
  Stream<DynamicTest> matchesOriginalInternalsBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-internals-contracts.tsv"),
            "Run generate_internals_contracts.py --source-root .tools/python-api-audit")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "internals:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object result = drive(map(read(parts[1])));
                      ByteArrayOutputStream output = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(output), result);
                      assertEquals(
                          parts[2], Base64.getEncoder().encodeToString(output.toByteArray()));
                    }));
  }

  private static Object read(String value) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private static int i(Object value) {
    return ((Number) value).intValue();
  }

  private static byte[] hex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private record Handle(String name) {}

  private static String name(Object handle) {
    return ((Handle) handle).name();
  }

  /** Scripted native port mirroring the traced stubs in generate_internals_contracts.py. */
  private static final class StubPort implements PythonInternalsPort {
    private final Map<String, Object> kase;
    private final List<Object> trace;
    private final Map<String, Deque<Object>> queues = new HashMap<>();
    private final Map<String, Integer> spawns = new HashMap<>();

    private StubPort(Map<String, Object> kase, List<Object> trace) {
      this.kase = kase;
      this.trace = trace;
      for (var entry : map(kase.getOrDefault("queues", Map.of())).entrySet()) {
        queues.put(entry.getKey(), new ArrayDeque<>(list(entry.getValue())));
      }
    }

    private boolean flag(String key, boolean fallback) {
      Object value = kase.get(key);
      return value == null ? fallback : (boolean) value;
    }

    private Object queue(String name) {
      Deque<Object> values = queues.get(name);
      if (values == null || values.isEmpty()) {
        throw new AssertionError("empty queue " + name);
      }
      return values.pop();
    }

    private Handle spawn(String name) {
      int count = spawns.merge(name, 1, Integer::sum);
      return new Handle(name + "#" + count);
    }

    @Override
    public boolean exists(String path) {
      return Boolean.TRUE.equals(map(kase.getOrDefault("exists", Map.of())).get(path));
    }

    @Override
    public void suppressStdoutStderr(boolean disable) {
      trace.add(List.of("suppress_stdout_stderr", disable));
    }

    @Override
    public Object modelDefaultParams() {
      trace.add(List.of("llama_model_default_params"));
      return new Handle("llama_model_default_params");
    }

    @Override
    public Object modelLoadFromFile(String path, Object params) {
      trace.add(
          List.of(
              "llama_model_load_from_file",
              HexFormat.of().formatHex(path.getBytes(StandardCharsets.UTF_8)),
              name(params)));
      return flag("load", true) ? new Handle("model") : null;
    }

    @Override
    public Object modelGetVocab(Object model) {
      trace.add(List.of("llama_model_get_vocab", name(model)));
      return flag("vocab", true) ? new Handle("vocab") : null;
    }

    @Override
    public void modelFree(Object model) {
      trace.add(List.of("llama_model_free", name(model)));
    }

    @Override
    public int modelNCtxTrain(Object model) {
      trace.add(List.of("llama_model_n_ctx_train", name(model)));
      return i(kase.getOrDefault("nCtxTrain", 8));
    }

    @Override
    public void modelDesc(Object model, byte[] buffer) {
      trace.add(List.of("llama_model_desc", name(model), "buf" + buffer.length, buffer.length));
      byte[] data = ((String) kase.getOrDefault("desc", "qwen35")).getBytes(StandardCharsets.UTF_8);
      System.arraycopy(data, 0, buffer, 0, data.length);
      buffer[data.length] = 0;
    }

    @Override
    public int modelMetaCount(Object model) {
      trace.add(List.of("llama_model_meta_count", name(model)));
      return list(kase.getOrDefault("metas", List.of())).size();
    }

    private int meta(Object model, int index, byte[] buffer, int which) {
      List<Object> entry = list(list(kase.get("metas")).get(index));
      byte[] text = ((String) entry.get(which)).getBytes(StandardCharsets.UTF_8);
      int grow = i(entry.get(2));
      int needed = ((grow >> which) & 1) == 1 ? text.length + 1 : text.length;
      if (needed > buffer.length) {
        return needed;
      }
      System.arraycopy(text, 0, buffer, 0, text.length);
      buffer[text.length] = 0;
      return text.length;
    }

    @Override
    public int modelMetaKeyByIndex(Object model, int index, byte[] buffer) {
      trace.add(
          List.of(
              "llama_model_meta_key_by_index",
              name(model),
              index,
              "buf" + buffer.length,
              buffer.length));
      return meta(model, index, buffer, 0);
    }

    @Override
    public int modelMetaValStrByIndex(Object model, int index, byte[] buffer) {
      trace.add(
          List.of(
              "llama_model_meta_val_str_by_index",
              name(model),
              index,
              "buf" + buffer.length,
              buffer.length));
      return meta(model, index, buffer, 1);
    }

    @Override
    public int vocabBos(Object vocab) {
      trace.add(List.of("llama_vocab_bos", name(vocab)));
      return i(kase.getOrDefault("bos", 11));
    }

    @Override
    public int tokenize(
        Object vocab, byte[] text, int[] tokens, int capacity, boolean addBos, boolean special) {
      trace.add(
          List.of(
              "llama_tokenize",
              name(vocab),
              HexFormat.of().formatHex(text),
              text.length,
              "buf" + tokens.length,
              capacity,
              addBos,
              special));
      List<Object> call = list(queue("tokenize"));
      List<Object> fill = list(call.get(0));
      for (int n = 0; n < fill.size(); n++) {
        tokens[n] = i(fill.get(n));
      }
      return i(call.get(1));
    }

    @Override
    public int tokenToPiece(Object vocab, int token, byte[] buffer, int lstrip, boolean special) {
      trace.add(
          List.of(
              "llama_token_to_piece",
              name(vocab),
              token,
              "buf" + buffer.length,
              buffer.length,
              lstrip,
              special));
      List<Object> call = list(queue("piece"));
      byte[] data = hex((String) call.get(0));
      System.arraycopy(data, 0, buffer, 0, data.length);
      return i(call.get(1));
    }

    @Override
    public Object contextDefaultParams() {
      trace.add(List.of("llama_context_default_params"));
      return new Handle("llama_context_default_params");
    }

    @Override
    public Object initFromModel(Object model, Object params) {
      trace.add(List.of("llama_init_from_model", name(model), name(params)));
      return flag("init", true) ? new Handle("ctx") : null;
    }

    @Override
    public Object getMemory(Object context) {
      trace.add(List.of("llama_get_memory", name(context)));
      return flag("memory", true) ? new Handle("mem") : null;
    }

    @Override
    public void memoryClear(Object memory, boolean data) {
      trace.add(List.of("llama_memory_clear", name(memory), data));
    }

    @Override
    public void contextFree(Object context) {
      trace.add(List.of("llama_free", name(context)));
    }

    @Override
    public int decode(Object context, PythonInternalsBatch batch) {
      trace.add(List.of("llama_decode", name(context), ((StubBatch) batch).snapshot()));
      return i(queue("decode"));
    }

    @Override
    public PythonInternalsBatch batchInit(int nTokens, int embd, int nSeqMax) {
      trace.add(List.of("llama_batch_init", nTokens, embd, nSeqMax));
      return flag("batchInit", true) ? new StubBatch(nTokens, embd, nSeqMax) : null;
    }

    @Override
    public void batchFree(PythonInternalsBatch batch) {
      trace.add(List.of("llama_batch_free", ((StubBatch) batch).snapshot()));
    }

    @Override
    public Object samplerChainDefaultParams() {
      trace.add(List.of("llama_sampler_chain_default_params"));
      return new Handle("llama_sampler_chain_default_params");
    }

    @Override
    public Object samplerChainInit(Object params) {
      trace.add(List.of("llama_sampler_chain_init", name(params)));
      return new Handle("chain");
    }

    @Override
    public void samplerChainAdd(Object chain, Object sampler) {
      trace.add(List.of("llama_sampler_chain_add", name(chain), name(sampler)));
    }

    @Override
    public Object samplerChainRemove(Object chain, int index) {
      trace.add(List.of("llama_sampler_chain_remove", name(chain), index));
      return new Handle("removed");
    }

    @Override
    public Object samplerInitGreedy() {
      trace.add(List.of("llama_sampler_init_greedy"));
      return spawn("llama_sampler_init_greedy");
    }

    @Override
    public Object samplerInitDist(long seed) {
      trace.add(List.of("llama_sampler_init_dist", seed));
      return spawn("llama_sampler_init_dist");
    }

    @Override
    public Object samplerInitTopK(int k) {
      trace.add(List.of("llama_sampler_init_top_k", k));
      return spawn("llama_sampler_init_top_k");
    }

    @Override
    public Object samplerInitTopP(double p, long minKeep) {
      trace.add(List.of("llama_sampler_init_top_p", p, minKeep));
      return spawn("llama_sampler_init_top_p");
    }

    @Override
    public Object samplerInitMinP(double p, long minKeep) {
      trace.add(List.of("llama_sampler_init_min_p", p, minKeep));
      return spawn("llama_sampler_init_min_p");
    }

    @Override
    public Object samplerInitTemp(double temperature) {
      trace.add(List.of("llama_sampler_init_temp", temperature));
      return spawn("llama_sampler_init_temp");
    }

    @Override
    public Object samplerInitPenalties(
        int nVocab, int penaltyLastN, double repeat, double freq, double present) {
      trace.add(
          List.of("llama_sampler_init_penalties", nVocab, penaltyLastN, repeat, freq, present));
      return spawn("llama_sampler_init_penalties");
    }

    @Override
    public long samplerGetSeed(Object sampler) {
      trace.add(List.of("llama_sampler_get_seed", name(sampler)));
      return ((Number) kase.getOrDefault("seed", 0)).longValue();
    }

    @Override
    public int samplerSample(Object sampler, Object context, int index) {
      trace.add(List.of("llama_sampler_sample", name(sampler), name(context), index));
      return i(queue("sample"));
    }

    @Override
    public void samplerFree(Object sampler) {
      trace.add(List.of("llama_sampler_free", name(sampler)));
    }
  }

  /** List-backed batch stand-in reproducing ctypes field and negative index semantics. */
  private static final class StubBatch implements PythonInternalsBatch {
    private final int capacity;
    private int nTokens;
    private final int[] token;
    private final int[] pos;
    private final int[][] seqId;
    private final int[] nSeqId;
    private final boolean[] logits;

    private StubBatch(int nTokens, int embd, int nSeqMax) {
      capacity = nTokens;
      token = embd == 0 ? new int[nTokens] : null;
      pos = new int[nTokens];
      seqId = new int[nTokens][nSeqMax];
      nSeqId = new int[nTokens];
      logits = new boolean[nTokens];
    }

    private int index(int index) {
      int resolved = index < 0 ? capacity + index : index;
      if (resolved < 0 || resolved >= capacity) {
        throw new PythonException(
            PythonErrorType.INDEX_ERROR, "list assignment index out of range", null);
      }
      return resolved;
    }

    private List<Object> snapshot() {
      List<Object> sequences = new ArrayList<>();
      for (int[] row : seqId) {
        sequences.add(Arrays.stream(row).boxed().collect(Collectors.toList()));
      }
      return List.of(
          "batch",
          nTokens,
          token == null ? List.of() : Arrays.stream(token).boxed().collect(Collectors.toList()),
          Arrays.stream(pos).boxed().collect(Collectors.toList()),
          sequences,
          Arrays.stream(nSeqId).boxed().collect(Collectors.toList()),
          IntStream.range(0, logits.length).mapToObj(i -> logits[i]).collect(Collectors.toList()));
    }

    @Override
    public int capacity() {
      return capacity;
    }

    @Override
    public int nTokens() {
      return nTokens;
    }

    @Override
    public void nTokens(int value) {
      nTokens = value;
    }

    @Override
    public void token(int index, int value) {
      token[index(index)] = value;
    }

    @Override
    public void pos(int index, int value) {
      pos[index(index)] = value;
    }

    @Override
    public void seqId(int index, int slot, int value) {
      seqId[index(index)][slot] = value;
    }

    @Override
    public void nSeqId(int index, int value) {
      nSeqId[index(index)] = value;
    }

    @Override
    public void logits(int index, boolean value) {
      logits[index(index)] = value;
    }
  }

  private static Object run(
      Map<String, Object> kase, List<Object> step, Map<String, Object> box, StubPort port) {
    String name = (String) step.get(0);
    boolean verbose = (boolean) kase.getOrDefault("verbose", true);
    switch (name) {
      case "model" -> {
        box.put(
            "model",
            new PythonLlamaModel(
                port,
                (String) kase.getOrDefault("path", "m.gguf"),
                new Handle("mparams"),
                verbose));
        return "ok";
      }
      case "context" -> {
        box.put(
            "ctx",
            new PythonLlamaContext(
                port, (PythonLlamaModel) box.get("model"), new Handle("cparams"), verbose));
        return "ok";
      }
      case "batch" -> {
        box.put(
            "batch",
            new PythonLlamaBatch(port, i(step.get(1)), i(step.get(2)), i(step.get(3)), verbose));
        return "ok";
      }
      case "sampler" -> {
        PythonWarnings warnings =
            PythonWarnings.withAction(
                PythonWarningAction.ALWAYS,
                warning ->
                    ((List<Object>) box.get("trace"))
                        .add(List.of("warn", warning.message(), warning.category().pythonName())));
        box.put("sampler", new PythonInternalsSampler(port, warnings));
        return "ok";
      }
      case "desc" -> {
        return ((PythonLlamaModel) box.get("model")).desc();
      }
      case "metadata" -> {
        return ((PythonLlamaModel) box.get("model")).metadata();
      }
      case "modelParams" -> {
        return name(PythonLlamaModel.defaultParams(port));
      }
      case "contextParams" -> {
        return name(PythonLlamaContext.defaultParams(port));
      }
      case "tokenize" -> {
        return ((PythonLlamaModel) box.get("model"))
            .tokenize(hex((String) step.get(1)), (boolean) step.get(2), (boolean) step.get(3));
      }
      case "piece" -> {
        return HexFormat.of()
            .formatHex(
                ((PythonLlamaModel) box.get("model"))
                    .tokenToPiece(i(step.get(1)), (boolean) step.get(2)));
      }
      case "detok" -> {
        return HexFormat.of()
            .formatHex(
                ((PythonLlamaModel) box.get("model"))
                    .detokenize(
                        list(step.get(1)).stream().map(PythonInternalsContractTest::i).toList(),
                        (boolean) step.get(2)));
      }
      case "kvClear" -> {
        ((PythonLlamaContext) box.get("ctx")).kvCacheClear();
        return null;
      }
      case "decode" -> {
        ((PythonLlamaContext) box.get("ctx")).decode((PythonLlamaBatch) box.get("batch"));
        return "ok";
      }
      case "batchSet" -> {
        ((PythonLlamaBatch) box.get("batch"))
            .setBatch(
                list(step.get(1)).stream().map(PythonInternalsContractTest::i).toList(),
                i(step.get(2)),
                (boolean) step.get(3));
        return "ok";
      }
      case "batchReset" -> {
        ((PythonLlamaBatch) box.get("batch")).reset();
        return null;
      }
      case "batchTokens" -> {
        return ((PythonLlamaBatch) box.get("batch")).nTokens();
      }
      case "greedy" -> {
        ((PythonInternalsSampler) box.get("sampler")).addGreedy();
        return null;
      }
      case "dist" -> {
        ((PythonInternalsSampler) box.get("sampler")).addDist(((Number) step.get(1)).longValue());
        return null;
      }
      case "softmax" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addSoftmax(new PythonWarningLocation("caller.py", 1, "caller", null));
        return null;
      }
      case "topK" -> {
        ((PythonInternalsSampler) box.get("sampler")).addTopK(i(step.get(1)));
        return null;
      }
      case "topP" -> {
        if (step.size() > 2) {
          ((PythonInternalsSampler) box.get("sampler"))
              .addTopP(((Number) step.get(1)).doubleValue(), ((Number) step.get(2)).longValue());
        } else {
          ((PythonInternalsSampler) box.get("sampler"))
              .addTopP(((Number) step.get(1)).doubleValue());
        }
        return null;
      }
      case "minP" -> {
        if (step.size() > 2) {
          ((PythonInternalsSampler) box.get("sampler"))
              .addMinP(((Number) step.get(1)).doubleValue(), ((Number) step.get(2)).longValue());
        } else {
          ((PythonInternalsSampler) box.get("sampler"))
              .addMinP(((Number) step.get(1)).doubleValue());
        }
        return null;
      }
      case "temp" -> {
        ((PythonInternalsSampler) box.get("sampler")).addTemp(((Number) step.get(1)).doubleValue());
        return null;
      }
      case "penalties" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addPenalties(
                i(step.get(1)),
                i(step.get(2)),
                ((Number) step.get(3)).doubleValue(),
                ((Number) step.get(4)).doubleValue(),
                ((Number) step.get(5)).doubleValue());
        return null;
      }
      case "getSeed" -> {
        return ((PythonInternalsSampler) box.get("sampler")).getSeed();
      }
      case "sample" -> {
        return ((PythonInternalsSampler) box.get("sampler"))
            .sample((PythonLlamaContext) box.get("ctx"));
      }
      case "sampleAt" -> {
        return ((PythonInternalsSampler) box.get("sampler"))
            .sample((PythonLlamaContext) box.get("ctx"), i(step.get(1)));
      }
      case "seedSamplers" -> {
        Object target = step.get(1).equals("model") ? box.get("model") : box.get("sampler");
        for (Object entry : list(step.get(2))) {
          List<Object> pair = list(entry);
          if (target instanceof PythonLlamaModel model) {
            model
                .customSamplers()
                .add(new Object[] {i(pair.get(0)), new Handle((String) pair.get(1))});
          } else {
            ((PythonInternalsSampler) target)
                .customSamplers()
                .add(new Object[] {i(pair.get(0)), new Handle((String) pair.get(1))});
          }
        }
        if (target instanceof PythonLlamaModel model) {
          model.setSampler(new Handle("ownerChain"));
        } else {
          ((PythonInternalsSampler) target).setSampler(new Handle("ownerChain"));
        }
        return "ok";
      }
      case "close" -> {
        Object target = box.get(step.get(1));
        if (target instanceof PythonLlamaModel model) model.close();
        else if (target instanceof PythonLlamaContext ctx) ctx.close();
        else if (target instanceof PythonLlamaBatch batch) batch.close();
        else ((PythonInternalsSampler) target).close();
        return "ok";
      }
      default -> throw new AssertionError("unknown op " + name);
    }
  }

  private static Object capture(java.util.concurrent.Callable<Object> action) {
    try {
      return action.call();
    } catch (PythonException failure) {
      return dict("error", failure.type().pythonName(), "message", failure.getMessage());
    } catch (Exception failure) {
      return dict(
          "error",
          failure.getClass().getSimpleName(),
          "message",
          failure.getMessage() == null ? "" : failure.getMessage());
    }
  }

  private static Object drive(Map<String, Object> kase) {
    List<Object> trace = new ArrayList<>();
    StubPort port = new StubPort(kase, trace);
    List<Object> results = new ArrayList<>();
    Map<String, Object> box = new HashMap<>();
    box.put("trace", trace);
    for (Object op : list(kase.get("ops"))) {
      List<Object> step = list(op);
      results.add(capture(() -> run(kase, step, box, port)));
    }
    return dict("results", results, "trace", trace);
  }
}
