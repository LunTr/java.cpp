package dev.javacpp;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import dev.javacpp.compat.python.PythonInternalsBatch;
import dev.javacpp.compat.python.PythonInternalsPort;
import dev.javacpp.internal.LlamaBatch;
import dev.javacpp.internal.LlamaContextParams;
import dev.javacpp.internal.LlamaLibrary;
import dev.javacpp.internal.LlamaModelParams;
import dev.javacpp.internal.LlamaSamplerApply;
import dev.javacpp.internal.LlamaSamplerChainParams;
import dev.javacpp.internal.LlamaTokenDataArray;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** b11669 implementation of the _internals port; handles stay as JNA pointers. */
public final class PythonNativeInternals implements PythonInternalsPort {
  private final LlamaLibrary library;

  public PythonNativeInternals(LlamaLibrary library) {
    this.library = library;
  }

  public static PythonNativeInternals create() {
    return new PythonNativeInternals(LlamaRuntime.get().library());
  }

  private static Pointer handle(Object value) {
    return (Pointer) value;
  }

  @Override
  public boolean exists(String path) {
    try {
      return Files.exists(Path.of(path));
    } catch (InvalidPathException | SecurityException failure) {
      return false;
    }
  }

  /** Native stdout/stderr suppression is not part of the Java binding scope. */
  @Override
  public void suppressStdoutStderr(boolean disable) {}

  @Override
  public Object modelDefaultParams() {
    return library.llama_model_default_params();
  }

  @Override
  public Object modelLoadFromFile(String path, Object params) {
    return library.llama_model_load_from_file(path, (LlamaModelParams.ByValue) params);
  }

  @Override
  public Object modelGetVocab(Object model) {
    return library.llama_model_get_vocab(handle(model));
  }

  @Override
  public void modelFree(Object model) {
    library.llama_model_free(handle(model));
  }

  @Override
  public int modelNCtxTrain(Object model) {
    return library.llama_model_n_ctx_train(handle(model));
  }

  @Override
  public void modelDesc(Object model, byte[] buffer) {
    library.llama_model_desc(handle(model), buffer, buffer.length);
  }

  @Override
  public int modelMetaCount(Object model) {
    return library.llama_model_meta_count(handle(model));
  }

  @Override
  public int modelMetaKeyByIndex(Object model, int index, byte[] buffer) {
    return library.llama_model_meta_key_by_index(handle(model), index, buffer, buffer.length);
  }

  @Override
  public int modelMetaValStrByIndex(Object model, int index, byte[] buffer) {
    return library.llama_model_meta_val_str_by_index(handle(model), index, buffer, buffer.length);
  }

  @Override
  public int vocabBos(Object vocab) {
    return library.llama_vocab_bos(handle(vocab));
  }

  @Override
  public int tokenize(
      Object vocab, byte[] text, int[] tokens, int capacity, boolean addBos, boolean special) {
    return library.llama_tokenize(
        handle(vocab),
        text,
        text.length,
        tokens,
        capacity,
        (byte) (addBos ? 1 : 0),
        (byte) (special ? 1 : 0));
  }

  @Override
  public int tokenToPiece(Object vocab, int token, byte[] buffer, int lstrip, boolean special) {
    return library.llama_token_to_piece(
        handle(vocab), token, buffer, buffer.length, lstrip, (byte) (special ? 1 : 0));
  }

  @Override
  public int vocabType(Object vocab) {
    return library.llama_vocab_type(handle(vocab));
  }

  @Override
  public double modelRopeFreqScaleTrain(Object model) {
    return library.llama_model_rope_freq_scale_train(handle(model));
  }

  @Override
  public String vocabGetText(Object vocab, int token) {
    return library.llama_vocab_get_text(handle(vocab), token);
  }

  @Override
  public double vocabGetScore(Object vocab, int token) {
    return library.llama_vocab_get_score(handle(vocab), token);
  }

