package io.github.yaulun.javacpp;

import static io.github.yaulun.javacpp.compat.python.PythonErrorBridge.error;

import com.sun.jna.*;
import io.github.yaulun.javacpp.compat.python.*;
import io.github.yaulun.javacpp.internal.*;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonNumbers;

/** Native construction using Python defaults and direct C parameter conversion. */
public final class PythonNativeLlama {
  private PythonNativeLlama() {}

  public static PythonLlama load(Map<String, Object> arguments) {
    return loadInto(null, arguments);
  }

  @SuppressWarnings("unchecked")
  private static PythonLlama loadInto(PythonLlama existing, Map<String, Object> supplied) {
    var options = PythonLlama.constructorArguments(supplied);
    LlamaLibrary library = LlamaRuntime.get().library();
    Object numa =
        options.get("numa") instanceof Boolean flag
            ? (flag ? BigInteger.ONE : BigInteger.ZERO)
            : options.get("numa");
    if (Py.compare(numa, Py.Op.NE, BigInteger.ZERO))
      library.llama_numa_init(PythonNumbers.cInt(numa, 1));
    var params = library.llama_model_default_params();
    int layers = Py.cInt(options.get("n_gpu_layers"));
    params.n_gpu_layers = layers == -1 ? Integer.MAX_VALUE : layers;
    params.split_mode = Py.cInt(options.get("split_mode"));
    params.main_gpu = Py.cInt(options.get("main_gpu"));
    params.vocab_only = (byte) (Py.truth(options.get("vocab_only")) ? 1 : 0);
    boolean mmap = Py.truth(options.get("use_mmap")) && options.get("lora_path") == null,
        mlock = Py.truth(options.get("use_mlock"));
    params.load_mode = mmap ? mlock ? 3 : 1 : mlock ? 2 : 0;
    List<Memory> retained = new ArrayList<>();
    LlamaModel model = null;
    LlamaContext context = null;
    Pointer adapter = null;
    boolean complete = false;
    try {
      if (options.get("tensor_split") != null) {
        List<?> split = (List<?>) options.get("tensor_split");
        int max = (int) library.llama_max_devices();
        if (split.size() > max)
          throw error(
              PythonErrorType.VALUE_ERROR,
              "Attempt to split tensors that exceed maximum supported devices. Current LLAMA_MAX_DEVICES="
                  + max);
        Memory memory = new Memory((long) max * 4);
        memory.clear();
        retained.add(memory);
        for (int n = 0; n < split.size(); n++)
          memory.setFloat((long) n * 4, ((Number) split.get(n)).floatValue());
        params.tensor_split = memory;
      }
      if (options.get("kv_overrides") != null) {
        Map<?, ?> overrides = (Map<?, ?>) options.get("kv_overrides");
        Memory memory = new Memory((long) (overrides.size() + 1) * 264);
        memory.clear();
        retained.add(memory);
        int n = 0;
        for (var entry : overrides.entrySet()) {
          long offset = (long) n++ * 264;
          byte[] key = PythonLlamaServices.strictUtf8(entry.getKey());
          if (key.length > 128)
            throw error(
                PythonErrorType.VALUE_ERROR,
                "bytes too long (" + key.length + ", maximum length 128)");
          memory.write(offset + 4, key, 0, key.length);
          Object value = entry.getValue();
          if (value instanceof Boolean flag) {
            memory.setInt(offset, 2);
            memory.setByte(offset + 136, (byte) (flag ? 1 : 0));
          } else if (value instanceof Byte
              || value instanceof Short
              || value instanceof Integer
              || value instanceof Long
              || value instanceof BigInteger) {
            memory.setInt(offset, 0);
            try {
              memory.setLong(offset + 136, Py.index(value).longValueExact());
            } catch (ArithmeticException overflow) {
              throw error(PythonErrorType.OVERFLOW_ERROR, "int too long to convert");
            }
          } else if (value instanceof Number number) {
            memory.setInt(offset, 1);
            memory.setDouble(offset + 136, number.doubleValue());
          } else if (value instanceof String text) {
            byte[] encoded = PythonLlamaServices.strictUtf8(text);
            if (encoded.length > 128)
              throw error(
                  PythonErrorType.VALUE_ERROR,
                  "Value for " + entry.getKey() + " is too long: " + text);
            memory.setInt(offset, 3);
            memory.write(offset + 136, encoded, 0, encoded.length);
          } else
            throw error(
                PythonErrorType.VALUE_ERROR,
                "Unknown value type for "
                    + entry.getKey()
                    + ": "
                    + PythonErrorBridge.pythonString(value));
        }
        params.kv_overrides = memory;
      }
      String modelPath = options.get("model_path").toString();
      Path path = Path.of(modelPath);
      if (!Files.exists(path))
        throw error(PythonErrorType.VALUE_ERROR, "Model path does not exist: " + modelPath);
      Pointer handle = library.llama_model_load_from_file(modelPath, params);
      if (handle == null)
        throw error(PythonErrorType.VALUE_ERROR, "Failed to load model from file: " + modelPath);
      try {
        model =
            new LlamaModel(
                library,
                handle,
                path,
                ModelParams.builder().vocabOnly(Py.truth(options.get("vocab_only"))).build(),
                null);
      } catch (RuntimeException | Error failure) {
        library.llama_model_free(handle);
        throw failure;
      }
      int requested = Py.cInt(options.get("n_ctx")),
          ctx = requested == 0 ? model.info().contextTrain() : requested;
      int batch = Math.min(ctx, Py.cInt(options.get("n_batch"))),
          micro = Math.min(batch, Py.cInt(options.get("n_ubatch")));
      var contextOptions = new LinkedHashMap<String, Object>();
      for (String name :
          List.of(
              "pooling_type",
              "attention_type",
              "rope_freq_base",
              "rope_freq_scale",
              "yarn_ext_factor",
              "yarn_attn_factor",
              "yarn_beta_fast",
              "yarn_beta_slow",
              "yarn_orig_ctx",
              "offload_kqv",
              "no_perf")) contextOptions.put(name, options.get(name));
      contextOptions.put("n_ctx", ctx);
      contextOptions.put("n_batch", batch);
      contextOptions.put("n_ubatch", micro);
      int cpu = Runtime.getRuntime().availableProcessors();
      contextOptions.put(
          "n_threads",
          Py.orElse(options.get("n_threads"), () -> Math.max(cpu / 2, 1)));
      contextOptions.put(
          "n_threads_batch", Py.orElse(options.get("n_threads_batch"), () -> cpu));
      contextOptions.put(
          "rope_scaling_type",
          options.get("rope_scaling_type") == null ? -1 : options.get("rope_scaling_type"));
      contextOptions.put("embeddings", options.get("embedding"));
      contextOptions.put("flash_attn_type", Py.truth(options.get("flash_attn")) ? 1 : 0);
      for (String name : List.of("op_offload", "swa_full", "type_k", "type_v"))
        if (options.get(name) != null) contextOptions.put(name, options.get(name));
      if (Py.truth(options.get("embedding"))) {
        contextOptions.put(
            "n_seq_max", Math.min(batch, (int) library.llama_max_parallel_sequences()));
        contextOptions.put("kv_unified", true);
      }
      try {
        context = model.newPythonContext(contextOptions);
      } catch (io.github.yaulun.javacpp.exception.InferenceException failure) {
        if (failure
            .nativeFailure()
            .filter(v -> v.function().equals("llama_init_from_model") && v.returnValue() == 0)
            .isPresent())
          throw error(PythonErrorType.VALUE_ERROR, "Failed to create llama_context");
        throw failure;
      }
      if (Py.truth(options.get("lora_path"))) {
        String lora = options.get("lora_path").toString();
        adapter = library.llama_adapter_lora_init(model.handle(), lora);
        if (adapter == null)
          throw error(
              PythonErrorType.RUNTIME_ERROR,
              "Failed to initialize LoRA adapter from lora path: " + lora);
        if (context.setLora(adapter, PythonNumbers.cFloat(options.get("lora_scale"), 3)) != 0)
          throw error(
              PythonErrorType.RUNTIME_ERROR, "Failed to set LoRA adapter from lora path: " + lora);
      }
      boolean all =
          options.get("draft_model") != null || Py.truth(options.get("logits_all"));
      int[] ids = new int[ctx];
      float[][] scores =
          new float
              [Py.equal(options.get("logits_all"), Boolean.TRUE)
                  ? ctx
                  : Py.cInt(options.get("n_batch"))]
              [model.vocab().size()];
      LlamaModel owner = model;
      LlamaContext active = context;
      boolean recurrent = library.llama_model_is_recurrent(model.handle()) != 0,
          hybrid = model.info().hybrid();
      PythonLlama state =
          existing == null
              ? new PythonLlama(
                  ids,
                  scores,
                  0,
                  context.contextLength(),
                  all,
                  recurrent,
                  hybrid,
                  () -> active::clear)
              : existing;
      state.replaceBuffers(
          ids, scores, context.contextLength(), all, recurrent, hybrid, () -> active::clear);
      PythonLlamaResource<Pointer> lora = new PythonLlamaResource<>(adapter);
      state.setCloseAction(
          () -> {
            try {
              lora.free(library::llama_adapter_lora_free);
            } finally {
              try {
                active.close();
              } finally {
                owner.close();
              }
            }
          });
      state.attachNative(model, context);
      state.setExecution(PythonLlamaExecution.fromContext(context, batch));
      state.setLastNTokensSize(Py.cInt(options.get("last_n_tokens_size")));
      state.setSeed(
          Py.orElse(options.get("seed"), () -> BigInteger.valueOf(4294967295L)));
      if (options.get("tokenizer") != null)
        state.setTokenizer((PythonTokenizer) options.get("tokenizer"));
      state.setDraftModel((java.util.function.UnaryOperator<int[]>) options.get("draft_model"));
      boolean verbose = Py.truth(options.get("verbose"));
      state.services().setVerbose(verbose);
      state.services().setModelPath(modelPath);
      state.services().setSpmInfill(Py.truth(options.get("spm_infill")));
      if (verbose) state.setDiagnostics(System.err::println);
      state
          .services()
          .setChatFormat(
              options.get("chat_format") == null
                  ? "llama-2"
                  : options.get("chat_format").toString());
      state
          .services()
          .setChatHandler((Function<Map<String, Object>, Object>) options.get("chat_handler"));
      state
          .services()
          .setChatRegistry(
              format ->
                  forwarded -> {
                    PythonChatFormatterResponse formatted =
                        PythonChatFormats.format(format, (List<?>) forwarded.get("messages"));
                    var call = new LinkedHashMap<>(forwarded);
                    for (String key :
                        List.of(
                            "llama",
                            "messages",
                            "functions",
                            "function_call",
                            "tools",
                            "tool_choice",
                            "response_format",
                            "top_logprobs")) call.remove(key);
                    Object logprobs = forwarded.get("logprobs");
                    call.put(
                        "logprobs", Py.truth(logprobs) ? forwarded.get("top_logprobs") : null);
                    call.put(
                        "prompt",
                        Arrays.stream(
                                state.tokenize(
                                    PythonLlamaServices.strictUtf8(formatted.prompt()), true, true))
                            .boxed()
                            .toList());
                    if (formatted.stop() != null) {
                      List<Object> stops = new ArrayList<>();
                      Object requestedStop = call.get("stop");
                      if (requestedStop instanceof List<?> list) stops.addAll(list);
                      else if (requestedStop != null) stops.add(requestedStop);
                      if (formatted.stop() instanceof List<?> list) stops.addAll(list);
                      else stops.add(formatted.stop());
                      call.put("stop", stops);
                    }
                    return PythonChatCompletions.convertCompletionToChat(
                        state.createCompletion(call), Py.truth(forwarded.get("stream")));
                  });
      Map<String, String> metadata = model.metadata();
      Map<String, String> templates = new LinkedHashMap<>();
      for (var entry : metadata.entrySet())
        if (entry.getKey().startsWith("tokenizer.chat_template."))
          templates.put(entry.getKey().substring(10), entry.getValue());
      if (metadata.containsKey("tokenizer.chat_template"))
        templates.put("chat_template.default", metadata.get("tokenizer.chat_template"));
      for (var entry : templates.entrySet())
        state
            .services()
            .modelHandlers()
            .put(
                entry.getKey(),
                forwarded -> {
                  String formatted =
                      render(library, entry.getValue(), (List<?>) forwarded.get("messages"));
                  var call = new LinkedHashMap<>(forwarded);
                  for (String key :
                      List.of(
                          "llama",
                          "messages",
                          "functions",
                          "function_call",
                          "tools",
                          "tool_choice",
                          "response_format",
                          "top_logprobs")) call.remove(key);
                  call.put(
                      "prompt",
                      Arrays.stream(
                              state.tokenize(
                                  PythonLlamaServices.strictUtf8(formatted), false, true))
                          .boxed()
                          .toList());
                  call.put(
                      "logprobs",
                      Py.truth(forwarded.get("logprobs")) ? forwarded.get("top_logprobs") : null);
                  List<Object> stops = new ArrayList<>();
                  Object stop = forwarded.get("stop");
                  if (stop instanceof List<?> list) stops.addAll(list);
                  else if (stop != null) stops.add(stop);
                  int eos = owner.vocab().eos();
                  stops.add(eos == -1 ? "" : owner.vocab().tokenText(eos));
                  call.put("stop", stops);
                  PythonStoppingCriteriaList stopping = new PythonStoppingCriteriaList();
                  stopping.add((tokens, logits) -> tokens[tokens.length - 1] == eos);
                  call.put("stopping_criteria", stopping);
                  return PythonChatCompletions.convertCompletionToChat(
                      state.createCompletion(call), Py.truth(forwarded.get("stream")));
                });
      String chatFormat = (String) options.get("chat_format");
      if (chatFormat == null
          && options.get("chat_handler") == null
          && templates.containsKey("chat_template.default")) {
        chatFormat = PythonChatFormats.guessChatFormatFromGgufMetadata(metadata);
        if (chatFormat == null) chatFormat = "chat_template.default";
      }
      if (chatFormat == null && options.get("chat_handler") == null) chatFormat = "llama-2";
      state.services().setChatFormat(chatFormat);
      var values = new LinkedHashMap<>(options);
      values.put("n_batch", batch);
      values.put("numa", numa);
      values.put("chat_format", chatFormat);

      Map<String, Object> nativeContextValues = snapshot(library.llama_context_default_params());
      nativeContextValues.putAll(contextOptions);
      for (String field : List.of("embeddings", "offload_kqv", "no_perf", "op_offload", "swa_full"))
        nativeContextValues.put(field, Py.truth(nativeContextValues.get(field)));
      Map<String, Object> nativeModelValues = snapshot(params);
      nativeModelValues.put("vocab_only", params.vocab_only != 0);
      state.setConfiguration(
          new PythonLlamaConfiguration(
              state,
              values,
              nativeModelValues,
              nativeContextValues,
              rebuild -> loadInto(state, rebuild)));
      complete = true;
      return state;
    } finally {
      for (Memory memory : retained) memory.close();
      if (!complete) {
        try {
          if (adapter != null) library.llama_adapter_lora_free(adapter);
        } finally {
          try {
            if (context != null) context.close();
          } finally {
            if (model != null) model.close();
          }
        }
      }
    }
  }

