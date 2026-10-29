package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * State, token generation and numerical helpers from Llama in Python 0.3.36. The supplied
 * intc/single arrays remain caller-owned; synchronize access externally. Execution and sampling are
 * explicit ports; the complete Python constructor and completion/chat policies have separate
 * mappings.
 */
public final class PythonLlama implements AutoCloseable {
  private int[] inputIds;
  private float[][] scores;
  private int contextLength;
  private boolean logitsAll;
  private boolean recurrent;
  private boolean hybrid;
  private Supplier<Runnable> memoryAccess;
  private int nTokens;
  private boolean requiresEval;
  private Object seed = BigInteger.ZERO;
  private PythonLlamaCache cache;
  private PythonTokenizerModel tokenizerModel;
  private PythonTokenizer activeTokenizer;
  private io.github.yaulun.javacpp.LlamaModel nativeModel;
  private io.github.yaulun.javacpp.LlamaContext nativeContext;
  private static final java.lang.ref.Cleaner cleaner = java.lang.ref.Cleaner.create();

  private static final class Cleanup implements Runnable {
    private Runnable action;

    public void run() {
      Runnable current = action;
      action = null;
      if (current != null) current.run();
    }
  }

  private final Cleanup cleanup = new Cleanup();
  private final java.lang.ref.Cleaner.Cleanable registration = cleaner.register(this, cleanup);
  private PythonLlamaServices services = new PythonLlamaServices(this);
  private PythonLlamaConfiguration configuration;
  private java.util.function.UnaryOperator<int[]> draftModel;

  public static java.util.Map<String, Object> constructorArguments(
      java.util.Map<String, Object> options) {
    return PythonLlamaDefaults.bind("__init__", options);
  }

  public static PythonLlama load(java.util.Map<String, Object> options) {
    return io.github.yaulun.javacpp.PythonNativeLlama.load(options);
  }

  public void setConfiguration(PythonLlamaConfiguration value) {
    configuration = value;
  }

  public java.util.Map<String, Object> getState() {
    return configuration.getState();
  }

  public void setState(java.util.Map<String, Object> state) {
    configuration.setState(state);
  }

  public void setDraftModel(java.util.function.UnaryOperator<int[]> value) {
    draftModel = value;
  }

  public void replaceBuffers(
      int[] ids,
      float[][] logits,
      int capacity,
      boolean all,
      boolean isRecurrent,
      boolean isHybrid,
      Supplier<Runnable> memory) {
    close();
    inputIds = ids;
    scores = logits;
    contextLength = capacity;
    logitsAll = all;
    recurrent = isRecurrent;
    hybrid = isHybrid;
    memoryAccess = memory;
    nTokens = 0;
    requiresEval = true;
    sampler = null;
    cache = null;
    services = new PythonLlamaServices(this);
  }

  boolean logitsAllEnabled() {
    return logitsAll;
  }

  int configuredContextLength() {
    return contextLength;
  }

  float[] scoreRow(int index) {
    int size = sliceEnd(scores.length), row = index < 0 ? size + index : index;
    if (row < 0 || row >= size)
      throw PythonValues.error(
          PythonErrorType.INDEX_ERROR,
          "index " + index + " is out of bounds for axis 0 with size " + size);
    return scores[row];
  }

  void diagnostic(String text) {
    diagnostics.accept(text);
  }

  public PythonLlamaServices services() {
    return services;
  }

  public Object createCompletion(java.util.Map<String, Object> options) {
    return services.createCompletion(options);
  }

  public Object call(java.util.Map<String, Object> options) {
    return services.call(options);
  }

  public Object createChatCompletion(java.util.Map<String, Object> options) {
    return services.createChatCompletion(options);
  }

  public Object createChatCompletionOpenAiV1(java.util.Map<String, Object> options) {
    return services.createChatCompletionOpenAiV1(options);
  }

  public Object embed(java.util.Map<String, Object> options) {
    return services.embed(options);
  }

  public java.util.Map<String, Object> createEmbedding(java.util.Map<String, Object> options) {
    return services.createEmbedding(options);
  }

