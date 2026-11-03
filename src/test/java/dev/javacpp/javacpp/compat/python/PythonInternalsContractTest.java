package dev.javacpp.compat.python;

import static dev.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import dev.javacpp.python.PythonErrorType;
import dev.javacpp.python.PythonException;
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
    public int vocabType(Object vocab) {
      trace.add(List.of("llama_vocab_type", name(vocab)));
      return i(kase.getOrDefault("vocabType", 2));
    }

    @Override
    public double modelRopeFreqScaleTrain(Object model) {
      trace.add(List.of("llama_model_rope_freq_scale_train", name(model)));
      return ((Number) kase.getOrDefault("ropeScale", 0.25)).doubleValue();
    }

    @Override
    public String vocabGetText(Object vocab, int token) {
      trace.add(List.of("llama_vocab_get_text", name(vocab), token));
      return new String(
          hex((String) kase.getOrDefault("textHex", "e4bda0")), StandardCharsets.UTF_8);
    }

    @Override
    public double vocabGetScore(Object vocab, int token) {
      trace.add(List.of("llama_vocab_get_score", name(vocab), token));
      return ((Number) kase.getOrDefault("score", -1.5)).doubleValue();
    }

    @Override
    public int vocabGetAttr(Object vocab, int token) {
      trace.add(List.of("llama_vocab_get_attr", name(vocab), token));
      return i(kase.getOrDefault("attr", 3));
    }

    @Override
    public int vocabEos(Object vocab) {
      trace.add(List.of("llama_vocab_eos", name(vocab)));
      return i(kase.getOrDefault("llama_vocab_eos", 0));
    }

    @Override
    public int vocabSep(Object vocab) {
      trace.add(List.of("llama_vocab_sep", name(vocab)));
      return i(kase.getOrDefault("llama_vocab_sep", 0));
    }

    @Override
    public int vocabNl(Object vocab) {
      trace.add(List.of("llama_vocab_nl", name(vocab)));
      return i(kase.getOrDefault("llama_vocab_nl", 0));
    }

    @Override
    public int vocabFimPre(Object vocab) {
      trace.add(List.of("llama_vocab_fim_pre", name(vocab)));
      return i(kase.getOrDefault("llama_vocab_fim_pre", 0));
    }

    @Override
    public int vocabFimSuf(Object vocab) {
      trace.add(List.of("llama_vocab_fim_suf", name(vocab)));
      return i(kase.getOrDefault("llama_vocab_fim_suf", 0));
    }

    @Override
    public int vocabFimMid(Object vocab) {
      trace.add(List.of("llama_vocab_fim_mid", name(vocab)));
      return i(kase.getOrDefault("llama_vocab_fim_mid", 0));
    }

    @Override
    public int vocabEot(Object vocab) {
      trace.add(List.of("llama_vocab_eot", name(vocab)));
      return i(kase.getOrDefault("llama_vocab_eot", 0));
    }

    @Override
    public boolean vocabGetAddBos(Object vocab) {
      trace.add(List.of("llama_vocab_get_add_bos", name(vocab)));
      return flag("addBos", true);
    }

    @Override
    public boolean vocabGetAddEos(Object vocab) {
      trace.add(List.of("llama_vocab_get_add_eos", name(vocab)));
      return flag("addEos", false);
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
    public boolean memorySeqRm(Object memory, int seqId, int p0, int p1) {
      trace.add(List.of("llama_memory_seq_rm", name(memory), seqId, p0, p1));
      return queues.containsKey("seqRm") ? (boolean) queue("seqRm") : true;
    }

    @Override
    public void memorySeqCp(Object memory, int seqIdSrc, int seqIdDst, int p0, int p1) {
      trace.add(List.of("llama_memory_seq_cp", name(memory), seqIdSrc, seqIdDst, p0, p1));
    }

    @Override
    public void memorySeqKeep(Object memory, int seqId) {
      trace.add(List.of("llama_memory_seq_keep", name(memory), seqId));
    }

    @Override
    public void memorySeqAdd(Object memory, int seqId, int p0, int p1, int shift) {
      trace.add(List.of("llama_memory_seq_add", name(memory), seqId, p0, p1, shift));
    }

    @Override
    public long stateGetSize(Object context) {
      trace.add(List.of("llama_state_get_size", name(context)));
      return ((Number) kase.getOrDefault("stateSize", 1024)).longValue();
    }

    @Override
    public int encode(Object context, PythonInternalsBatch batch) {
      trace.add(List.of("llama_encode", name(context), ((StubBatch) batch).snapshot()));
      return i(queue("encode"));
    }

    @Override
    public void setNThreads(Object context, int nThreads, int nThreadsBatch) {
      trace.add(List.of("llama_set_n_threads", name(context), nThreads, nThreadsBatch));
    }

    @Override
    public int poolingType(Object context) {
      trace.add(List.of("llama_pooling_type", name(context)));
      return i(kase.getOrDefault("pooling", 1));
    }

    @Override
    public Object getLogits(Object context) {
      trace.add(List.of("llama_get_logits", name(context)));
      return new Handle("get_logits_ptr");
    }

    @Override
    public Object getLogitsIth(Object context, int index) {
      trace.add(List.of("llama_get_logits_ith", name(context), index));
      return new Handle("get_logits_ith_ptr");
    }

    @Override
    public Object getEmbeddings(Object context) {
      trace.add(List.of("llama_get_embeddings", name(context)));
      return new Handle("get_embeddings_ptr");
    }

    @Override
    public Object getEmbeddingsIth(Object context, int index) {
      trace.add(List.of("llama_get_embeddings_ith", name(context), index));
      return new Handle("get_embeddings_ith_ptr");
    }

    @Override
    public Object getEmbeddingsSeq(Object context, int seqId) {
      trace.add(List.of("llama_get_embeddings_seq", name(context), seqId));
      return new Handle("get_embeddings_seq_ptr");
    }

    @Override
    public void perfContextReset(Object context) {
      trace.add(List.of("llama_perf_context_reset", name(context)));
    }

    @Override
    public void perfContextPrint(Object context) {
      trace.add(List.of("llama_perf_context_print", name(context)));
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

    @Override
    public int samplerChainN(Object chain) {
      trace.add(List.of("llama_sampler_chain_n", name(chain)));
      return i(kase.getOrDefault("chainN", 4));
    }

    @Override
    public void samplerAccept(Object sampler, int token) {
      trace.add(List.of("llama_sampler_accept", name(sampler), token));
    }

    @Override
    public void samplerReset(Object sampler) {
      trace.add(List.of("llama_sampler_reset", name(sampler)));
    }

    @Override
    public Object samplerClone(Object sampler) {
      trace.add(List.of("llama_sampler_clone", name(sampler)));
      return spawn("llama_sampler_clone");
    }

    private Object init(String name, Object... args) {
      List<Object> entry = new ArrayList<>(List.of(name));
      entry.addAll(List.of(args));
      trace.add(entry);
      return spawn(name);
    }

    @Override
    public Object samplerInitTypical(double p, long minKeep) {
      return init("llama_sampler_init_typical", p, minKeep);
    }

    @Override
    public Object samplerInitTempExt(double t, double delta, double exponent) {
      return init("llama_sampler_init_temp_ext", t, delta, exponent);
    }

    @Override
    public Object samplerInitXtc(double p, double t, long minKeep, long seed) {
      return init("llama_sampler_init_xtc", p, t, minKeep, seed);
    }

    @Override
    public Object samplerInitTopNSigma(double n) {
      return init("llama_sampler_init_top_n_sigma", n);
    }

    @Override
    public Object samplerInitMirostat(int nVocab, long seed, double tau, double eta, int m) {
      return init("llama_sampler_init_mirostat", nVocab, seed, tau, eta, m);
    }

    @Override
    public Object samplerInitMirostatV2(long seed, double tau, double eta) {
      return init("llama_sampler_init_mirostat_v2", seed, tau, eta);
    }

    private static String hexBytes(byte[] data) {
      return HexFormat.of().formatHex(data);
    }

    @Override
    public Object samplerInitGrammar(Object vocab, byte[] grammar, byte[] root) {
      return init("llama_sampler_init_grammar", name(vocab), hexBytes(grammar), hexBytes(root));
    }

    @Override
    public Object samplerInitGrammarLazyPatterns(
        Object vocab,
        byte[] grammar,
        byte[] root,
        List<byte[]> triggerPatterns,
        List<Integer> triggerTokens) {
      return init(
          "llama_sampler_init_grammar_lazy_patterns",
          name(vocab),
          hexBytes(grammar),
          hexBytes(root),
          "buf" + triggerPatterns.size(),
          triggerPatterns.size(),
          "buf" + triggerTokens.size(),
          triggerTokens.size());
    }

    @Override
    public Object samplerInitDry(
        Object vocab,
        double dryMultiplier,
        double dryBase,
        int dryAllowedLength,
        int dryPenaltyLastN,
        List<byte[]> seqBreakers) {
      return init(
          "llama_sampler_init_dry",
          name(vocab),
          dryMultiplier,
          dryBase,
          dryAllowedLength,
          dryPenaltyLastN,
          "buf" + seqBreakers.size(),
          seqBreakers.size());
    }

    @Override
    public Object samplerInitLogitBias(int nVocab, int[] tokens, double[] biases) {
      return init("llama_sampler_init_logit_bias", nVocab, tokens.length, "buf" + tokens.length);
    }

    @Override
    public Object samplerInitInfill(Object vocab) {
      return init("llama_sampler_init_infill", name(vocab));
    }

    @Override
    public Object newSamplerInterface() {
      trace.add(List.of("llama_sampler_i"));
      return new Handle("sampler_i");
    }

    @Override
    public Object samplerIFunction(String field, Object arg) {
      trace.add(List.of("llama_sampler_i_" + field, arg instanceof Number ? arg : arg.toString()));
      return new Handle("cfunc_" + field);
    }

    @Override
    public void samplerIAssign(Object samplerI, String field, Object value) {}

    @Override
    public Object newLlamaSampler() {
      trace.add(List.of("llama_sampler"));
      return new Handle("sampler");
    }

    @Override
    public Object pointerOf(Object target) {
      return new Handle("ptr:" + name(target));
    }

    @Override
    public void samplerAssign(Object sampler, String field, Object value) {}

    @Override
    public Object candidatesData(int nVocab) {
      return new Handle("data_ptr");
    }

    @Override
    public Object tokenDataArray(Object data, int size, boolean sorted) {
      trace.add(List.of("llama_token_data_array", name(data), size, sorted));
      return new Handle("candidates");
    }

    @Override
    public void candidatesStore(Object data, int[] ids, float[] logits, float[] probs) {}

    @Override
    public void candidatesAssign(Object candidates, String field, Object value) {}
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

  /** llama_grammar.LlamaGrammar stand-in whose reset() is observable on the trace. */
  private static final class FakeGrammar implements PythonGrammar {
    private final List<Object> trace;

    private FakeGrammar(List<Object> trace) {
      this.trace = trace;
    }

    @Override
    public String grammar() {
      return "root ::= 'ab'";
    }

    @Override
    public String root() {
      return "root";
    }

    @Override
    public void reset() {
      trace.add(List.of("grammar_reset"));
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
      case "batchAdd" -> {
        ((PythonLlamaBatch) box.get("batch"))
            .addSequence(
                list(step.get(1)).stream().map(PythonInternalsContractTest::i).toList(),
                i(step.get(2)),
                (boolean) step.get(3));
        return "ok";
      }
      case "batchDump" -> {
        List<Object> snapshot =
            ((StubBatch) ((PythonLlamaBatch) box.get("batch")).batch()).snapshot();
        return snapshot.subList(1, snapshot.size());
      }
      case "vocabType" -> {
        return ((PythonLlamaModel) box.get("model")).vocabType();
      }
      case "ropeScale" -> {
        return ((PythonLlamaModel) box.get("model")).ropeFreqScaleTrain();
      }
      case "getTensor" -> {
        return ((PythonLlamaModel) box.get("model")).getTensor((String) step.get(1));
      }
      case "tokenText" -> {
        return ((PythonLlamaModel) box.get("model")).tokenGetText(i(step.get(1)));
      }
      case "tokenScore" -> {
        return ((PythonLlamaModel) box.get("model")).tokenGetScore(i(step.get(1)));
      }
      case "tokenAttr" -> {
        return ((PythonLlamaModel) box.get("model")).tokenGetAttr(i(step.get(1)));
      }
      case "tokenCls" -> {
        return ((PythonLlamaModel) box.get("model")).tokenCls();
      }
      case "tokenSep" -> {
        return ((PythonLlamaModel) box.get("model")).tokenSep();
      }
      case "tokenNl" -> {
        return ((PythonLlamaModel) box.get("model")).tokenNl();
      }
      case "tokenPrefix" -> {
        return ((PythonLlamaModel) box.get("model")).tokenPrefix();
      }
      case "tokenMiddle" -> {
        return ((PythonLlamaModel) box.get("model")).tokenMiddle();
      }
      case "tokenSuffix" -> {
        return ((PythonLlamaModel) box.get("model")).tokenSuffix();
      }
      case "tokenEot" -> {
        return ((PythonLlamaModel) box.get("model")).tokenEot();
      }
      case "addBos" -> {
        return ((PythonLlamaModel) box.get("model")).addBosToken();
      }
      case "addEos" -> {
        return ((PythonLlamaModel) box.get("model")).addEosToken();
      }
      case "pooling" -> {
        return ((PythonLlamaContext) box.get("ctx")).poolingType();
      }
      case "kvRm" -> {
        return ((PythonLlamaContext) box.get("ctx"))
            .kvCacheSeqRm(i(step.get(1)), i(step.get(2)), i(step.get(3)));
      }
      case "kvCp" -> {
        ((PythonLlamaContext) box.get("ctx"))
            .kvCacheSeqCp(i(step.get(1)), i(step.get(2)), i(step.get(3)), i(step.get(4)));
        return "ok";
      }
      case "kvKeep" -> {
        ((PythonLlamaContext) box.get("ctx")).kvCacheSeqKeep(i(step.get(1)));
        return "ok";
      }
      case "kvShift" -> {
        ((PythonLlamaContext) box.get("ctx"))
            .kvCacheSeqShift(i(step.get(1)), i(step.get(2)), i(step.get(3)), i(step.get(4)));
        return "ok";
      }
      case "stateSize" -> {
        return ((PythonLlamaContext) box.get("ctx")).getStateSize();
      }
      case "encode" -> {
        ((PythonLlamaContext) box.get("ctx")).encode((PythonLlamaBatch) box.get("batch"));
        return "ok";
      }
      case "nThreads" -> {
        ((PythonLlamaContext) box.get("ctx")).setNThreads(i(step.get(1)), i(step.get(2)));
        return "ok";
      }
      case "logits" -> {
        return name(((PythonLlamaContext) box.get("ctx")).getLogits());
      }
      case "logitsIth" -> {
        return name(((PythonLlamaContext) box.get("ctx")).getLogitsIth(i(step.get(1))));
      }
      case "emb" -> {
        return name(((PythonLlamaContext) box.get("ctx")).getEmbeddings());
      }
      case "embIth" -> {
        return name(((PythonLlamaContext) box.get("ctx")).getEmbeddingsIth(i(step.get(1))));
      }
      case "embSeq" -> {
        return name(((PythonLlamaContext) box.get("ctx")).getEmbeddingsSeq(i(step.get(1))));
      }
      case "resetTimings" -> {
        ((PythonLlamaContext) box.get("ctx")).resetTimings();
        return "ok";
      }
      case "printTimings" -> {
        ((PythonLlamaContext) box.get("ctx")).printTimings();
        return "ok";
      }
      case "deprecated" -> {
        return deprecated((PythonLlamaContext) box.get("ctx"), (String) step.get(1), step);
      }
      case "normalize" -> {
        return PythonInternals.normalizeEmbedding(
            list(step.get(1)).stream().map(v -> (Number) v).toList());
      }
      case "tda" -> {
        box.put("tda", new PythonLlamaTokenDataArray(port, i(step.get(1))));
        return "ok";
      }
      case "tdaCopy" -> {
        List<Object> values = list(step.get(1));
        float[] logits = new float[values.size()];
        for (int n = 0; n < values.size(); n++) {
          logits[n] = ((Number) values.get(n)).floatValue();
        }
        ((PythonLlamaTokenDataArray) box.get("tda")).copyLogits(logits);
        return "ok";
      }
      case "tdaDump" -> {
        PythonLlamaTokenDataArray tda = (PythonLlamaTokenDataArray) box.get("tda");
        List<Object> ids = new ArrayList<>();
        for (int v : tda.ids()) ids.add(v);
        List<Object> logits = new ArrayList<>();
        for (float v : tda.logits()) logits.add((double) v);
        List<Object> probs = new ArrayList<>();
        for (float v : tda.probs()) probs.add((double) v);
        return List.of(ids, logits, probs, tda.sorted(), tda.size());
      }
      case "sctx" -> {
        box.put("sctx", new PythonLlamaSamplingContext(new PythonLlamaSamplingParams()));
        return "ok";
      }
      case "sctxGrammar" -> {
        ((PythonLlamaSamplingContext) box.get("sctx"))
            .setGrammar(new FakeGrammar((List<Object>) box.get("trace")));
        return "ok";
      }
      case "sctxPrev" -> {
        ((PythonLlamaSamplingContext) box.get("sctx"))
            .prev()
            .addAll(list(step.get(1)).stream().map(PythonInternalsContractTest::i).toList());
        return "ok";
      }
      case "sctxReset" -> {
        ((PythonLlamaSamplingContext) box.get("sctx")).reset();
        return "ok";
      }
      case "sctxCp" -> {
        PythonLlamaSamplingContext sctx = (PythonLlamaSamplingContext) box.get("sctx");
        PythonLlamaSamplingContext copy = sctx.cp();
        box.put("sctx2", copy);
        copy.prev().add(99);
        return List.of(
            new ArrayList<>(sctx.prev()),
            new ArrayList<>(copy.prev()),
            copy.params() == sctx.params());
      }
      case "sctxLast" -> {
        return ((PythonLlamaSamplingContext) box.get("sctx")).last();
      }
      case "sctxPrevStr" -> {
        return ((PythonLlamaSamplingContext) box.get("sctx"))
            .prevStr((PythonLlamaContext) box.get("ctx"), i(step.get(1)));
      }
      case "sctxSample" -> {
        return ((PythonLlamaSamplingContext) box.get("sctx"))
            .sample((PythonLlamaContext) box.get("ctx"), i(step.get(1)), null);
      }
      case "sctxAccept" -> {
        PythonLlamaSamplingContext sctx = (PythonLlamaSamplingContext) box.get("sctx");
        sctx.accept((PythonLlamaContext) box.get("ctx"), i(step.get(1)), (boolean) step.get(2));
        return new ArrayList<>(sctx.prev());
      }
      case "custom" -> {
        box.put("custom", new PythonCustomSampler(port, curP -> {}));
        return "ok";
      }
      case "customGet" -> {
        return name(((PythonCustomSampler) box.get("custom")).getSampler());
      }
      case "typical" -> {
        if (step.size() > 2) {
          ((PythonInternalsSampler) box.get("sampler"))
              .addTypical(((Number) step.get(1)).doubleValue(), ((Number) step.get(2)).longValue());
        } else {
          ((PythonInternalsSampler) box.get("sampler"))
              .addTypical(((Number) step.get(1)).doubleValue());
        }
        return null;
      }
      case "tempExt" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addTempExt(
                ((Number) step.get(1)).doubleValue(),
                ((Number) step.get(2)).doubleValue(),
                ((Number) step.get(3)).doubleValue());
        return null;
      }
      case "xtc" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addXtc(
                ((Number) step.get(1)).doubleValue(),
                ((Number) step.get(2)).doubleValue(),
                ((Number) step.get(3)).longValue(),
                ((Number) step.get(4)).longValue());
        return null;
      }
      case "topNSigma" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addTopNSigma(((Number) step.get(1)).doubleValue());
        return null;
      }
      case "mirostat" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addMirostat(
                i(step.get(1)),
                ((Number) step.get(2)).longValue(),
                ((Number) step.get(3)).doubleValue(),
                ((Number) step.get(4)).doubleValue(),
                i(step.get(5)));
        return null;
      }
      case "mirostatV2" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addMirostatV2(
                ((Number) step.get(1)).longValue(),
                ((Number) step.get(2)).doubleValue(),
                ((Number) step.get(3)).doubleValue());
        return null;
      }
      case "grammarAdd" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addGrammar(
                (PythonLlamaModel) box.get("model"),
                new FakeGrammar((List<Object>) box.get("trace")));
        return "ok";
      }
      case "grammarLazy" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addGrammarLazyPatterns(
                (PythonLlamaModel) box.get("model"),
                new FakeGrammar((List<Object>) box.get("trace")),
                list(step.get(1)).stream().map(v -> (String) v).toList(),
                list(step.get(2)).stream().map(PythonInternalsContractTest::i).toList());
        return "ok";
      }
      case "dry" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addDry(
                (PythonLlamaModel) box.get("model"),
                ((Number) step.get(1)).doubleValue(),
                ((Number) step.get(2)).doubleValue(),
                i(step.get(3)),
                i(step.get(4)),
                list(step.get(5)).stream().map(v -> (String) v).toList());
        return "ok";
      }
      case "logitBias" -> {
        Map<Integer, Double> bias = new LinkedHashMap<>();
        for (var entry : map(step.get(2)).entrySet()) {
          bias.put(i(entry.getKey()), ((Number) entry.getValue()).doubleValue());
        }
        ((PythonInternalsSampler) box.get("sampler")).addLogitBias(i(step.get(1)), bias);
        return "ok";
      }
      case "infill" -> {
        ((PythonInternalsSampler) box.get("sampler"))
            .addInfill((PythonLlamaModel) box.get("model"));
        return "ok";
      }
      case "addCustom" -> {
        ((PythonInternalsSampler) box.get("sampler")).addCustom(curP -> {});
        return "ok";
      }
      case "acceptTok" -> {
        ((PythonInternalsSampler) box.get("sampler")).accept(i(step.get(1)));
        return "ok";
      }
      case "resetSampler" -> {
        ((PythonInternalsSampler) box.get("sampler")).reset();
        return "ok";
      }
      case "clone" -> {
        box.put("sampler2", ((PythonInternalsSampler) box.get("sampler")).clone());
        return "ok";
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

  private static Object deprecated(PythonLlamaContext ctx, String name, List<Object> step) {
    switch (name) {
      case "set_rng_seed" -> {
        ctx.setRngSeed(i(step.get(2)));
        return null;
      }
      case "sample_repetition_penalties" -> {
        ctx.sampleRepetitionPenalties(
            step.get(2),
            step.get(3),
            i(step.get(4)),
            ((Number) step.get(5)).doubleValue(),
            ((Number) step.get(6)).doubleValue(),
            ((Number) step.get(7)).doubleValue());
        return null;
      }
      case "sample_softmax" -> {
        ctx.sampleSoftmax(step.get(2));
        return null;
      }
      case "sample_top_k" -> {
        ctx.sampleTopK(step.get(2), i(step.get(3)), i(step.get(4)));
        return null;
      }
      case "sample_top_p" -> {
        ctx.sampleTopP(step.get(2), ((Number) step.get(3)).doubleValue(), i(step.get(4)));
        return null;
      }
      case "sample_min_p" -> {
        ctx.sampleMinP(step.get(2), ((Number) step.get(3)).doubleValue(), i(step.get(4)));
        return null;
      }
      case "sample_typical" -> {
        ctx.sampleTypical(step.get(2), ((Number) step.get(3)).doubleValue(), i(step.get(4)));
        return null;
      }
      case "sample_temp" -> {
        ctx.sampleTemp(step.get(2), ((Number) step.get(3)).doubleValue());
        return null;
      }
      case "sample_grammar" -> {
        ctx.sampleGrammar(step.get(2), step.get(3));
        return null;
      }
      case "sample_token_mirostat" -> {
        ctx.sampleTokenMirostat(
            step.get(2),
            ((Number) step.get(3)).doubleValue(),
            ((Number) step.get(4)).doubleValue(),
            i(step.get(5)),
            step.get(6));
        return null;
      }
      case "sample_token_mirostat_v2" -> {
        ctx.sampleTokenMirostatV2(
            step.get(2),
            ((Number) step.get(3)).doubleValue(),
            ((Number) step.get(4)).doubleValue(),
            step.get(5));
        return null;
      }
      case "sample_token_greedy" -> {
        return ctx.sampleTokenGreedy(step.get(2));
      }
      case "sample_token" -> {
        return ctx.sampleToken(step.get(2));
      }
      case "grammar_accept_token" -> {
        ctx.grammarAcceptToken(step.get(2), i(step.get(3)));
        return null;
      }
      default -> throw new AssertionError("unknown deprecated " + name);
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