  private static float cFloat(Object value) {
    if (!(value instanceof Number) && !(value instanceof Boolean))
      throw error(
          PythonErrorType.ARGUMENT_ERROR, "argument 1: TypeError: wrong type");
    return ((Number) value).floatValue();
  }

  static void assign(Structure structure, Map<String, Object> values) {
    for (var entry : values.entrySet())
      try {
        Field field = structure.getClass().getField(entry.getKey());
        Object value = entry.getValue();
        if (field.getType() == int.class) field.setInt(structure, Py.cInt(value));
        else if (field.getType() == byte.class) field.setByte(structure, (byte) Py.cInt(value));
        else if (field.getType() == float.class) field.setFloat(structure, cFloat(value));
        else if (field.getType() == long.class)
          try {
            field.setLong(structure, Py.index(value).longValueExact());
          } catch (ArithmeticException overflow) {
            throw error(PythonErrorType.OVERFLOW_ERROR, "int too long to convert");
          }
      } catch (ReflectiveOperationException failure) {
        throw new IllegalArgumentException(entry.getKey(), failure);
      }
  }

  private static String render(LlamaLibrary library, String template, List<?> messages) {
    LlamaChatMessage[] rows =
        messages.isEmpty()
            ? null
            : (LlamaChatMessage[]) new LlamaChatMessage().toArray(messages.size());
    List<Memory> strings = new ArrayList<>();
    try {
      for (int i = 0; i < messages.size(); i++) {
        Map<?, ?> message = (Map<?, ?>) messages.get(i);
        String role = (String) message.get("role");
        Object content = message.get("content");
        byte[] r = PythonLlamaServices.strictUtf8(role),
            c = PythonLlamaServices.strictUtf8(content);
        Memory rm = new Memory(r.length + 1L), cm = new Memory(c.length + 1L);
        rm.clear();
        cm.clear();
        rm.write(0, r, 0, r.length);
        cm.write(0, c, 0, c.length);
        strings.add(rm);
        strings.add(cm);
        rows[i].role = rm;
        rows[i].content = cm;
        rows[i].write();
      }
      byte[] bytes = new byte[512];
      int count =
          library.llama_chat_apply_template(
              template, rows, messages.size(), (byte) 1, bytes, bytes.length);
      if (count > bytes.length) {
        bytes = new byte[count];
        count =
            library.llama_chat_apply_template(
                template, rows, messages.size(), (byte) 1, bytes, bytes.length);
      }
      if (count < 0)
        throw error(PythonErrorType.RUNTIME_ERROR, "llama_chat_apply_template returned " + count);
      return new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8);
    } finally {
      for (Memory memory : strings) memory.close();
    }
  }

  private static Map<String, Object> snapshot(Structure structure) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Field field : structure.getClass().getFields())
      try {
        if (field.getType().isPrimitive()) result.put(field.getName(), field.get(structure));
      } catch (IllegalAccessException failure) {
        throw new AssertionError(failure);
      }
    return result;
  }
}
