package dev.javacpp.compat.python;

import static dev.javacpp.compat.python.PythonErrorBridge.error;

import dev.javacpp.python.PythonErrorType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * llama_chat_format.py handler/helper bodies that were still missing: grammar selection, HF
 * tokenizer formatters, completion-to-function conversion, functionary nested helpers and the pure
 * message/media helpers. External services are injectable ports.
 */
public final class PythonChatFormatHandlers {

  /** llama_grammar.LlamaGrammar factory surface used by the handlers. */
  public interface GrammarPort {
    /** LlamaGrammar.from_string(grammar) with no kwargs. */
    default Object fromString(String grammar) {
      return fromString(grammar, false);
    }

    Object fromString(String grammar, boolean verbose);

    Object fromJsonSchema(String schema, boolean verbose);

    /** json_schema_to_gbnf(json.dumps(schema)) used by the v1/v2 get_grammar path. */
    Object jsonSchemaToGbnf(String json);

    /** llama_grammar.JSON_GBNF constant. */
    String jsonGbnf();

    /** print(...) to stdout; observable in the v1/v2 get_grammar try block. */
    void printOut(Object value);

    /** suppress_stdout_stderr(disable=verbose) context enter. */
    void suppress(boolean disable);
  }

  /** transformers.AutoTokenizer surface used by the HF formatters and v1/v2 prepare. */
  public interface TokenizerPort {
    Object fromPretrained(Object path);

    void useDefaultSystemPrompt(Object tokenizer, boolean value);

    /** tokenizer.apply_chat_template(messages, tokenize=False, **kwargs). */
    Object applyChatTemplate(Object tokenizer, Object messages, Map<String, Object> kwargs);

    /** tokenizer.eos_token. */
    Object eosToken(Object tokenizer);
  }

  /** ImmutableSandboxedEnvironment(trim_blocks=True, lstrip_blocks=True) surface. */
  public interface JinjaPort {
    /** Environment built from the chat template at formatter construction time. */
    void envFromString(String template);

    /** Env construction with recorded kwarg keys (media handlers record different kwargs). */
    default void envInit(java.util.List<String> kwargKeys, String template) {
      envFromString(template);
    }

    /** env.render(**context). */
    String render(Map<String, Object> context);
  }

  /** Bounded llama view for Qwen25VLChatHandler.__call__ preamble. */
  public interface QwenLlama {
    void reset();

    void kvCacheClear();

    void setNTokens(int nTokens);

    boolean hasInputIds();

    void inputIdsFill(int value);
  }

  public record FormatterResult(String prompt, Object stop, boolean addedSpecial)
      implements PythonChatFormat.FormatterResult {
    @Override
    public Object stoppingCriteria() {
      return null;
    }
  }