  /** Attaches caller-owned resources and wires execution and the default tokenizer. */
  public void attachNative(
      io.github.yaulun.javacpp.LlamaModel model, io.github.yaulun.javacpp.LlamaContext context) {
    nativeModel = Objects.requireNonNull(model);
    nativeContext = Objects.requireNonNull(context);
    setExecution(PythonLlamaExecution.fromContext(context));
    setTokenizerModel(PythonLlamaTokenizer.modelFromVocab(model.vocab()));
    setSamplingBackend(() -> io.github.yaulun.javacpp.PythonNativeSampling.builder(model, context));
    services.setEmbeddingBackend(context.embeddingBackend());
    services.setModelPath(model.info().file().toString());
    services.setCompletionModel(
        new PythonCompletionModel() {
          public int bos() {
            return model.vocab().bos();
          }

          public int eos() {
            return model.vocab().eos();
          }

          public int cls() {
            return model.vocab().cls();
          }

          public int sep() {
            return model.vocab().sep();
          }

          public boolean addBos() {
            return model.vocab().addBos();
          }

          public boolean addEos() {
            return model.vocab().addEos();
          }

          public boolean isEog(int token) {
            return model.vocab().isEog(token);
          }

          public String tokenText(int token) {
            return model.vocab().tokenText(token);
          }

          public String metadata(String key, String fallback) {
            return model.metadata(key).orElse(fallback);
          }

          public int contextLength() {
            return context.contextLength();
          }

          public String modelPath() {
            return model.info().file().toString();
          }

          public void resetTimings() {
            context.embeddingBackend().resetTimings();
          }

          public void printTimings() {
            context.embeddingBackend().printTimings();
          }
        });
  }

  public io.github.yaulun.javacpp.LlamaContext ctx() {
    return nativeContext == null || nativeContext.isClosed() ? null : nativeContext;
  }

  public io.github.yaulun.javacpp.LlamaModel model() {
    return nativeModel == null || nativeModel.isClosed() ? null : nativeModel;
  }

  public int nCtx() {
    return ctx().contextLength();
  }

  public int nEmbd() {
    return model().info().embedding();
  }

  public int nVocab() {
    return model().vocab().size();
  }

  public int tokenEos() {
    return model().vocab().eos();
  }

  public int tokenBos() {
    return model().vocab().bos();
  }

  public int tokenNl() {
    return model().vocab().newline();
  }

  public int poolingType() {
    return ctx().poolingType();
  }

  /** Installs caller-chosen ownership cleanup; the action is consumed even when it throws. */
  public void setCloseAction(Runnable action) {
    cleanup.action = action;
  }

  @Override
  public void close() {
    cleanup.run();
  }

  private PythonLlamaExecution execution;
  private PythonLlamaSampler sampler;
  private Supplier<PythonLlamaSampler> samplerFactory;
  private Supplier<PythonSamplerBuilder> samplingBackend;
  private int lastNTokensSize = 64;

  public void setSamplingBackend(Supplier<PythonSamplerBuilder> value) {
    samplingBackend = value;
  }

  public void setLastNTokensSize(int value) {
    lastNTokensSize = value;
  }

  /** Complete original sampling-chain selection, including ignored legacy options. */
  public PythonLlamaSampler initSampler(java.util.Map<String, Object> arguments) {
    var options = PythonLlamaDefaults.bind("_init_sampler", arguments);
    PythonSamplerBuilder builder = samplingBackend.get();
    var processors = (PythonLogitsProcessorList) options.get("logits_processor");
    if (processors != null)
      builder.add(
          "custom",
          (java.util.function.Consumer<float[]>) values -> applyProcessors(processors, values));
    builder.add(
        "penalties",
        scores.length == 0 ? 0 : scores[0].length,
        lastNTokensSize,
        options.get("repeat_penalty"),
        options.get("frequency_penalty"),
        options.get("presence_penalty"));
    if (options.get("grammar") != null) builder.add("grammar", options.get("grammar"));
    double temperature = PythonNumbers.compareNumber(options.get("temp"), "<", "float");
    Object mirostat = options.get("mirostat_mode");
    if (temperature < 0) builder.add("dist", seed);
    else if (temperature == 0) builder.add("greedy");
    else if (PythonNumbers.equal(mirostat, 1))
      builder.add(
          "mirostat",
          scores.length == 0 ? 0 : scores[0].length,
          seed,
          options.get("mirostat_tau"),
          options.get("mirostat_eta"),
          100);
    else if (PythonNumbers.equal(mirostat, 2))
      builder.add("mirostat_v2", seed, options.get("mirostat_tau"), options.get("mirostat_eta"));
    else {
      builder.add("top_k", options.get("top_k"));
      builder.add("typical", options.get("typical_p"), 1);
      builder.add("top_p", options.get("top_p"), 1);
      builder.add("min_p", options.get("min_p"), 1);
      builder.add("temp", options.get("temp"));
      builder.add("dist", seed);
    }
    return builder.build();
  }

