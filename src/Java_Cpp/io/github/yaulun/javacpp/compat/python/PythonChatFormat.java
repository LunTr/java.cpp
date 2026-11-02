package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Ports the remaining small llama_chat_format helpers: the two Protocol __call__ ellipsis bodies,
 * Jinja2ChatFormatter.strftime_now/tojson, and chat_formatter_to_chat_completion_handler whose
 * nested handler body is replayed over formatter/llama/grammar/conversion ports.
 */
public final class PythonChatFormat {
  private PythonChatFormat() {}

  /** Ports LlamaChatCompletionHandler.__call__: the Protocol body is `...` and returns None. */
  public static Object handlerCall(Object self, Map<String, ?> kwargs) {
    return null;
  }

  /** Ports ChatFormatter.__call__: the Protocol body is `...` and returns None. */
  public static Object formatterCall(Object self, Map<String, ?> kwargs) {
    return null;
  }

  /** Ports Jinja2ChatFormatter.strftime_now: datetime.now().strftime(f) through a clock port. */
  public static String strftimeNow(String f, Function<String, String> now) {
    return now.apply(f);
  }

  /** Ports Jinja2ChatFormatter.tojson: json.dumps with the pinned kwargs. */
  public static String tojson(
      Object x, boolean ensureAscii, Integer indent, List<String> separators, boolean sortKeys) {
    return PythonJson.dumps(x, ensureAscii, indent, separators, sortKeys);
  }

  /** Formatter call result: ChatFormatterResponse fields. */
  public interface FormatterResult {
    String prompt();

    Object stop(); // null | String | List<String>

    boolean addedSpecial();

    Object stoppingCriteria();
  }

  /** Ports reached by the nested chat_completion_handler body. */
  public interface Ports {
    /**
     * chat_formatter(messages=..., functions=..., function_call=..., tools=..., tool_choice=...,
     * **kwargs).
     */
    FormatterResult format(Map<String, Object> kwargs);

    /** llama.tokenize(bytes, add_bos=..., special=True). */
    int[] tokenize(byte[] utf8, boolean addBos);

    /** llama.verbose attribute. */
    boolean verbose();

    /** _grammar_for_response_format(response_format, verbose=...). */
    Object grammarForResponseFormat(Map<String, Object> responseFormat);

    /** llama_grammar.LlamaGrammar.from_json_schema(json.dumps(schema), verbose=...). */
    Object grammarFromJsonSchema(String schemaJson) throws PythonException;

    /** llama_grammar.LlamaGrammar.from_string(JSON_GBNF, verbose=...). */
    Object grammarFromString();

    /** llama.create_completion(**kwargs). */
    Object createCompletion(Map<String, Object> kwargs);

    /** _convert_completion_to_chat(completion_or_chunks, stream=...). */
    Object convertCompletion(Object chunks, boolean stream);

    /** _convert_completion_to_chat_function(name, chunks, stream). */
    Object convertCompletionToFunction(String name, Object chunks, boolean stream);

    /** print(str(e), file=sys.stderr). */
    Consumer<String> stderr();
  }