  private static String str(Object value) {
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private static String get(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }

  // ---------------------------------------------------------------------------
  // Grammar helpers

  public static Object grammarForJson(GrammarPort port, boolean verbose) {
    return port.fromString(port.jsonGbnf(), verbose);
  }

  public static Object grammarForJsonSchema(
      GrammarPort port, String schema, boolean verbose, boolean fallbackToJson) {
    try {
      return port.fromJsonSchema(schema, verbose);
    } catch (RuntimeException failure) {
      if (fallbackToJson) {
        return grammarForJson(port, verbose);
      }
      throw failure;
    }
  }

  public static Object grammarForResponseFormat(
      GrammarPort port, Map<String, Object> responseFormat, boolean verbose) {
    if (!"json_object".equals(responseFormat.get("type"))) {
      return null;
    }
    if (responseFormat.containsKey("schema")) {
      return grammarForJsonSchema(
          port, PythonJson.dumps(responseFormat.get("schema")), verbose, true);
    }
    return grammarForJson(port, verbose);
  }

  // ---------------------------------------------------------------------------
  // HF tokenizer formatters

  /** hf_autotokenizer_to_chat_formatter plus the nested format_autotokenizer. */
  public static Function<Object[], FormatterResult> hfAutotokenizerToChatFormatter(
      TokenizerPort port, Object pretrainedPath) {
    Object tokenizer = port.fromPretrained(pretrainedPath);
    return args -> {
      Object messages = args[0];
      @SuppressWarnings("unchecked")
      Map<String, Object> kwargs = (Map<String, Object>) args[1];
      port.useDefaultSystemPrompt(tokenizer, false);
      Object prompt = port.applyChatTemplate(tokenizer, messages, kwargs);
      if (!(prompt instanceof String)) {
        throw error(PythonErrorType.ASSERTION_ERROR, "");
      }
      return new FormatterResult((String) prompt, port.eosToken(tokenizer), true);
    };
  }

  /**
   * The wrapper builds the formatter, then wraps it via the already-covered
   * chat_formatter_to_chat_completion_handler.
   */
  public static Function<Object[], FormatterResult> hfAutotokenizerToChatCompletionHandler(
      TokenizerPort port, Object pretrainedPath) {
    return hfAutotokenizerToChatFormatter(port, pretrainedPath);
  }

  /** hf_tokenizer_config_to_chat_formatter plus the nested format_tokenizer_config. */
  public static Function<Object[], FormatterResult> hfTokenizerConfigToChatFormatter(
      JinjaPort port, Map<String, Object> tokenizerConfig, boolean addGenerationPrompt) {
    Objects.requireNonNull(tokenizerConfig, "tokenizerConfig");
    Object template = tokenizerConfig.get("chat_template");
    if (!(template instanceof String)) {
      throw error(PythonErrorType.ASSERTION_ERROR, "");
    }
    String bosToken = (String) requireStr(tokenizerConfig, "bos_token");
    String eosToken = (String) requireStr(tokenizerConfig, "eos_token");
    port.envFromString((String) template);
    return args -> {
      List<Object> messages = new ArrayList<>(list(args[0]));
      @SuppressWarnings("unchecked")
      Map<String, Object> kwargs = (Map<String, Object>) args[1];
      if (addGenerationPrompt) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        messages.add(assistant);
      }
      Map<String, Object> context = new LinkedHashMap<>();
      context.put("messages", messages);
      context.put("bos_token", bosToken);
      context.put("eos_token", eosToken);
      context.putAll(kwargs);
      String prompt = port.render(context);
      return new FormatterResult(prompt, List.of(eosToken, bosToken), true);
    };
  }

  /** Same wrapper shape as the autotokenizer variant. */
  public static Function<Object[], FormatterResult> hfTokenizerConfigToChatCompletionHandler(
      JinjaPort port, Map<String, Object> tokenizerConfig, boolean addGenerationPrompt) {
    return hfTokenizerConfigToChatFormatter(port, tokenizerConfig, addGenerationPrompt);
  }

  private static Object requireStr(Map<String, Object> config, String key) {
    Object value = config.get(key);
    if (!(value instanceof String)) {
      throw error(PythonErrorType.ASSERTION_ERROR, "");
    }
    return value;
  }

  // ---------------------------------------------------------------------------
  // _convert_completion_to_chat_function + nested stream

  @SuppressWarnings("unchecked")
  public static Object convertCompletionToChatFunction(
      String toolName, Object completionOrChunks, boolean stream) {
    if (!stream) {
      Map<String, Object> completion = map(completionOrChunks);
      assertContains(completion, "usage");
      String toolId = "call_" + "_0_" + toolName + "_" + completion.get("id");
      Map<String, Object> firstChoice =
          (Map<String, Object>) ((List<Object>) completion.get("choices")).get(0);
      Map<String, Object> function = new LinkedHashMap<>();
      function.put("name", toolName);
      function.put("arguments", firstChoice.get("text"));
      Map<String, Object> toolCall = new LinkedHashMap<>();
      toolCall.put("id", toolId);
      toolCall.put("type", "function");
      toolCall.put("function", function);
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("role", "assistant");
      message.put("content", null);
      message.put("function_call", function);
      message.put("tool_calls", List.of(toolCall));
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("index", 0);
      choice.put("message", message);
      choice.put(
          "logprobs",
          PythonChatCompletions.convertTextCompletionLogprobsToChat(firstChoice.get("logprobs")));
      choice.put("finish_reason", "tool_calls");
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", "chat" + completion.get("id"));
      result.put("object", "chat.completion");
      result.put("created", completion.get("created"));
      result.put("model", completion.get("model"));
      result.put("choices", List.of(choice));
      result.put("usage", completion.get("usage"));
      return result;
    }
    return streamResponseToFunctionStream(toolName, (List<Object>) completionOrChunks);
  }