  private void applyProcessors(PythonLogitsProcessorList processors, float[] values) {
    for (int i = 0; i < processors.size(); i++) {
      int[] ids = java.util.Arrays.copyOf(inputIds, sliceEnd(inputIds.length));
      float[] result;
      try {
        if (processors.get(i) == null)
          throw PythonValues.error(PythonErrorType.TYPE_ERROR, "'NoneType' object is not callable");
        result = processors.get(i).apply(ids, values);
      } finally {
        System.arraycopy(ids, 0, inputIds, 0, ids.length);
      }
      if (result == null) java.util.Arrays.fill(values, Float.NaN);
      else if (result.length == 1) java.util.Arrays.fill(values, result[0]);
      else {
        if (result.length != values.length)
          throw broadcast("(" + result.length + ",)", "(" + values.length + ",)");
        System.arraycopy(result, 0, values, 0, values.length);
      }
    }
  }

  public int sample(java.util.Map<String, Object> options, Integer index) {
    var parameters = PythonLlamaDefaults.bind("sample", options);
    parameters.remove("idx");
    PythonErrorBridge.requireEvaluatedTokens(nTokens);
    boolean temporary = sampler == null;
    if (temporary) sampler = initSampler(parameters);
    Object requestedIndex = index == null ? options.get("idx") : index;
    Object relativeIndex =
        requestedIndex == null ? -1 : PythonNumbers.subtract(requestedIndex, nTokens);
    if (execution == null) throw PythonValues.error(PythonErrorType.ASSERTION_ERROR, "");
    int token = sampler.sampleAt(relativeIndex);
    if (temporary) sampler = null;
    return token;
  }

  private java.util.function.Consumer<String> diagnostics = value -> {};

  public void setExecution(PythonLlamaExecution value) {
    execution = Objects.requireNonNull(value);
  }

  public void setSamplerFactory(Supplier<PythonLlamaSampler> value) {
    samplerFactory = Objects.requireNonNull(value);
  }

  public void setSampler(PythonLlamaSampler value) {
    sampler = value;
  }

  public PythonLlamaSampler sampler() {
    return sampler;
  }

  public void setDiagnostics(java.util.function.Consumer<String> value) {
    diagnostics = Objects.requireNonNull(value);
  }

  /** Lazily runs the pinned generate state machine; sampling policy is supplied by the factory. */
  public TokenGenerator generate(
      int[] tokens,
      boolean reset,
      PythonStoppingCriteriaList stopping,
      java.util.function.UnaryOperator<int[]> draft) {
    return new TokenGenerator(tokens, reset, stopping, draft);
  }

  public TokenGenerator generate(int[] tokens) {
    return generate(tokens, true, null, null);
  }

  public TokenGenerator generate(int[] tokens, java.util.Map<String, Object> arguments) {
    var supplied = new java.util.LinkedHashMap<>(arguments);
    supplied.put("tokens", tokens);
    var options = PythonLlamaDefaults.bind("generate", supplied);
    var sampling = new java.util.LinkedHashMap<>(options);
    sampling.remove("tokens");
    sampling.remove("reset");
    sampling.remove("stopping_criteria");
    var result =
        new TokenGenerator(
            tokens,
            PythonValues.truth(options.get("reset")),
            (PythonStoppingCriteriaList) options.get("stopping_criteria"),
            null);
    result.samplingOptions = sampling;
    return result;
  }