  /** Ports the nested chat_completion_handler closure body. */
  @SuppressWarnings("unchecked")
  public static Object chatCompletionHandler(Ports ports, Map<String, Object> args) {
    Object messages = args.get("messages");
    Object functions = args.get("functions");
    Object functionCall = args.get("function_call");
    Object tools = args.get("tools");
    Object toolChoice = args.get("tool_choice");
    Object stop = args.get("stop");
    Object responseFormat = args.get("response_format");
    Object logprobs = args.get("logprobs");
    Object topLogprobs = args.get("top_logprobs");
    boolean stream = Boolean.TRUE.equals(args.get("stream"));

    Map<String, Object> formatArgs = new java.util.LinkedHashMap<>();
    formatArgs.put("messages", messages);
    formatArgs.put("functions", functions);
    formatArgs.put("function_call", functionCall);
    formatArgs.put("tools", tools);
    formatArgs.put("tool_choice", toolChoice);
    if (args.get("kwargs") instanceof Map<?, ?> extra) {
      for (Map.Entry<?, ?> entry : extra.entrySet()) {
        formatArgs.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    FormatterResult result = ports.format(formatArgs);

    int[] prompt =
        ports.tokenize(
            result.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            !result.addedSpecial());

    if (result.stop() != null) {
      java.util.List<Object> merged = new java.util.ArrayList<>();
      if (stop != null) {
        if (stop instanceof String single) merged.add(single);
        else merged.addAll((List<Object>) stop);
      }
      if (result.stop() instanceof List<?> list) merged.addAll(list);
      else merged.add(result.stop());
      stop = merged;
    }

    Object stoppingCriteria = result.stoppingCriteria();

    Object grammar = args.get("grammar");
    if (responseFormat instanceof Map<?, ?> rf && "json_object".equals(rf.get("type"))) {
      grammar = ports.grammarForResponseFormat((Map<String, Object>) rf);
    }

    if (functions != null) {
      List<Object> converted = new java.util.ArrayList<>();
      for (Object function : (List<Object>) functions) {
        converted.add(Map.of("type", "function", "function", function));
      }
      tools = converted;
    }

    if (functionCall != null) {
      if (functionCall instanceof String call && ("none".equals(call) || "auto".equals(call))) {
        toolChoice = call;
      }
      if (functionCall instanceof Map<?, ?> callMap && callMap.containsKey("name")) {
        toolChoice = Map.of("type", "function", "function", Map.of("name", callMap.get("name")));
      }
    }

    Object tool = null;
    if (toolChoice instanceof Map<?, ?> choice && tools instanceof List<?> toolList) {
      Object name = ((Map<?, ?>) choice.get("function")).get("name");
      tool =
          toolList.stream()
              .filter(t -> name.equals(((Map<?, ?>) ((Map<?, ?>) t).get("function")).get("name")))
              .findFirst()
              .orElse(null);
      if (tool == null) {
        throw Py.error(
            PythonErrorType.VALUE_ERROR, "Tool choice '" + name + "' not found in tools.");
      }
      Object schema = ((Map<?, ?>) ((Map<?, ?>) tool).get("function")).get("parameters");
      try {
        grammar = ports.grammarFromJsonSchema(PythonJson.dumps(schema));
      } catch (Exception error) {
        if (ports.verbose()) {
          ports.stderr().accept(String.valueOf(error));
        }
        grammar = ports.grammarFromString();
      }
    }

    List<Object> promptList = new java.util.ArrayList<>();
    for (int token : prompt) promptList.add(token);
    Map<String, Object> completionArgs = new java.util.LinkedHashMap<>();
    completionArgs.put("prompt", promptList);
    completionArgs.put("temperature", args.get("temperature"));
    completionArgs.put("top_p", args.get("top_p"));
    completionArgs.put("top_k", args.get("top_k"));
    completionArgs.put("min_p", args.get("min_p"));
    completionArgs.put("typical_p", args.get("typical_p"));
    completionArgs.put("logprobs", Boolean.TRUE.equals(logprobs) ? topLogprobs : null);
    completionArgs.put("stream", args.get("stream"));
    completionArgs.put("stop", stop);
    completionArgs.put("seed", args.get("seed"));
    completionArgs.put("max_tokens", args.get("max_tokens"));
    completionArgs.put("presence_penalty", args.get("presence_penalty"));
    completionArgs.put("frequency_penalty", args.get("frequency_penalty"));
    completionArgs.put("repeat_penalty", args.get("repeat_penalty"));
    completionArgs.put("tfs_z", args.get("tfs_z"));
    completionArgs.put("mirostat_mode", args.get("mirostat_mode"));
    completionArgs.put("mirostat_tau", args.get("mirostat_tau"));
    completionArgs.put("mirostat_eta", args.get("mirostat_eta"));
    completionArgs.put("model", args.get("model"));
    completionArgs.put("logits_processor", args.get("logits_processor"));
    completionArgs.put("stopping_criteria", stoppingCriteria);
    completionArgs.put("grammar", grammar);
    completionArgs.put("logit_bias", args.get("logit_bias"));
    Object completionOrChunks = ports.createCompletion(completionArgs);

    if (tool != null) {
      Object toolName = ((Map<?, ?>) ((Map<?, ?>) tool).get("function")).get("name");
      return ports.convertCompletionToFunction(
          String.valueOf(toolName), completionOrChunks, stream);
    }
    return ports.convertCompletion(completionOrChunks, stream);
  }

  /** Ports chat_formatter_to_chat_completion_handler: returns the handler closure. */
  public static Function<Map<String, Object>, Object> chatFormatterToChatCompletionHandler(
      Ports ports) {
    return args -> chatCompletionHandler(ports, args);
  }
}