  @Override
  public int vocabGetAttr(Object vocab, int token) {
    return library.llama_vocab_get_attr(handle(vocab), token);
  }

  @Override
  public int vocabEos(Object vocab) {
    return library.llama_vocab_eos(handle(vocab));
  }

  @Override
  public int vocabSep(Object vocab) {
    return library.llama_vocab_sep(handle(vocab));
  }

  @Override
  public int vocabNl(Object vocab) {
    return library.llama_vocab_nl(handle(vocab));
  }

  @Override
  public int vocabFimPre(Object vocab) {
    return library.llama_vocab_fim_pre(handle(vocab));
  }

  @Override
  public int vocabFimSuf(Object vocab) {
    return library.llama_vocab_fim_suf(handle(vocab));
  }

  @Override
  public int vocabFimMid(Object vocab) {
    return library.llama_vocab_fim_mid(handle(vocab));
  }

  @Override
  public int vocabEot(Object vocab) {
    return library.llama_vocab_eot(handle(vocab));
  }

  @Override
  public boolean vocabGetAddBos(Object vocab) {
    return library.llama_vocab_get_add_bos(handle(vocab)) != 0;
  }

  @Override
  public boolean vocabGetAddEos(Object vocab) {
    return library.llama_vocab_get_add_eos(handle(vocab)) != 0;
  }

  @Override
  public Object contextDefaultParams() {
    return library.llama_context_default_params();
  }

  @Override
  public Object initFromModel(Object model, Object params) {
    return library.llama_init_from_model(handle(model), (LlamaContextParams.ByValue) params);
  }

  @Override
  public Object getMemory(Object context) {
    return library.llama_get_memory(handle(context));
  }

  @Override
  public void memoryClear(Object memory, boolean data) {
    library.llama_memory_clear(handle(memory), (byte) (data ? 1 : 0));
  }

  @Override
  public boolean memorySeqRm(Object memory, int seqId, int p0, int p1) {
    return library.llama_memory_seq_rm(handle(memory), seqId, p0, p1) != 0;
  }

  @Override
  public void memorySeqCp(Object memory, int seqIdSrc, int seqIdDst, int p0, int p1) {
    library.llama_memory_seq_cp(handle(memory), seqIdSrc, seqIdDst, p0, p1);
  }

  @Override
  public void memorySeqKeep(Object memory, int seqId) {
    library.llama_memory_seq_keep(handle(memory), seqId);
  }

  @Override
  public void memorySeqAdd(Object memory, int seqId, int p0, int p1, int shift) {
    library.llama_memory_seq_add(handle(memory), seqId, p0, p1, shift);
  }

  @Override
  public long stateGetSize(Object context) {
    return library.llama_state_get_size(handle(context));
  }

  @Override
  public int encode(Object context, PythonInternalsBatch batch) {
    return library.llama_encode(handle(context), ((NativeBatch) batch).value());
  }

  @Override
  public void setNThreads(Object context, int nThreads, int nThreadsBatch) {
    library.llama_set_n_threads(handle(context), nThreads, nThreadsBatch);
  }

  @Override
  public Object getLogits(Object context) {
    return library.llama_get_logits(handle(context));
  }

  @Override
  public Object getLogitsIth(Object context, int index) {
    return library.llama_get_logits_ith(handle(context), index);
  }

  @Override
  public Object getEmbeddings(Object context) {
    return library.llama_get_embeddings(handle(context));
  }

  @Override
  public Object getEmbeddingsIth(Object context, int index) {
    return library.llama_get_embeddings_ith(handle(context), index);
  }

  @Override
  public Object getEmbeddingsSeq(Object context, int seqId) {
    return library.llama_get_embeddings_seq(handle(context), seqId);
  }

  @Override
  public int poolingType(Object context) {
    return library.llama_pooling_type(handle(context));
  }

  @Override
  public void perfContextReset(Object context) {
    library.llama_perf_context_reset(handle(context));
  }