  /** Explicit send-style generator. A null return represents normal generator exhaustion. */
  public final class TokenGenerator implements AutoCloseable {
    private final int[] prompt;
    private final boolean resetRequested;
    private final PythonStoppingCriteriaList stopping;
    private final java.util.function.UnaryOperator<int[]> draft;
    private boolean started;
    private boolean ended;
    private boolean yielded;
    private int lastToken;
    private int sampleIndex;
    private java.util.ArrayList<Integer> pending;
    private java.util.Map<String, Object> samplingOptions;

    private TokenGenerator(
        int[] tokens,
        boolean reset,
        PythonStoppingCriteriaList stopping,
        java.util.function.UnaryOperator<int[]> draft) {
      prompt = tokens;
      resetRequested = reset;
      this.stopping = stopping;
      this.draft = draft;
    }

    public Integer next() {
      return send(null);
    }

    public Integer send(int[] additional) {
      if (ended) return null;
      if (!started && additional != null)
        throw PythonValues.error(
            PythonErrorType.TYPE_ERROR, "can't send non-None value to a just-started generator");
      try {
        return resume(additional);
      } catch (RuntimeException | Error failure) {
        ended = true;
        throw failure;
      }
    }

    private Integer resume(int[] additional) {
      if (!started) {
        started = true;
        sampler =
            samplingOptions != null
                ? initSampler(samplingOptions)
                : samplerFactory != null ? samplerFactory.get() : initSampler(java.util.Map.of());
        pending = new java.util.ArrayList<>();
        for (int token : prompt) pending.add(token);
        boolean reset = resetRequested;
        if (reset && nTokens > 0) {
          int prefix = 0;
          while (prefix < Math.min(sliceEnd(inputIds.length), pending.size())
              && inputIds[prefix] == pending.get(prefix)) prefix++;
          boolean consumed = prefix == pending.size();
          int reuse;
          if (nTokens == pending.size() && consumed && !requiresEval) {
            reset = false;
            pending.clear();
            reuse = 0;
            diagnostics.accept("Llama.generate: full prompt already cached, skipping reset");
          } else reuse = consumed ? prefix - 1 : prefix;
          if (reuse > 0) {
            if (requireExecution().removeSequence(reuse)) {
              reset = false;
              pending = new java.util.ArrayList<>(pending.subList(reuse, pending.size()));
              nTokens = reuse;
              requiresEval = true;
              diagnostics.accept(
                  "Llama.generate: "
                      + reuse
                      + " prefix-match hit, remaining "
                      + pending.size()
                      + " prompt tokens to eval");
            } else
              diagnostics.accept(
                  "Llama.generate: "
                      + reuse
                      + " prefix-match found but partial kv removal not supported, re-evaluating full prompt");
          }
        }
        if (reset) PythonLlama.this.reset();
        sampleIndex = nTokens + pending.size() - 1;
        eval(pending.stream().mapToInt(Integer::intValue).toArray());
      } else if (yielded) {
        pending.clear();
        pending.add(lastToken);
        if (additional != null) for (int token : additional) pending.add(token);
        if (sampleIndex < nTokens && lastToken != inputIds[sampleIndex]) {
          nTokens = sampleIndex;
          requireExecution().removeSequence(nTokens);
          requiresEval = true;
        }
      }
      if (sampleIndex >= nTokens) {
        java.util.function.UnaryOperator<int[]> draftFunction = draft != null ? draft : draftModel;
        if (draftFunction != null) {
          int start = sliceIndex(nTokens, inputIds.length),
              end = sliceIndex((long) nTokens + pending.size(), inputIds.length);
          if (end - start != pending.size() && pending.size() != 1)
            throw broadcast("(" + pending.size() + ",)", "(" + (end - start) + ",)");
          for (int i = start; i < end; i++)
            inputIds[i] = pending.get(pending.size() == 1 ? 0 : i - start);
          int[] draftIds = java.util.Arrays.copyOf(inputIds, end);
          int[] proposed;
          try {
            proposed = draftFunction.apply(draftIds);
          } finally {
            System.arraycopy(draftIds, 0, inputIds, 0, draftIds.length);
          }
          int limit = sliceIndex((long) contextLength - nTokens - pending.size(), proposed.length);
          for (int i = 0; i < limit; i++) pending.add(proposed[i]);
        }
        eval(pending.stream().mapToInt(Integer::intValue).toArray());
      }
      int token = sample(sampleIndex);
      sampleIndex++;
      if (stopping != null) {
        int row = sampleIndex - nTokens;
        int scoreRows = sliceEnd(scores.length);
        if (row < 0) row += scoreRows;
        if (row < 0 || row >= scoreRows)
          throw PythonValues.error(
              PythonErrorType.INDEX_ERROR,
              "index "
                  + (sampleIndex - nTokens)
                  + " is out of bounds for axis 0 with size "
                  + scoreRows);
        int[] stopIds = java.util.Arrays.copyOf(inputIds, sliceIndex(sampleIndex, inputIds.length));
        boolean stop;
        try {
          stop = stopping.test(stopIds, scores[row]);
        } finally {
          System.arraycopy(stopIds, 0, inputIds, 0, stopIds.length);
        }
        if (stop) {
          ended = true;
          return null;
        }
      }
      lastToken = token;
      yielded = true;
      return token;
    }

