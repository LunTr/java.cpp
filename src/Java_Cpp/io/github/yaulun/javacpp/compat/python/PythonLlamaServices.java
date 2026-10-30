package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.*;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.util.*;
import java.util.function.*;

/** Completion/chat delegation and embedding orchestration from the pinned Llama class. */
public final class PythonLlamaServices {
  public interface OpenAiTypes {
    Object completion(Map<String, Object> value);

    Object chunk(Map<String, Object> value);
  }

  private final PythonLlama llama;
  private Function<Map<String, Object>, Iterator<Map<String, Object>>> completionFactory;
  private Function<Map<String, Object>, Object> chatHandler;
  private final Map<String, Function<Map<String, Object>, Object>> modelHandlers =
      new LinkedHashMap<>();
  private Function<String, Function<Map<String, Object>, Object>> registry;
  private String chatFormat = "llama-2";
  private String modelPath;
  private OpenAiTypes openAiTypes;
  private PythonEmbeddingBackend embedding;
  private boolean verbose;
  private PythonCompletionModel completionModel;
  private Supplier<String> completionId = () -> "cmpl-" + UUID.randomUUID();
  private java.util.function.LongSupplier completionTime = () -> System.currentTimeMillis() / 1000;
  private PythonWarnings warnings =
      PythonWarnings.defaults(warning -> System.err.print(warning.format()));
  private boolean infill;

  public void setSpmInfill(boolean value) {
    infill = value;
  }

  public void setCompletionModel(PythonCompletionModel value) {
    completionModel = value;
    completionFactory =
        options ->
            new PythonCompletion(
                llama,
                completionModel,
                options,
                verbose,
                completionId,
                completionTime,
                warnings,
                infill);
  }

  public void setCompletionIdentity(Supplier<String> id, java.util.function.LongSupplier time) {
    completionId = id;
    completionTime = time;
  }

  public void setWarnings(PythonWarnings value) {
    warnings = value;
  }

  public PythonLlamaServices(PythonLlama llama) {
    this.llama = llama;
  }

  public void setCompletionFactory(
      Function<Map<String, Object>, Iterator<Map<String, Object>>> value) {
    completionFactory = value;
  }

  public void setChatHandler(Function<Map<String, Object>, Object> value) {
    chatHandler = value;
  }

  public void setChatRegistry(Function<String, Function<Map<String, Object>, Object>> value) {
    registry = value;
  }

  public Map<String, Function<Map<String, Object>, Object>> modelHandlers() {
    return modelHandlers;
  }

  public void setChatFormat(String value) {
    chatFormat = value;
  }

  public void setModelPath(String value) {
    modelPath = value;
  }

  public void setOpenAiTypes(OpenAiTypes value) {
    openAiTypes = value;
  }

  public void setEmbeddingBackend(PythonEmbeddingBackend value) {
    embedding = value;
  }

  public void setVerbose(boolean value) {
    verbose = value;
  }

  public Object createCompletion(Map<String, Object> arguments) {
    var options = PythonLlamaDefaults.bind("create_completion", arguments);
    if (options.get("max_tokens") == null) options.put("max_tokens", -1);
    var result = completionFactory.apply(PythonLlamaDefaults.forward("create_completion", options));
    if (truth(options.get("stream"))) return result;
    try {
      return result.next();
    } catch (NoSuchElementException failure) {
      throw error(PythonErrorType.STOP_ITERATION, "");
    }
  }

  public Object call(Map<String, Object> arguments) {
    return createCompletion(
        PythonLlamaDefaults.forward("__call__", PythonLlamaDefaults.bind("__call__", arguments)));
  }

  public Object createChatCompletion(Map<String, Object> arguments) {
    var options = PythonLlamaDefaults.bind("create_chat_completion", arguments);
    Function<Map<String, Object>, Object> handler = chatHandler;
    if (handler == null) handler = modelHandlers.get(chatFormat);
    if (handler == null) handler = registry.apply(chatFormat);
    options.put("llama", llama);
    return handler.apply(PythonLlamaDefaults.forward("create_chat_completion", options));
  }

  private static PythonException openAiMissing() {
    return error(
        PythonErrorType.IMPORT_ERROR,
        "To use create_chat_completion_openai_v1, you must install the openai package.You can install it with `pip install openai`.");
  }

  @SuppressWarnings("unchecked")
  public Object createChatCompletionOpenAiV1(Map<String, Object> arguments) {
    try {
      if (openAiTypes == null) throw openAiMissing();
      Object stream = arguments.getOrDefault("stream", false);
      if (!(stream instanceof Boolean)) throw error(PythonErrorType.ASSERTION_ERROR, "");
      Object result = createChatCompletion(arguments);
      if ((boolean) stream) {
        Iterator<Map<String, Object>> chunks = (Iterator<Map<String, Object>>) result;
        OpenAiTypes converter = openAiTypes;
        return new Iterator<Object>() {
          public boolean hasNext() {
            return chunks.hasNext();
          }

          public Object next() {
            return converter.chunk(chunks.next());
          }
        };
      }
      return openAiTypes.completion((Map<String, Object>) result);
    } catch (PythonException failure) {
      if (failure.type() == PythonErrorType.IMPORT_ERROR) throw openAiMissing();
      throw failure;
    }
  }