  @Override
  public void perfContextPrint(Object context) {
    library.llama_perf_context_print(handle(context));
  }

  @Override
  public void contextFree(Object context) {
    library.llama_free(handle(context));
  }

  @Override
  public int decode(Object context, PythonInternalsBatch batch) {
    return library.llama_decode(handle(context), ((NativeBatch) batch).value());
  }

  @Override
  public PythonInternalsBatch batchInit(int nTokens, int embd, int nSeqMax) {
    LlamaBatch.ByValue value = new LlamaBatch.ByValue();
    LlamaBatch created = library.llama_batch_init(nTokens, embd, nSeqMax);
    created.read();
    value.n_tokens = created.n_tokens;
    value.token = created.token;
    value.embd = created.embd;
    value.pos = created.pos;
    value.n_seq_id = created.n_seq_id;
    value.seq_id = created.seq_id;
    value.logits = created.logits;
    return new NativeBatch(value, nTokens);
  }

  @Override
  public void batchFree(PythonInternalsBatch batch) {
    library.llama_batch_free(((NativeBatch) batch).value());
  }

  @Override
  public Object samplerChainDefaultParams() {
    return library.llama_sampler_chain_default_params();
  }

  @Override
  public Object samplerChainInit(Object params) {
    return library.llama_sampler_chain_init((LlamaSamplerChainParams.ByValue) params);
  }

  @Override
  public void samplerChainAdd(Object chain, Object sampler) {
    library.llama_sampler_chain_add(handle(chain), handle(sampler));
  }

  @Override
  public Object samplerChainRemove(Object chain, int index) {
    return library.llama_sampler_chain_remove(handle(chain), index);
  }

  @Override
  public Object samplerInitGreedy() {
    return library.llama_sampler_init_greedy();
  }

  @Override
  public Object samplerInitDist(long seed) {
    return library.llama_sampler_init_dist((int) seed);
  }

  @Override
  public Object samplerInitTopK(int k) {
    return library.llama_sampler_init_top_k(k);
  }

  @Override
  public Object samplerInitTopP(double p, long minKeep) {
    return library.llama_sampler_init_top_p((float) p, minKeep);
  }

  @Override
  public Object samplerInitMinP(double p, long minKeep) {
    return library.llama_sampler_init_min_p((float) p, minKeep);
  }

  @Override
  public Object samplerInitTemp(double temperature) {
    return library.llama_sampler_init_temp((float) temperature);
  }

  @Override
  public Object samplerInitPenalties(
      int nVocab, int penaltyLastN, double repeat, double freq, double present) {
    return library.llama_sampler_init_penalties(
        nVocab, penaltyLastN, (float) repeat, (float) freq, (float) present);
  }

  @Override
  public long samplerGetSeed(Object sampler) {
    return Integer.toUnsignedLong(library.llama_sampler_get_seed(handle(sampler)));
  }

  @Override
  public int samplerSample(Object sampler, Object context, int index) {
    return library.llama_sampler_sample(handle(sampler), handle(context), index);
  }

  @Override
  public void samplerFree(Object sampler) {
    library.llama_sampler_free(handle(sampler));
  }

  @Override
  public int samplerChainN(Object chain) {
    return library.llama_sampler_chain_n(handle(chain));
  }

  @Override
  public void samplerAccept(Object sampler, int token) {
    library.llama_sampler_accept(handle(sampler), token);
  }

  @Override
  public void samplerReset(Object sampler) {
    library.llama_sampler_reset(handle(sampler));
  }

  @Override
  public Object samplerClone(Object sampler) {
    return library.llama_sampler_clone(handle(sampler));
  }

  @Override
  public Object samplerInitTypical(double p, long minKeep) {
    return library.llama_sampler_init_typical((float) p, minKeep);
  }