    @Override
    public void close() {
      ended = true;
    }
  }

  /** Port of eval: native decode precedes array writes; cursor advances only after all writes. */
  public void eval(int[] tokens) {
    PythonLlamaExecution backend = requireExecution();
    backend.removeSequence(nTokens);
    int batchSize = backend.batchSize();
    if (batchSize == 0)
      throw PythonValues.error(PythonErrorType.VALUE_ERROR, "range() arg 3 must not be zero");
    if (batchSize < 0) return;
    for (int offset = 0; offset < tokens.length; offset += batchSize) {
      int[] batch =
          java.util.Arrays.copyOfRange(tokens, offset, Math.min(tokens.length, offset + batchSize));
      backend.decode(batch, nTokens, logitsAll);
      int start = sliceIndex(nTokens, inputIds.length),
          end = sliceIndex((long) nTokens + batch.length, inputIds.length);
      if (end - start != batch.length && batch.length != 1)
        throw broadcast("(" + batch.length + ",)", "(" + Math.max(0, end - start) + ",)");
      if (batch.length == 1) java.util.Arrays.fill(inputIds, start, Math.max(start, end), batch[0]);
      else System.arraycopy(batch, 0, inputIds, start, batch.length);
      if (logitsAll)
        assignEvalScores(
            backend.logits(batch.length, scores.length == 0 ? 0 : scores[0].length),
            nTokens,
            nTokens + batch.length);
      nTokens += batch.length;
      requiresEval = false;
    }
  }

  public int sample() {
    return sample(null);
  }

  /** Port of sample orchestration; an exceptional temporary sample stays attached in Python. */
  public int sample(Integer index) {
    PythonErrorBridge.requireEvaluatedTokens(nTokens);
    boolean temporary = sampler == null;
    if (temporary) {
      sampler = samplerFactory != null ? samplerFactory.get() : initSampler(java.util.Map.of());
    }
    if (execution == null) throw PythonValues.error(PythonErrorType.ASSERTION_ERROR, "");
    int token = sampler.sample(index == null ? -1 : index - nTokens);
    if (temporary) sampler = null;
    return token;
  }