  /** The nested generator materialized as a list; chunk order and dict shapes are unchanged. */
  @SuppressWarnings("unchecked")
  public static List<Object> streamResponseToFunctionStream(String toolName, List<Object> chunks) {
    List<Object> out = new ArrayList<>();
    boolean first = true;
    String id = null;
    Object created = null;
    Object model = null;
    String toolId = null;
    for (Object chunkObj : chunks) {
      Map<String, Object> chunk = map(chunkObj);
      Map<String, Object> choice0 = map(((List<Object>) chunk.get("choices")).get(0));
      if (first) {
        id = "chat" + chunk.get("id");
        created = chunk.get("created");
        model = chunk.get("model");
        toolId = "call_" + "_0_" + toolName + "_" + chunk.get("id");
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        delta.put("content", null);
        delta.put("function_call", null);
        delta.put("tool_calls", null);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("finish_reason", null);
        choice.put("logprobs", null);
        choice.put("delta", delta);
        out.add(chunkMsg(id, "chat.completion.chunk", created, model, choice));
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", toolName);
        function.put("arguments", choice0.get("text"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", 0);
        toolCall.put("id", toolId);
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> delta2 = new LinkedHashMap<>();
        delta2.put("role", null);
        delta2.put("content", null);
        delta2.put("function_call", function);
        delta2.put("tool_calls", List.of(toolCall));
        Map<String, Object> choice2 = new LinkedHashMap<>();
        choice2.put("index", 0);
        choice2.put("finish_reason", null);
        choice2.put(
            "logprobs",
            PythonChatCompletions.convertTextCompletionLogprobsToChat(choice0.get("logprobs")));
        choice2.put("delta", delta2);
        out.add(
            chunkMsg(
                "chat" + chunk.get("id"),
                "chat.completion.chunk",
                chunk.get("created"),
                chunk.get("model"),
                choice2));
        first = false;
        continue;
      }
      Map<String, Object> function = new LinkedHashMap<>();
      function.put("name", toolName);
      function.put("arguments", choice0.get("text"));
      Map<String, Object> toolCall = new LinkedHashMap<>();
      toolCall.put("index", 0);
      toolCall.put("id", toolId);
      toolCall.put("type", "function");
      toolCall.put("function", function);
      Map<String, Object> delta = new LinkedHashMap<>();
      delta.put("role", null);
      delta.put("content", null);
      delta.put("function_call", function);
      delta.put("tool_calls", List.of(toolCall));
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("index", 0);
      choice.put("finish_reason", null);
      choice.put(
          "logprobs",
          PythonChatCompletions.convertTextCompletionLogprobsToChat(choice0.get("logprobs")));
      choice.put("delta", delta);
      out.add(
          chunkMsg(
              "chat" + chunk.get("id"),
              "chat.completion.chunk",
              chunk.get("created"),
              chunk.get("model"),
              choice));
    }
    if (id != null && created != null && model != null) {
      Map<String, Object> delta = new LinkedHashMap<>();
      delta.put("role", null);
      delta.put("content", null);
      delta.put("function_call", null);
      delta.put("tool_calls", null);
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("index", 0);
      choice.put("finish_reason", "tool_calls");
      choice.put("logprobs", null);
      choice.put("delta", delta);
      out.add(chunkMsg(id, "chat.completion.chunk", created, model, choice));
    }
    return out;
  }

  private static Map<String, Object> chunkMsg(
      Object id, String object, Object created, Object model, Map<String, Object> choice) {
    Map<String, Object> chunk = new LinkedHashMap<>();
    chunk.put("id", id);
    chunk.put("object", object);
    chunk.put("created", created);
    chunk.put("model", model);
    chunk.put("choices", List.of(choice));
    return chunk;
  }

  private static void assertContains(Map<String, Object> map, String key) {
    if (!map.containsKey(key)) {
      throw error(PythonErrorType.ASSERTION_ERROR, "");
    }
  }

  // ---------------------------------------------------------------------------
  // functionary nested helpers (v1 and v1/v2 share these bodies)

  public static String generateTypeDefinition(
      Map<String, Object> param, int indentLevel, Map<String, Object> sharedDefs) {
    String indent = "  ".repeat(indentLevel);
    if (param.containsKey("$ref")) {
      String ref = (String) param.get("$ref");
      return ref.substring(ref.lastIndexOf('/') + 1);
    } else if ("array".equals(param.get("type"))) {
      Map<String, Object> items =
          param.get("items") instanceof Map ? map(param.get("items")) : new LinkedHashMap<>();
      return "Array<" + generateTypeDefinition(items, indentLevel + 1, sharedDefs) + ">";
    } else if ("object".equals(param.get("type"))) {
      Map<String, Object> properties =
          param.get("properties") instanceof Map
              ? map(param.get("properties"))
              : new LinkedHashMap<>();
      StringBuilder schema = new StringBuilder("{\n");
      for (var entry : properties.entrySet()) {
        schema
            .append(indent)
            .append("  ")
            .append(entry.getKey())
            .append(": ")
            .append(generateTypeDefinition(map(entry.getValue()), indentLevel + 1, sharedDefs))
            .append(",\n");
      }
      schema.append(indent).append("}");
      return schema.toString();
    } else if (param.containsKey("enum")) {
      List<String> values = new ArrayList<>();
      for (Object value : list(param.get("enum"))) {
        values.add("\"" + value + "\"");
      }
      return String.join(" | ", values);
    }
    Object type = param.get("type");
    return type == null ? "any" : type.toString();
  }

  public static String generateSharedDefinitions(Map<String, Object> sharedDefs, int indentLevel) {
    String indent = "  ".repeat(indentLevel);
    StringBuilder out = new StringBuilder();
    for (var entry : sharedDefs.entrySet()) {
      Map<String, Object> properties = map(entry.getValue());
      out.append(indent).append("type ").append(entry.getKey()).append(" = ");
      if ("object".equals(properties.get("type"))) {
        out.append(generateTypeDefinition(properties, indentLevel, sharedDefs));
      } else if (properties.containsKey("enum")) {
        List<String> values = new ArrayList<>();
        for (Object value : list(properties.get("enum"))) {
          values.add("\"" + value + "\"");
        }
        out.append(String.join(" | ", values));
      }
      out.append(";\n");
    }
    return out.toString();
  }

  /** v1 flavor: two-space indent in the body and trailing newline after the closing brace. */
  public static String generateSchemaFromFunctionsV1(List<Object> functions, String namespace) {
    StringBuilder schema =
        new StringBuilder(
            "// Supported function definitions that should be called when necessary.\n");
    schema.append("namespace ").append(namespace).append(" {\n\n");
    Map<String, Object> shared = new LinkedHashMap<>();
    for (Object functionObj : functions) {
      Map<String, Object> function = map(functionObj);
      Object parameters = function.get("parameters");
      if (parameters instanceof Map) {
        Object defs = map(parameters).get("$defs");
        if (defs instanceof Map) {
          shared.putAll(map(defs));
        }
      }
    }
    schema.append(generateSharedDefinitions(shared, 1));
    for (Object functionObj : functions) {
      Map<String, Object> function = map(functionObj);
      Map<String, Object> parameters =
          function.get("parameters") instanceof Map
              ? map(function.get("parameters"))
              : new LinkedHashMap<>();
      List<Object> required =
          parameters.get("required") instanceof List ? list(parameters.get("required")) : List.of();
      schema.append("  // ").append(function.getOrDefault("description", "")).append('\n');
      schema.append("  type ").append(function.get("name")).append(" = (_: {\n");
      Object properties = parameters.get("properties");
      if (properties instanceof Map) {
        for (var entry : map(properties).entrySet()) {
          Map<String, Object> param = map(entry.getValue());
          schema
              .append("    // ")
              .append(param.getOrDefault("description", ""))
              .append('\n')
              .append("    ")
              .append(entry.getKey())
              .append(required.contains(entry.getKey()) ? "" : "?")
              .append(": ")
              .append(generateTypeDefinition(param, 2, shared))
              .append(",\n");
        }
      }
      schema.append("  }) => any;\n\n");
    }
    schema.append("} // namespace ").append(namespace).append('\n');
    return schema.toString();
  }

  /** v1/v2 flavor: no body indent and no trailing newline after the closing brace. */
  public static String generateSchemaFromFunctionsV2(List<Object> functions, String namespace) {
    StringBuilder schema =
        new StringBuilder(
            "// Supported function definitions that should be called when necessary.\n");
    schema.append("namespace ").append(namespace).append(" {\n\n");
    Map<String, Object> shared = new LinkedHashMap<>();
    for (Object functionObj : functions) {
      Map<String, Object> function = map(functionObj);
      Object parameters = function.get("parameters");
      if (parameters instanceof Map) {
        Object defs = map(parameters).get("$defs");
        if (defs instanceof Map) {
          shared.putAll(map(defs));
        }
      }
    }
    schema.append(generateSharedDefinitions(shared, 1));
    for (Object functionObj : functions) {
      Map<String, Object> function = map(functionObj);
      Map<String, Object> parameters =
          function.get("parameters") instanceof Map
              ? map(function.get("parameters"))
              : new LinkedHashMap<>();
      List<Object> required =
          parameters.get("required") instanceof List ? list(parameters.get("required")) : List.of();
      schema.append("// ").append(function.getOrDefault("description", "")).append('\n');
      schema.append("type ").append(function.get("name")).append(" = (_: {\n");
      Object properties = parameters.get("properties");
      if (properties instanceof Map) {
        for (var entry : map(properties).entrySet()) {
          Map<String, Object> param = map(entry.getValue());
          schema
              .append("// ")
              .append(param.getOrDefault("description", ""))
              .append('\n')
              .append(entry.getKey())
              .append(required.contains(entry.getKey()) ? "" : "?")
              .append(": ")
              .append(generateTypeDefinition(param, 2, shared))
              .append(",\n");
        }
      }
      schema.append("}) => any;\n\n");
    }
    schema.append("} // namespace ").append(namespace);
    return schema.toString();
  }

  public static final String FUNCTIONARY_SYSTEM_MESSAGE =
      "A chat between a curious user and an artificial intelligence assistant. The assistant"
          + " gives helpful, detailed, and polite answers to the user's questions. The assistant"
          + " calls functions with appropriate input when necessary";

  /** functionary prepare_messages_for_inference (v1) plus message_to_str. */
  public static String prepareMessagesForInferenceV1(
      List<Object> messages, List<Object> functions, List<Object> tools) {
    List<Map<String, Object>> all = new ArrayList<>();
    if (functions != null) {
      all.add(systemMessage(generateSchemaFromFunctionsV1(functions, "functions")));
    }
    if (tools != null) {
      List<Object> toolFunctions = new ArrayList<>();
      for (Object toolObj : tools) {
        Map<String, Object> tool = map(toolObj);
        if ("function".equals(tool.get("type"))) {
          toolFunctions.add(tool.get("function"));
        }
      }
      all.add(systemMessage(generateSchemaFromFunctionsV1(toolFunctions, "functions")));
    }
    all.add(systemMessage(FUNCTIONARY_SYSTEM_MESSAGE));
    for (Object messageObj : messages) {
      Map<String, Object> message = map(messageObj);
      if ("function".equals(message.get("role")) && message.containsKey("name")) {
        message.put("name", "functions." + message.get("name"));
      }
      if (message.containsKey("function_call")) {
        map(message.get("function_call")).compute("name", (k, v) -> "functions." + v);
      }
      all.add(message);
    }
    Map<String, Object> assistant = new LinkedHashMap<>();
    assistant.put("role", "assistant");
    assistant.put("content", null);
    all.add(assistant);
    StringBuilder out = new StringBuilder();
    for (Map<String, Object> message : all) {
      out.append(messageToStr(message));
    }
    return out.toString();
  }

  private static Map<String, Object> systemMessage(String content) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "system");
    message.put("content", content);
    return message;
  }