  @Override
  public Object samplerInitTempExt(double t, double delta, double exponent) {
    return library.llama_sampler_init_temp_ext((float) t, (float) delta, (float) exponent);
  }

  @Override
  public Object samplerInitXtc(double p, double t, long minKeep, long seed) {
    return library.llama_sampler_init_xtc((float) p, (float) t, minKeep, (int) seed);
  }

  @Override
  public Object samplerInitTopNSigma(double n) {
    return library.llama_sampler_init_top_n_sigma((float) n);
  }

  @Override
  public Object samplerInitMirostat(int nVocab, long seed, double tau, double eta, int m) {
    return library.llama_sampler_init_mirostat(nVocab, (int) seed, (float) tau, (float) eta, m);
  }

  @Override
  public Object samplerInitMirostatV2(long seed, double tau, double eta) {
    return library.llama_sampler_init_mirostat_v2((int) seed, (float) tau, (float) eta);
  }

  @Override
  public Object samplerInitGrammar(Object vocab, byte[] grammar, byte[] root) {
    return library.llama_sampler_init_grammar(handle(vocab), utf8(grammar), utf8(root));
  }

  @Override
  public Object samplerInitGrammarLazyPatterns(
      Object vocab,
      byte[] grammar,
      byte[] root,
      List<byte[]> triggerPatterns,
      List<Integer> triggerTokens) {
    String[] patterns = new String[triggerPatterns.size()];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = utf8(triggerPatterns.get(i));
    }
    int[] tokens = triggerTokens.stream().mapToInt(Integer::intValue).toArray();
    return library.llama_sampler_init_grammar_lazy_patterns(
        handle(vocab), utf8(grammar), utf8(root), patterns, patterns.length, tokens, tokens.length);
  }

  @Override
  public Object samplerInitDry(
      Object vocab,
      double dryMultiplier,
      double dryBase,
      int dryAllowedLength,
      int dryPenaltyLastN,
      List<byte[]> seqBreakers) {
    String[] breakers = new String[seqBreakers.size()];
    for (int i = 0; i < breakers.length; i++) {
      breakers[i] = utf8(seqBreakers.get(i));
    }
    return library.llama_sampler_init_dry(
        handle(vocab),
        (float) dryMultiplier,
        (float) dryBase,
        dryAllowedLength,
        dryPenaltyLastN,
        breakers,
        breakers.length);
  }

  @Override
  public Object samplerInitLogitBias(int nVocab, int[] tokens, double[] biases) {
    Memory buffer = new Memory(8L * tokens.length);
    for (int i = 0; i < tokens.length; i++) {
      buffer.setInt(8L * i, tokens[i]);
      buffer.setFloat(8L * i + 4, (float) biases[i]);
    }
    return library.llama_sampler_init_logit_bias(nVocab, tokens.length, buffer);
  }

  @Override
  public Object samplerInitInfill(Object vocab) {
    return library.llama_sampler_init_infill(handle(vocab));
  }

  private static String utf8(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }

  @Override
  public Object newSamplerInterface() {
    return new NativeSamplerI();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object samplerIFunction(String field, Object arg) {
    // Python wraps a literal 0 for the optional slots; JNA uses NULL for those.
    if ("apply".equals(field)) {
      java.util.function.Consumer<Object> apply = (java.util.function.Consumer<Object>) arg;
      return (LlamaSamplerApply) (sampler, curP) -> apply.accept(curP);
    }
    return Pointer.NULL;
  }

  @Override
  public void samplerIAssign(Object samplerI, String field, Object value) {
    ((NativeSamplerI) samplerI).set(field, value);
  }

  @Override
  public Object newLlamaSampler() {
    return new NativeLlamaSampler();
  }

  @Override
  public Object pointerOf(Object target) {
    return ((com.sun.jna.Structure) target).getPointer();
  }

  @Override
  public void samplerAssign(Object sampler, String field, Object value) {
    NativeLlamaSampler target = (NativeLlamaSampler) sampler;
    if ("iface".equals(field)) {
      target.iface = (Pointer) value;
    } else if ("ctx".equals(field)) {
      target.ctx = (Pointer) value;
    }
  }

  @Override
  public Object candidatesData(int nVocab) {
    return new Memory(12L * nVocab);
  }

  @Override
  public Object tokenDataArray(Object data, int size, boolean sorted) {
    LlamaTokenDataArray candidates = new LlamaTokenDataArray();
    candidates.data = (Pointer) data;
    candidates.size = size;
    candidates.sorted = (byte) (sorted ? 1 : 0);
    candidates.write();
    return candidates;
  }

  @Override
  public void candidatesStore(Object data, int[] ids, float[] logits, float[] probs) {
    Pointer target = (Pointer) data;
    for (int i = 0; i < ids.length; i++) {
      target.setInt(12L * i, ids[i]);
      target.setFloat(12L * i + 4, logits[i]);
      target.setFloat(12L * i + 8, probs[i]);
    }
  }

  @Override
  public void candidatesAssign(Object candidates, String field, Object value) {
    LlamaTokenDataArray target = (LlamaTokenDataArray) candidates;
    if ("sorted".equals(field)) {
      target.sorted = (byte) ((Boolean) value ? 1 : 0);
    } else if ("size".equals(field)) {
      target.size = ((Number) value).longValue();
    }
    target.write();
  }

  /** llama_sampler_i with the callback fields used by CustomSampler; optional slots stay NULL. */
  private static final class NativeSamplerI extends com.sun.jna.Structure {
    public Pointer name;
    public Pointer accept;
    public com.sun.jna.Callback apply;
    public Pointer reset;
    public Pointer clone;
    public Pointer free;

    @Override
    protected java.util.List<String> getFieldOrder() {
      return java.util.List.of("name", "accept", "apply", "reset", "clone", "free");
    }

    private void set(String field, Object value) {
      switch (field) {
        case "apply" -> apply = (com.sun.jna.Callback) value;
        case "name" -> name = (Pointer) value;
        case "accept" -> accept = (Pointer) value;
        case "reset" -> reset = (Pointer) value;
        case "clone" -> clone = (Pointer) value;
        case "free" -> free = (Pointer) value;
        default -> throw new IllegalArgumentException(field);
      }
    }
  }

  /** llama_sampler { iface, ctx }. */
  private static final class NativeLlamaSampler extends com.sun.jna.Structure {
    public Pointer iface;
    public Pointer ctx;

    @Override
    protected java.util.List<String> getFieldOrder() {
      return java.util.List.of("iface", "ctx");
    }
  }

  private static final class NativeBatch implements PythonInternalsBatch {
    private final LlamaBatch.ByValue value;
    private final int capacity;

    private NativeBatch(LlamaBatch.ByValue value, int capacity) {
      this.value = value;
      this.capacity = capacity;
    }

    private LlamaBatch.ByValue value() {
      return value;
    }

    private int index(int index) {
      return index < 0 ? capacity + index : index;
    }

    @Override
    public int capacity() {
      return capacity;
    }

    @Override
    public int nTokens() {
      return value.n_tokens;
    }

    @Override
    public void nTokens(int count) {
      value.n_tokens = count;
    }

    @Override
    public void token(int index, int token) {
      value.token.setInt(4L * index(index), token);
    }

    @Override
    public void pos(int index, int position) {
      value.pos.setInt(4L * index(index), position);
    }

    @Override
    public void seqId(int index, int slot, int sequence) {
      value.seq_id.getPointer(8L * index(index)).setInt(4L * slot, sequence);
    }

    @Override
    public void nSeqId(int index, int count) {
      value.n_seq_id.setInt(4L * index(index), count);
    }

    @Override
    public void logits(int index, boolean output) {
      value.logits.setByte(index(index), (byte) (output ? 1 : 0));
    }
  }
}