  public PythonLlamaState saveState() {
    diagnostics.accept("Llama.save_state: saving llama state");
    PythonLlamaExecution backend = requireExecution();
    long size = backend.stateSize();
    diagnostics.accept("Llama.save_state: got state size: " + size);
    byte[] data = new byte[Math.toIntExact(size)];
    diagnostics.accept("Llama.save_state: allocated state");
    long count = backend.copyState(data);
    diagnostics.accept("Llama.save_state: copied llama state: " + count);
    if (count > size)
      throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "Failed to copy llama state data");
    byte[] compact = java.util.Arrays.copyOf(data, Math.toIntExact(count));
    diagnostics.accept("Llama.save_state: saving " + count + " bytes of llama state");
    float[][] saved = java.util.Arrays.copyOf(scores, sliceEnd(scores.length));
    for (int i = 0; i < saved.length; i++) saved[i] = saved[i].clone();
    return new PythonLlamaState(
        inputIds.clone(), saved, nTokens, compact, BigInteger.valueOf(count), seed);
  }

  /** Port of load_state, including positive-only tail clearing and pre-error state mutations. */
  public void loadState(PythonLlamaState state) {
    assignScores(state.scores(), 0, state.nTokens());
    for (int row = sliceIndex(state.nTokens(), scores.length); row < scores.length; row++)
      for (int col = 0; col < scores[row].length; col++)
        if (scores[row][col] > 0) scores[row][col] = 0;
    inputIds = state.inputIds().clone();
    nTokens = state.nTokens();
    requiresEval = true;
    seed = state.seedObject();
    int size = state.llamaStateSize().intValueExact();
    if (size < 0)
      throw PythonValues.error(
          PythonErrorType.VALUE_ERROR, "Array length must be >= 0, not " + size);
    if (state.llamaState().length < size)
      throw PythonValues.error(
          PythonErrorType.VALUE_ERROR,
          "Buffer size too small ("
              + state.llamaState().length
              + " instead of at least "
              + size
              + " bytes)");
    byte[] data = java.util.Arrays.copyOf(state.llamaState(), size);
    if (requireExecution().restoreState(data) != size)
      throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "Failed to set llama state data");
  }

  private PythonLlamaExecution requireExecution() {
    if (execution == null) throw new IllegalStateException("Attach an execution backend first");
    return execution;
  }

  private void assignScores(float[][] source, int from, int to) {
    int start = sliceIndex(from, scores.length),
        end = Math.max(start, sliceIndex(to, scores.length));
    int rows = end - start, columns = scores.length == 0 ? 0 : scores[0].length;
    int sourceColumns = source.length == 0 ? columns : source[0].length;
    if ((source.length != rows && source.length != 1)
        || (sourceColumns != columns && sourceColumns != 1))
      throw broadcast(
          "(" + source.length + "," + sourceColumns + ")", "(" + rows + "," + columns + ")");
    for (int row = 0; row < rows; row++)
      for (int col = 0; col < columns; col++)
        scores[start + row][col] =
            source[source.length == 1 ? 0 : row][sourceColumns == 1 ? 0 : col];
  }

  private void assignEvalScores(float[][] source, int from, int to) {
    int start = sliceIndex(from, scores.length),
        end = Math.max(start, sliceIndex(to, scores.length));
    int columns = scores.length == 0 ? 0 : scores[0].length;
    int sourceCount = 0;
    for (float[] row : source) sourceCount += row.length;
    int count = (end - start) * columns;
    if (sourceCount != count && sourceCount != 1)
      throw broadcast("(" + sourceCount + ",)", "(" + count + ",)");
    int offset = 0;
    for (int row = start; row < end; row++)
      for (int col = 0; col < columns; col++) {
        scores[row][col] =
            source[sourceCount == 1 ? 0 : offset / columns][
                sourceCount == 1 ? 0 : offset % columns];
        offset++;
      }
  }

  private static PythonException broadcast(String source, String target) {
    return PythonValues.error(
        PythonErrorType.VALUE_ERROR,
        "could not broadcast input array from shape " + source + " into shape " + target);
  }

  private static int sliceIndex(long index, int length) {
    return (int) Math.max(0, Math.min(length, index < 0 ? length + index : index));
  }

  /** Attaches the model codec and selects its default tokenizer for high-level calls. */
  public void setTokenizerModel(PythonTokenizerModel value) {
    tokenizerModel = Objects.requireNonNull(value, "model");
    activeTokenizer = new PythonLlamaTokenizer(value);
  }

  /** Selects a custom tokenizer for high-level tokenize/detokenize calls. */
  public void setTokenizer(PythonTokenizer value) {
    activeTokenizer = Objects.requireNonNull(value, "tokenizer");
  }

  /** High-level Llama defaults: add_bos=true, special=false. */
  public int[] tokenize(byte[] text) {
    return tokenize(text, true, false);
  }

  public int[] tokenize(byte[] text, boolean addBos, boolean special) {
    return requireTokenizer().tokenize(text, addBos, special);
  }

  public byte[] detokenize(int[] tokens) {
    return detokenize(tokens, null, false);
  }

  public byte[] detokenize(int[] tokens, int[] previousTokens, boolean special) {
    return requireTokenizer().detokenize(tokens, previousTokens, special);
  }

  /** Returns a new model-backed wrapper, independently of the active custom tokenizer. */
  public PythonLlamaTokenizer tokenizer() {
    if (tokenizerModel == null) throw new IllegalStateException("Attach a tokenizer model first");
    return new PythonLlamaTokenizer(tokenizerModel);
  }

  private PythonTokenizer requireTokenizer() {
    if (activeTokenizer == null) throw new IllegalStateException("Attach a tokenizer first");
    return activeTokenizer;
  }

  /**
   * Attaches evaluated buffers without copying. For recurrent/hybrid models, reset queries
   * memoryAccess and runs its returned clear action when present. A clear action must clear native
   * memory with data=true. For other models memoryAccess is never queried.
   */
  public PythonLlama(
      int[] inputIds,
      float[][] scores,
      int nTokens,
      int contextLength,
      boolean logitsAll,
      boolean recurrent,
      boolean hybrid,
      Supplier<Runnable> memoryAccess) {
    this.inputIds = Objects.requireNonNull(inputIds, "inputIds");
    this.scores = Objects.requireNonNull(scores, "scores");
    this.nTokens = nTokens;
    this.contextLength = contextLength;
    this.logitsAll = logitsAll;
    this.recurrent = recurrent;
    this.hybrid = hybrid;
    this.memoryAccess = Objects.requireNonNull(memoryAccess, "memoryAccess");
  }

  /** Writable, zero-position view equivalent to input_ids[:n_tokens]. */
  public IntBuffer inputIds() {
    return IntBuffer.wrap(inputIds, 0, sliceEnd(inputIds.length)).slice();
  }

  /** Writable row views equivalent to scores[:n_tokens, :]. */
  public List<FloatBuffer> scores() {
    List<FloatBuffer> result = new ArrayList<>();
    for (int row = 0; row < sliceEnd(scores.length); row++) {
      result.add(FloatBuffer.wrap(scores[row]).slice());
    }
    return List.copyOf(result);
  }

  /** Snapshot of eval_tokens in deque iteration order, retaining its last n_ctx items. */
  public List<Integer> evalTokens() {
    checkMaxlen(contextLength);
    int end = sliceEnd(inputIds.length);
    List<Integer> result = new ArrayList<>();
    for (int i = Math.max(0, end - contextLength); i < end; i++) result.add(inputIds[i]);
    return result;
  }

  /** Deep snapshot of eval_logits, retaining n_ctx rows for logits_all and one row otherwise. */
  public List<List<Float>> evalLogits() {
    int maxlen = logitsAll ? contextLength : 1;
    checkMaxlen(maxlen);
    int end = sliceEnd(scores.length);
    List<List<Float>> result = new ArrayList<>();
    for (int row = Math.max(0, end - maxlen); row < end; row++) {
      List<Float> values = new ArrayList<>();
      for (float value : scores[row]) values.add(value);
      result.add(values);
    }
    return result;
  }

  /** Assigns the cache reference, including null, without clearing state. */
  public void setCache(PythonLlamaCache value) {
    cache = value;
  }

  /** Returns the currently attached cache reference. */
  public PythonLlamaCache cache() {
    return cache;
  }

  /** Assigns the Python integer seed without reseeding an existing sampler. */
  public void setSeed(BigInteger value) {
    seed = value;
  }

  /** Returns the assigned seed. */
  public BigInteger seed() {
    return PythonNumbers.integer(seed);
  }

  public void setSeed(Object value) {
    seed = value;
  }

  public Object seedObject() {
    return seed;
  }

  /** Resets the logical cursor before querying and clearing recurrent/hybrid memory. */
  public void reset() {
    nTokens = 0;
    requiresEval = true;
    if (recurrent || hybrid) {
      Runnable clear = memoryAccess.get();
      if (clear != null) clear.run();
    }
  }

  /** Returns the logical cursor used by the state views. */
  public int nTokens() {
    return nTokens;
  }

  /** Records the cursor and clears requires_eval after caller-managed evaluation succeeds. */
  public void setEvaluatedTokens(int value) {
    nTokens = value;
    requiresEval = false;
  }

  /** Returns whether reset has requested evaluation. */
  public boolean requiresEval() {
    return requiresEval;
  }

  /** Stable log-softmax of one single-precision vector, using the original nonfinite-max rule. */
  public static float[] logitsToLogprobs(float[] logits) {
    Objects.requireNonNull(logits, "logits");
    if (logits.length == 0) throw emptyReduction();
    float maximum = logits[0];
    for (float value : logits) maximum = Math.max(maximum, value);
    if (!Float.isFinite(maximum)) maximum = 0;
    float[] shifted = new float[logits.length];
    float[] exponentials = new float[logits.length];
    for (int i = 0; i < logits.length; i++) {
      shifted[i] = logits[i] - maximum;
      exponentials[i] = (float) Math.exp(shifted[i]);
    }
    float normalizer = (float) Math.log(sum(exponentials, 0, exponentials.length));
    for (int i = 0; i < shifted.length; i++) shifted[i] -= normalizer;
    return shifted;
  }

  /**
   * Log-softmax of a rectangular single-precision matrix along axis 0/-2 or 1/-1. Results preserve
   * shape and leave input buffers unchanged. Empty outer dimensions and other ranks are outside
   * this Java array interface.
   */
  public static float[][] logitsToLogprobs(float[][] logits, int axis) {
    Objects.requireNonNull(logits, "logits");
    if (axis < -2 || axis > 1) throw new IllegalArgumentException("axis must be -2, -1, 0 or 1");
    if (logits.length == 0) throw emptyReduction();
    int columns = Objects.requireNonNull(logits[0], "row").length;
    for (float[] row : logits) {
      if (Objects.requireNonNull(row, "row").length != columns)
        throw new IllegalArgumentException("logits must be rectangular");
    }
    float[][] result = new float[logits.length][columns];
    if (axis == 1 || axis == -1) {
      for (int row = 0; row < logits.length; row++) result[row] = logitsToLogprobs(logits[row]);
    } else {
      for (int column = 0; column < columns; column++) {
        float[] values = new float[logits.length];
        for (int row = 0; row < logits.length; row++) values[row] = logits[row][column];
        float[] probabilities = logitsToLogprobs(values);
        for (int row = 0; row < logits.length; row++) result[row][column] = probabilities[row];
      }
    }
    return result;
  }

  private int sliceEnd(int length) {
    return (int) Math.max(0, Math.min(length, nTokens < 0 ? (long) length + nTokens : nTokens));
  }

  private static void checkMaxlen(int maxlen) {
    if (maxlen < 0)
      throw PythonValues.error(PythonErrorType.VALUE_ERROR, "maxlen must be non-negative");
  }

  private static PythonException emptyReduction() {
    return PythonValues.error(
        PythonErrorType.VALUE_ERROR,
        "zero-size array to reduction operation maximum which has no identity");
  }

  // NumPy's contiguous float reduction uses eight lanes and pairwise blocks of at most 128 values.
  private static float sum(float[] values, int offset, int length) {
    if (length < 8) {
      float result = -0.0f;
      for (int i = 0; i < length; i++) result += values[offset + i];
      return result;
    }
    if (length <= 128) {
      float[] lanes = new float[8];
      System.arraycopy(values, offset, lanes, 0, 8);
      int i = 8;
      for (; i + 7 < length; i += 8)
        for (int lane = 0; lane < 8; lane++) lanes[lane] += values[offset + i + lane];
      float result =
          ((lanes[0] + lanes[1]) + (lanes[2] + lanes[3]))
              + ((lanes[4] + lanes[5]) + (lanes[6] + lanes[7]));
      for (; i < length; i++) result += values[offset + i];
      return result;
    }
    int half = (length / 2) & ~7;
    return sum(values, offset, half) + sum(values, offset + half, length - half);
  }
}