  private static String messageToStr(Map<String, Object> msg) {
    Object role = msg.get("role");
    if ("system".equals(role)) {
      return "system:\n" + msg.get("content") + "\n";
    } else if ("function".equals(role) && msg.containsKey("name")) {
      return "function name=" + msg.get("name") + ":\n" + msg.get("content") + "\n";
    } else if ("function".equals(role) && msg.containsKey("function_call")) {
      Map<String, Object> call = map(msg.get("function_call"));
      return "function name=" + call.get("name") + ":\n" + call.get("arguments") + "\n";
    } else if ("tool".equals(role)) {
      if (msg.get("content") != null) {
        return "function name=" + msg.get("tool_call_id") + ":\n" + msg.get("content") + "\n";
      }
      return "function name=" + msg.get("tool_call_id") + "\n";
    } else if ("user".equals(role)) {
      if (msg.get("content") == null) {
        return "user:\n</s></s>\n";
      }
      return "user:\n</s>" + msg.get("content") + "</s>\n";
    } else if ("assistant".equals(role)) {
      if (msg.get("content") != null && msg.containsKey("function_call")) {
        Map<String, Object> call = map(msg.get("function_call"));
        return "assistant:\n"
            + msg.get("content")
            + "\nassistant to="
            + call.get("name")
            + ":\n"
            + call.get("arguments")
            + "</s>\n";
      } else if (msg.containsKey("function_call")) {
        Map<String, Object> call = map(msg.get("function_call"));
        return "assistant to=" + call.get("name") + ":\n" + call.get("arguments") + "</s>\n";
      } else if (msg.containsKey("tool_calls") && !list(msg.get("tool_calls")).isEmpty()) {
        Map<String, Object> toolCall = map(list(msg.get("tool_calls")).get(0));
        return "assistant to="
            + toolCall.get("id")
            + ":\n"
            + map(toolCall.get("function")).get("arguments")
            + "</s>\n";
      } else if (msg.get("content") == null) {
        return "assistant";
      }
      return "assistant:\n" + msg.get("content") + "\n";
    }
    throw error(PythonErrorType.VALUE_ERROR, "Unsupported role: " + role);
  }