  public Object embed(Map<String, Object> arguments) {
    var options = PythonLlamaDefaults.bind("embed", arguments);
    Object input = options.get("input");
    int dimensions = embedding.dimensions(),
        batch = embedding.batchSize(),
        sequences = embedding.sequenceLimit();
    int pooling = embedding.poolingType();
    if (!embedding.enabled())
      throw error(
          PythonErrorType.RUNTIME_ERROR,
          "Llama model must be created with embedding=True to call this method");
    if (verbose) embedding.resetTimings();
    embedding.resetBatch();
    List<?> inputs = input instanceof String ? List.of(input) : list(input);
    List<Object> output = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    int total = 0, tokensInBatch = 0, sequence = 0;
    for (Object item : inputs) {
      byte[] bytes = strictUtf8(item);
      int[] tokens = llama.tokenize(bytes);
      if (truth(options.get("truncate")))
        tokens = Arrays.copyOf(tokens, sliceEnd(batch, tokens.length));
      total += tokens.length;
      if (tokens.length > batch)
        throw error(
            PythonErrorType.VALUE_ERROR,
            "Requested tokens (" + tokens.length + ") exceed batch size of " + batch);
      if (tokensInBatch + tokens.length > batch || sequence >= sequences) {
        decodeEmbedding(sizes, output, dimensions, pooling, truth(options.get("normalize")));
        sizes.clear();
        tokensInBatch = 0;
        sequence = 0;
      }
      embedding.addSequence(tokens, sequence, true);
      sizes.add(tokens.length);
      tokensInBatch += tokens.length;
      sequence++;
    }
    decodeEmbedding(sizes, output, dimensions, pooling, truth(options.get("normalize")));
    if (verbose) embedding.printTimings();
    Object result = input instanceof String ? output.getFirst() : output;
    embedding.clearMemory();
    llama.reset();
    return truth(options.get("return_count")) ? List.of(result, total) : result;
  }

  private void decodeEmbedding(
      List<Integer> sizes, List<Object> output, int dimensions, int pooling, boolean normalize) {
    embedding.clearMemory();
    embedding.decode();
    embedding.resetBatch();
    int position = 0;
    for (int i = 0; i < sizes.size(); i++) {
      if (pooling == 0) {
        double[] values = embedding.embeddings();
        List<Object> rows = new ArrayList<>();
        for (int j = 0; j < sizes.get(i); j++)
          rows.add(
              embeddingRow(
                  Arrays.copyOfRange(
                      values, position + j * dimensions, position + (j + 1) * dimensions),
                  normalize));
        output.add(rows);
        position += sizes.get(i);
      } else output.add(embeddingRow(embedding.sequenceEmbedding(i), normalize));
    }
  }

  private static List<Double> embeddingRow(double[] values, boolean normalize) {
    double norm = 0;
    if (normalize) {
      for (double value : values) norm += value * value;
      norm = Math.sqrt(norm);
    }
    List<Double> result = new ArrayList<>();
    for (double value : values) result.add(normalize && norm != 0 ? value / norm : value);
    return result;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createEmbedding(Map<String, Object> arguments) {
    var options = PythonLlamaDefaults.bind("create_embedding", arguments);
    Object input = options.get("input");
    List<?> inputs = input instanceof List<?> ? (List<?>) input : Collections.singletonList(input);
    List<?> embedded = (List<?>) embed(dict("input", inputs, "return_count", true));
    List<?> vectors = (List<?>) embedded.getFirst();
    List<Object> data = new ArrayList<>();
    for (int i = 0; i < vectors.size(); i++)
      data.add(dict("object", "embedding", "embedding", vectors.get(i), "index", i));
    return dict(
        "object",
        "list",
        "data",
        data,
        "model",
        options.get("model") == null ? modelPath : options.get("model"),
        "usage",
        dict("prompt_tokens", embedded.get(1), "total_tokens", embedded.get(1)));
  }

  private static int sliceEnd(int index, int length) {
    return Math.max(0, Math.min(length, index < 0 ? length + index : index));
  }

  public static byte[] strictUtf8(Object value) {
    if (!(value instanceof String text))
      throw error(
          PythonErrorType.ATTRIBUTE_ERROR,
          "'" + typeName(value) + "' object has no attribute 'encode'");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        i++;
        continue;
      }
      if (Character.isSurrogate(c)) {
        int end = i + 1;
        while (end < text.length() && Character.isSurrogate(text.charAt(end))) end++;
        int position = text.codePointCount(0, i);
        String detail =
            end == i + 1
                ? "character '\\u" + String.format("%04x", (int) c) + "' in position " + position
                : "characters in position " + position + "-" + (position + end - i - 1);
        throw error(
            PythonErrorType.UNICODE_ENCODE_ERROR,
            "'utf-8' codec can't encode " + detail + ": surrogates not allowed");
      }
    }
    return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