  /** v1/v2 prepare_messages_for_inference: template rendered by the tokenizer port. */
  public static String prepareMessagesForInferenceV2(
      TokenizerPort port,
      Object tokenizer,
      List<Object> messages,
      String version,
      List<Object> functions,
      List<Object> tools,
      Object toolChoice) {
    List<Map<String, Object>> all = new ArrayList<>();
    if ("none".equals(toolChoice)) {
      all.add(systemMessage(generateSchemaFromFunctionsV2(List.of(), "functions")));
    } else {
      if (functions != null) {
        all.add(systemMessage(generateSchemaFromFunctionsV2(functions, "functions")));
      } else if (tools != null && !"none".equals(toolChoice)) {
        List<Object> toolFunctions = new ArrayList<>();
        for (Object toolObj : tools) {
          Map<String, Object> tool = map(toolObj);
          if ("function".equals(tool.get("type"))) {
            toolFunctions.add(tool.get("function"));
          }
        }
        all.add(systemMessage(generateSchemaFromFunctionsV2(toolFunctions, "functions")));
      }
    }
    all.add(systemMessage(FUNCTIONARY_SYSTEM_MESSAGE));
    for (Object messageObj : messages) {
      Map<String, Object> message = map(messageObj);
      if ("function".equals(message.get("role")) && message.containsKey("name")) {
        message.put("name", "functions." + message.get("name"));
      }
      if (message.containsKey("function_call")) {
        map(message.get("function_call")).compute("name", (k, v) -> "functions." + v);
      }
      all.add(message);
    }
    String suffix = "v1".equals(version) ? "assistant:\n" : "<|from|>assistant\n<|recipient|>";
    Object rendered = port.applyChatTemplate(tokenizer, all, Map.of("tokenize", false));
    return rendered + suffix;
  }

  /** v1/v2 get_grammar: schema lookup order, json_schema_to_gbnf attempts and fallback. */
  public static Object functionaryGetGrammar(
      GrammarPort port,
      Object functionCall,
      List<Object> functions,
      List<Object> tools,
      boolean verbose) {
    Object functionBody = null;
    if (functions != null) {
      for (Object functionObj : functions) {
        Map<String, Object> function = map(functionObj);
        if (functionCall.equals(function.get("name"))) {
          functionBody = function.get("parameters");
          break;
        }
      }
    }
    if (tools != null) {
      for (Object toolObj : tools) {
        Map<String, Object> tool = map(toolObj);
        if ("function".equals(tool.get("type"))
            && functionCall.equals(map(tool.get("function")).get("name"))) {
          functionBody = map(tool.get("function")).get("parameters");
          break;
        }
      }
    }
    try {
      port.suppress(verbose);
      Object grammarText = port.jsonSchemaToGbnf(PythonJson.dumps(functionBody));
      Object grammar =
          port.fromString((String) port.jsonSchemaToGbnf(PythonJson.dumps(functionBody)));
      port.printOut(grammarText);
      return grammar;
    } catch (RuntimeException failure) {
      if (verbose) {
        port.printOut(
            "Failed to parse function body as JSON schema, falling back to default grammar");
        port.printOut(failure);
      }
      port.suppress(verbose);
      return port.fromString(port.jsonGbnf(), verbose);
    }
  }

  // ---------------------------------------------------------------------------
  // Media-message helpers (Llava15 / MTMD share these bodies)

  public static List<String> getImageUrls(List<Object> messages) {
    List<String> urls = new ArrayList<>();
    for (Object messageObj : messages) {
      Map<String, Object> message = map(messageObj);
      if (!"user".equals(message.get("role"))) {
        continue;
      }
      Object content = message.get("content");
      if (content == null) {
        continue;
      }
      if (!(content instanceof List)) {
        continue;
      }
      for (Object part : list(content)) {
        if (part instanceof Map && map(part).containsKey("type")) {
          if ("image_url".equals(map(part).get("type"))) {
            Object url = map(part).get("image_url");
            if (url instanceof Map && map(url).containsKey("url")) {
              urls.add(map(url).get("url").toString());
            } else {
              urls.add(url.toString());
            }
          }
        }
      }
    }
    return urls;
  }

  /** split_text_on_image_urls plus the nested find_first. */
  public static List<List<Object>> splitTextOnImageUrls(String text, List<String> imageUrls) {
    List<List<Object>> out = new ArrayList<>();
    String remaining = text;
    while (!remaining.isEmpty()) {
      Integer pos = null;
      Integer index = null;
      for (int i = 0; i < imageUrls.size(); i++) {
        int found = remaining.indexOf(imageUrls.get(i));
        if (found != -1) {
          pos = found;
          index = i;
          break;
        }
      }
      if (pos != null) {
        if (pos > 0) {
          out.add(List.of("text", remaining.substring(0, pos)));
        }
        out.add(List.of("image_url", imageUrls.get(index)));
        remaining = remaining.substring(pos + imageUrls.get(index).length());
      } else {
        out.add(List.of("text", remaining));
        remaining = "";
      }
    }
    return out;
  }

  public static Object decodeTokenPiece(Object piece) {
    if (piece instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return String.valueOf(piece);
  }

  public static Object convertContentPartForTemplate(Object part, String mediaMarker) {
    if (part instanceof Map && "image_url".equals(map(part).get("type"))) {
      Map<String, Object> text = new LinkedHashMap<>();
      text.put("type", "text");
      text.put("text", mediaMarker);
      return text;
    }
    return part;
  }

  public static Map<String, Object> convertMessageForTemplate(
      Map<String, Object> message, String mediaMarker) {
    Map<String, Object> copy = new LinkedHashMap<>(message);
    Object content = copy.get("content");
    if (content instanceof List) {
      List<Object> converted = new ArrayList<>();
      for (Object part : list(content)) {
        converted.add(convertContentPartForTemplate(part, mediaMarker));
      }
      copy.put("content", converted);
    }
    return copy;
  }

  public static List<Object> getTemplateMessages(List<Object> messages, String mediaMarker) {
    List<Object> out = new ArrayList<>();
    for (Object message : messages) {
      out.add(convertMessageForTemplate(map(message), mediaMarker));
    }
    return out;
  }

  public static String postprocessTemplateText(
      String text, List<String> imageUrls, String mediaMarker) {
    for (String url : imageUrls) {
      text = text.replace(url, mediaMarker);
    }
    return text;
  }

  /** Qwen25VLChatHandler.__call__ preamble; parentCall stands in for super().__call__(**kwargs). */
  public static Object qwen25vlCall(
      Map<String, Object> kwargs,
      QwenLlama llama,
      boolean verbose,
      java.util.function.BiConsumer<String, Boolean> setLastImageState,
      boolean hasLastImageEmbed,
      Function<Map<String, Object>, Object> parentCall,
      java.util.function.Consumer<String> stderr) {
    llama.reset();
    llama.kvCacheClear();
    llama.setNTokens(0);
    if (llama.hasInputIds()) {
      llama.inputIdsFill(0);
    }
    if (hasLastImageEmbed) {
      setLastImageState.accept("embed", null);
      setLastImageState.accept("hash", null);
    }
    if (verbose) {
      Object messages = kwargs.getOrDefault("messages", List.of());
      int imageCount = getImageUrls(list(messages)).size();
      stderr.accept("Minimal - Cleared state, processing " + imageCount + " images");
    }
    return parentCall.apply(kwargs);
  }
}
