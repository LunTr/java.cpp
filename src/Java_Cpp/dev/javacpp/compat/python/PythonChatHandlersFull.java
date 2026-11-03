package dev.javacpp.compat.python;

import static dev.javacpp.compat.python.PythonErrorBridge.error;

import dev.javacpp.python.PythonErrorType;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The remaining llama_chat_format.py bodies: Jinja2ChatFormatter, the functionary outer handlers,
 * the llava/mtmd media handlers and chatml_function_calling. Native/template/network surfaces are
 * injectable ports.
 */
public final class PythonChatHandlersFull {

  private PythonChatHandlersFull() {}

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object v) {
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object v) {
    return (List<Object>) v;
  }

  // ---------------------------------------------------------------------------
  // Jinja2ChatFormatter

  /** jinja2.parser.Parser surface used by IgnoreGenerationTags.parse. */
  public interface JinjaParser {
    void skip(int n);

    Object parseStatements(Object endNames, boolean dropNeedle);
  }

  /** Environment surface used by Jinja2ChatFormatter. */
  public interface JinjaEnv {
    void init(Map<String, Object> kwargs, List<Object> extensions);

    void setFilter(String name, Object filter);

    void buildTemplate(String template);

    String render(Map<String, Object> context);
  }

  public static Object ignoreGenerationTagsParse(JinjaParser parser) {
    parser.skip(1);
    return parser.parseStatements(List.of("name:endgeneration"), true);
  }

  public static final class Jinja2ChatFormatter {
    public final String template;
    public final String eosToken;
    public final String bosToken;
    public final boolean addGenerationPrompt;
    public final Set<Integer> stopTokenIds;
    private final JinjaEnv env;

    public Jinja2ChatFormatter(
        JinjaEnv env,
        String template,
        String eosToken,
        String bosToken,
        boolean addGenerationPrompt,
        List<Object> stopTokenIds) {
      this.env = env;
      this.template = template;
      this.eosToken = eosToken;
      this.bosToken = bosToken;
      this.addGenerationPrompt = addGenerationPrompt;
      if (stopTokenIds == null) {
        this.stopTokenIds = null;
      } else {
        this.stopTokenIds = new LinkedHashSet<>();
        for (Object v : list(stopTokenIds)) this.stopTokenIds.add(((Number) v).intValue());
      }
      Map<String, Object> kwargs = new LinkedHashMap<>();
      kwargs.put("loader", "BaseLoader");
      kwargs.put("trim_blocks", true);
      kwargs.put("lstrip_blocks", true);
      kwargs.put("extensions", List.of("IgnoreGenerationTags", "loopcontrols"));
      env.init(kwargs, List.of("IgnoreGenerationTags", "loopcontrols"));
      env.setFilter("tojson", "tojson");
      env.buildTemplate(template);
    }

    public Map<String, Object> call(Map<String, Object> args) {
      Map<String, Object> context = new LinkedHashMap<>();
      context.put("messages", args.get("messages"));
      context.put("eos_token", eosToken);
      context.put("bos_token", bosToken);
      context.put("raise_exception", "raise_exception");
      context.put("add_generation_prompt", addGenerationPrompt);
      context.put("functions", args.get("functions"));
      context.put("function_call", args.get("function_call"));
      context.put("tools", args.get("tools"));
      context.put("tool_choice", args.get("tool_choice"));
      context.put("strftime_now", "strftime_now");
      Set<String> known =
          Set.of("messages", "functions", "function_call", "tools", "tool_choice", "kwargs");
      @SuppressWarnings("unchecked")
      Map<String, Object> kwargs = (Map<String, Object>) args.get("kwargs");
      if (kwargs != null) context.putAll(kwargs);
      for (var e : args.entrySet()) {
        if (!known.contains(e.getKey())) context.put(e.getKey(), e.getValue());
      }
      String prompt = env.render(context);
      Object stoppingCriteria = null;
      if (stopTokenIds != null) {
        stoppingCriteria = List.of("stopping", List.of("stop_on_last_token"));
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("prompt", prompt);
      response.put("stop", List.of(eosToken));
      response.put("stopping_criteria", stoppingCriteria);
      response.put("added_special", true);
      return response;
    }

    public boolean stopOnLastToken(List<Object> tokens) {
      return stopTokenIds.contains(((Number) tokens.get(tokens.size() - 1)).intValue());
    }

    public String toChatHandler() {
      return "chat_completion_handler";
    }
  }

  // ---------------------------------------------------------------------------
  // functionary outer handlers

  /** llama.create_completion surface for the handlers. */
  public interface LlamaPort {
    Object createCompletion(Map<String, Object> kwargs);

    boolean verbose();

    Object detokenize(List<Object> tokens);

    Object tokenEos();

    Object tokenBos();
  }

  /** functionary_chat_handler (v1 outer body). */
  public static Object functionaryChatHandler(
      PythonChatFormatHandlers.GrammarPort grammar,
      LlamaPort llama,
      PythonChatFormatHandlers.TokenizerPort tokenizerPort,
      Map<String, Object> kw) {
    List<Object> messages = list(kw.get("messages"));
    List<Object> functions = (List<Object>) kw.get("functions");
    List<Object> tools = (List<Object>) kw.get("tools");
    Object toolChoice = kw.get("tool_choice");
    Object functionCall = kw.get("function_call");
    boolean stream = Boolean.TRUE.equals(kw.get("stream"));

    if (tools != null) {
      List<Object> fns = new ArrayList<>();
      for (Object t : tools) {
        if ("function".equals(map(t).get("type"))) fns.add(map(t).get("function"));
      }
      functions = fns;
    }
    if (toolChoice != null) {
      functionCall = toolChoice instanceof Map ? map(toolChoice).get("function") : toolChoice;
    }

    String prompt =
        PythonChatFormatHandlers.prepareMessagesForInferenceV1(messages, functions, tools);

    Map<String, Object> createArgs = new LinkedHashMap<>();
    if (functionCall == null && (functions == null || functions.isEmpty())) {
      createArgs.put("prompt", prompt + ":\n");
      createArgs.put("temperature", kw.get("temperature"));
      createArgs.put("top_p", kw.get("top_p"));
      createArgs.put("top_k", kw.get("top_k"));
      createArgs.put("min_p", kw.get("min_p"));
      createArgs.put("typical_p", kw.get("typical_p"));
      createArgs.put("stream", stream);
      createArgs.put("stop", List.of("user:", "</s>"));
      createArgs.put("max_tokens", kw.get("max_tokens"));
      createArgs.put("presence_penalty", kw.get("presence_penalty"));
      createArgs.put("frequency_penalty", kw.get("frequency_penalty"));
      createArgs.put("repeat_penalty", kw.get("repeat_penalty"));
      createArgs.put("tfs_z", kw.get("tfs_z"));
      createArgs.put("mirostat_mode", kw.get("mirostat_mode"));
      createArgs.put("mirostat_tau", kw.get("mirostat_tau"));
      createArgs.put("mirostat_eta", kw.get("mirostat_eta"));
      createArgs.put("model", kw.get("model"));
      createArgs.put("logits_processor", kw.get("logits_processor"));
      createArgs.put("grammar", kw.get("grammar"));
      return PythonChatCompletions.convertCompletionToChat(
          llama.createCompletion(createArgs), stream);
    }

    String newPrompt;
    if (functionCall == null || (functionCall instanceof String && "auto".equals(functionCall))) {
      Map<String, Object> probeArgs = new LinkedHashMap<>();
      probeArgs.put("prompt", prompt);
      probeArgs.put("stop", "\n");
      probeArgs.put("stream", false);
      Object completion = llama.createCompletion(probeArgs);
      String text = (String) map(list(map(completion).get("choices")).get(0)).get("text");
      functionCall = text.substring(text.lastIndexOf('.') + 1, text.length() - 1);
      newPrompt = prompt + text + "\n";
    } else if (functionCall instanceof String && !"none".equals(functionCall)) {
      newPrompt = prompt + ":\n";
    } else if (functionCall instanceof Map) {
      functionCall = map(functionCall).get("name");
      newPrompt = prompt + " to=functions." + functionCall + ":\n";
    } else {
      newPrompt = prompt + ":\n";
    }

    Object functionBody = null;
    if (functions != null) {
      for (Object f : functions) {
        if (functionCall.equals(map(f).get("name"))) {
          functionBody = map(f).get("parameters");
          break;
        }
      }
    }
    if (tools != null) {
      for (Object t : tools) {
        if ("function".equals(map(t).get("type"))
            && functionCall.equals(map(map(t).get("function")).get("name"))) {
          functionBody = map(map(t).get("function")).get("parameters");
          break;
        }
      }
    }

    Object grammarObj;
    boolean verbose = llama.verbose();
    if (functionBody != null) {
      try {
        grammar.suppress(verbose);
        Object grammarText = grammar.jsonSchemaToGbnf(PythonJson.dumps(functionBody));
        grammarObj =
            grammar.fromString(
                (String) grammar.jsonSchemaToGbnf(PythonJson.dumps(functionBody)), verbose);
        grammar.printOut(grammarText);
      } catch (RuntimeException failure) {
        if (verbose) {
          grammar.printOut(
              "Failed to parse function body as JSON schema, falling back to default grammar");
          grammar.printOut(failure);
        }
        grammar.suppress(verbose);
        grammarObj = grammar.fromString(grammar.jsonGbnf(), verbose);
      }
    } else {
      grammar.suppress(verbose);
      grammarObj = grammar.fromString(grammar.jsonGbnf(), verbose);
    }

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("prompt", newPrompt);
    args.put("stop", List.of("user:", "</s>"));
    args.put("stream", false);
    args.put("grammar", grammarObj);
    args.put("max_tokens", kw.get("max_tokens"));
    args.put("temperature", kw.get("temperature"));
    args.put("top_p", kw.get("top_p"));
    args.put("top_k", kw.get("top_k"));
    args.put("min_p", kw.get("min_p"));
    args.put("typical_p", kw.get("typical_p"));
    args.put("presence_penalty", kw.get("presence_penalty"));
    args.put("frequency_penalty", kw.get("frequency_penalty"));
    args.put("repeat_penalty", kw.get("repeat_penalty"));
    args.put("tfs_z", kw.get("tfs_z"));
    args.put("mirostat_mode", kw.get("mirostat_mode"));
    args.put("mirostat_tau", kw.get("mirostat_tau"));
    args.put("mirostat_eta", kw.get("mirostat_eta"));
    args.put("model", kw.get("model"));
    args.put("logits_processor", kw.get("logits_processor"));
    Object completion = llama.createCompletion(args);
    Map<String, Object> cmp = map(completion);
    if (!cmp.containsKey("usage")) throw error(PythonErrorType.ASSERTION_ERROR, "");
    if (!(functionCall instanceof String)) throw error(PythonErrorType.ASSERTION_ERROR, "");
    if (stream) throw error(PythonErrorType.ASSERTION_ERROR, "");
    if (verbose) {
      grammar.printOut(newPrompt);
      grammar.printOut(map(list(cmp.get("choices")).get(0)).get("text"));
    }
    Map<String, Object> first = map(list(cmp.get("choices")).get(0));
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", functionCall);
    function.put("arguments", first.get("text"));
    Map<String, Object> toolCall = new LinkedHashMap<>();
    toolCall.put("id", functionCall);
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
        PythonChatCompletions.convertTextCompletionLogprobsToChat(first.get("logprobs")));
    choice.put("finish_reason", "tool_calls");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", "chat" + cmp.get("id"));
    result.put("object", "chat.completion");
    result.put("created", cmp.get("created"));
    result.put("model", cmp.get("model"));
    result.put("choices", List.of(choice));
    result.put("usage", cmp.get("usage"));
    return result;
  }

  // ---------------------------------------------------------------------------
  // functionary_v1_v2 outer handler + create_completion + generate_streaming

  public static final String LLAVA_CHAT_FORMAT =
      "{% for message in messages %}{% if message.role == 'system' %}{{ message.content }}{% endif %}{% if message.role == 'user' %}{% if message.content is string %}\nUSER: {{ message.content }}{% endif %}{% if message.content is iterable %}\nUSER: {% for content in message.content %}{% if content.type == 'image_url' and content.image_url is string %}{{ content.image_url }}{% endif %}{% if content.type == 'image_url' and content.image_url is mapping %}{{ content.image_url.url }}{% endif %}{% endfor %}{% for content in message.content %}{% if content.type == 'text' %}{{ content.text }}{% endif %}{% endfor %}{% endif %}{% endif %}{% if message.role == 'assistant' and message.content is not none %}\nASSISTANT: {{ message.content }}{% endif %}{% endfor %}{% if add_generation_prompt %}\nASSISTANT: {% endif %}";

  public static final String CHATML_TEMPLATE =
      "{% for message in messages %}<|im_start|>{{ message.role }}\n{% if message.role == 'system' %}{{ message.content }}{% if tool_calls %}\n\nYou have access to the following functions:\n{% for tool in tools %}\nfunctions.{{ tool.function.name }}:\n{{ tool.function.parameters | tojson }}\n{% endfor %}\n\nYou can respond to users messages with either a single message or one or more function calls.\n\nTo respond with a message begin the message with 'message:', use the following format:\n\nmessage:\n<message>\n\nTo respond with one or more function calls begin the message with 'functions.<function_name>:', use the following format:\n\nfunctions.<function_name>:\n{ \"arg1\": \"value1\", \"arg2\": \"value2\" }\nfunctions.<function_name>:\n{ \"arg1\": \"value1\", \"arg2\": \"value2\" }{% endif %}<|im_end|>\n{% endif %}{% if message.role == 'user' %}{{ message.content }}<|im_end|>\n{% endif %}{% if message.role == 'assistant' %}{% if message.content and message.content | length > 0 %}{% if tool_calls %}message:\n{% endif %}{{ message.content }}<|im_end|>\n{% endif %}{% if 'tool_calls' in message %}{% for tool_call in message.tool_calls %}functions.{{ tool_call.function.name }}:\n{{ tool_call.function.arguments }}{% endfor %}<|im_end|>\n{% endif %}{% endif %}{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}";

  public static final class FunctionaryV2 {
    public final String version;
    public final String endSystem,
        endUser,
        endAssistant,
        endFunctionResult,
        startFunctionCall,
        endFunctionCall,
        recipientToken,
        fromToken,
        stopToken,
        contentToken;
    private final LlamaPort llama;
    private final PythonChatFormatHandlers.GrammarPort grammar;
    private final PythonChatFormatHandlers.TokenizerPort tokenizerPort;
    private final Object tokenizer;

    public FunctionaryV2(
        String ver,
        LlamaPort llama,
        PythonChatFormatHandlers.GrammarPort grammar,
        PythonChatFormatHandlers.TokenizerPort tokenizerPort,
        Object tokenizer) {
      this.version = ver;
      this.llama = llama;
      this.grammar = grammar;
      this.tokenizerPort = tokenizerPort;
      this.tokenizer = tokenizer;
      if ("v1".equals(ver)) {
        endSystem = "<|END_OF_SYSTEM|>";
        endUser = "<|END_OF_USER|>";
        endAssistant = "<|END_OF_ASSISTANT|>";
        endFunctionResult = "<|END_OF_FUNCTION_RESULT|>";
        startFunctionCall = "<|START_OF_FUNCTION_CALL|>";
        endFunctionCall = "<|END_OF_FUNCTION_CALL|>";
        recipientToken = fromToken = stopToken = contentToken = null;
      } else {
        recipientToken = "<|recipient|>";
        fromToken = "<|from|>";
        stopToken = "<|stop|>";
        contentToken = "<|content|>";
        endSystem =
            endUser = endAssistant = endFunctionResult = startFunctionCall = endFunctionCall = null;
      }
    }

    /** create_completion(prompt, stop, grammar): the kwargs passthrough. */
    public Object createCompletion(
        String prompt, Object stop, Object grammarObj, Map<String, Object> kw) {
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("prompt", prompt);
      args.put("temperature", kw.get("temperature"));
      args.put("top_p", kw.get("top_p"));
      args.put("top_k", kw.get("top_k"));
      args.put("min_p", kw.get("min_p"));
      args.put("typical_p", kw.get("typical_p"));
      args.put("stream", kw.get("stream"));
      args.put("stop", stop);
      args.put("max_tokens", kw.get("max_tokens"));
      args.put("presence_penalty", kw.get("presence_penalty"));
      args.put("frequency_penalty", kw.get("frequency_penalty"));
      args.put("repeat_penalty", kw.get("repeat_penalty"));
      args.put("tfs_z", kw.get("tfs_z"));
      args.put("mirostat_mode", kw.get("mirostat_mode"));
      args.put("mirostat_tau", kw.get("mirostat_tau"));
      args.put("mirostat_eta", kw.get("mirostat_eta"));
      args.put("model", kw.get("model"));
      args.put("logits_processor", kw.get("logits_processor"));
      args.put("grammar", grammarObj);
      return llama.createCompletion(args);
    }

    /** Outer body, no-tools/no-functions branch and non-stream dict branch. */
    @SuppressWarnings("unchecked")
    public Object handle(Map<String, Object> kw) {
      List<Object> messages = list(kw.get("messages"));
      List<Object> tools = (List<Object>) kw.get("tools");
      List<Object> functions = (List<Object>) kw.get("functions");
      Object toolChoice = kw.get("tool_choice");
      Object functionCall = kw.get("function_call");
      boolean stream = Boolean.TRUE.equals(kw.get("stream"));

      if (tools != null) {
        functions = new ArrayList<>();
        for (Object t : tools) {
          if ("function".equals(map(t).get("type")))
            ((List<Object>) functions).add(map(t).get("function"));
        }
      }
      if (toolChoice != null) {
        functionCall = toolChoice instanceof String ? toolChoice : map(toolChoice).get("function");
      } else if (functionCall == null) {
        functionCall = "auto";
      }

      String prompt =
          PythonChatFormatHandlers.prepareMessagesForInferenceV2(
              tokenizerPort, tokenizer, messages, version, functions, tools, functionCall);

      if ("none".equals(functionCall) || functions == null || functions.isEmpty()) {
        Object stop;
        String p = prompt;
        if ("v1".equals(version)) {
          stop = endAssistant;
        } else {
          stop = stopToken;
          p += "all\n<|content|>";
        }
        Object completion = createCompletion(p, stop, kw.get("grammar"), kw);
        if (!stream) {
          Map<String, Object> cmp = map(completion);
          Map<String, Object> first = map(list(cmp.get("choices")).get(0));
          first.put("text", ((String) first.get("text")).stripLeading());
        }
        return PythonChatCompletions.convertCompletionToChat(completion, stream);
      }

      if (stream) {
        return generateStreaming(tools, functions, functionCall, prompt, kw);
      }
      Object grammarObj =
          PythonChatFormatHandlers.functionaryGetGrammar(
              grammar, functionCall, functions, tools, llama.verbose());
      Object stops = functionCall instanceof Map ? List.of(stopToken, fromToken) : null;
      if (functionCall instanceof Map) {
        prompt += map(functionCall).get("name") + "\n" + contentToken;
      }
      Object completion =
          createCompletion(prompt, stops == null ? kw.get("stop") : stops, grammarObj, kw);
      return PythonChatCompletions.convertCompletionToChat(completion, stream);
    }

    /** generate_streaming materialized to a list of chunks. */
    public List<Object> generateStreaming(
        List<Object> tools,
        List<Object> functions,
        Object functionCall,
        String prompt,
        Map<String, Object> kw) {
      if (!"v2".equals(version)) {
        throw error(PythonErrorType.ASSERTION_ERROR, "Streaming for v1 is not supported");
      }
      List<Object> out = new ArrayList<>();
      String chunkId = null;
      Object chunkCreated = null;
      java.util.function.Supplier<String> toolId =
          kw.containsKey("tool_id_factory")
              ? (java.util.function.Supplier<String>) kw.get("tool_id_factory")
              : null;
      if (functionCall instanceof Map) {
        String name = (String) map(functionCall).get("name");
        prompt += name + "\n" + contentToken;
        Object grammarObj =
            PythonChatFormatHandlers.functionaryGetGrammar(
                grammar, name, functions, tools, llama.verbose());
        Object completion = createCompletion(prompt, List.of(stopToken, fromToken), grammarObj, kw);
        String id = toolId != null ? toolId.get() : "TOOLID";
        boolean first = true;
        Map<String, Object> lastChunk = null;
        for (Object chunkObj : list(completion)) {
          Map<String, Object> chunk = map(chunkObj);
          lastChunk = chunk;
          if (first) {
            Object funcCallDict = funcCallDict(tools, 0, id, name, "");
            Map<String, Object> d0 = new LinkedHashMap<>();
            d0.put("role", null);
            d0.put("content", null);
            out.add(chunk(chunk, d0, funcCallDict, null, null));
            first = false;
          }
          String text = ((String) map(list(chunk.get("choices")).get(0)).get("text"));
          String stripped = text == null ? null : text.strip();
          if (stripped != null && !stripped.isEmpty()) {
            Object d = funcCallDict(tools, 0, id, null, stripped);
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("role", null);
            db.put("content", null);
            out.add(
                chunk(
                    chunk,
                    db,
                    d,
                    PythonChatCompletions.convertTextCompletionLogprobsToChat(
                        map(list(chunk.get("choices")).get(0)).get("logprobs")),
                    null));
          }
        }
        out.add(
            chunk(
                lastChunk, deltaEnd(), null, null, tools != null ? "tool_calls" : "function_call"));
      } else if (functionCall instanceof String && "auto".equals(functionCall)) {
        int toolIndex = 0;
        while (true) {
          Object completion = createCompletion(prompt, contentToken, null, kw);
          StringBuilder text = new StringBuilder();
          Map<String, Object> lastChunk = null;
          for (Object chunkObj : list(completion)) {
            Map<String, Object> chunk = map(chunkObj);
            lastChunk = chunk;
            text.append(map(list(chunk.get("choices")).get(0)).get("text"));
          }
          if (chunkId == null) chunkId = (String) lastChunk.get("id");
          if (chunkCreated == null) chunkCreated = lastChunk.get("created");
          String functionName = text.toString().strip();
          if ("all".equals(functionName)) {
            prompt += "all\n<|content|>";
            out.add(contentChunk(lastChunk, "", null));
            completion = createCompletion(prompt, List.of(recipientToken, stopToken), null, kw);
            StringBuilder completionText = new StringBuilder();
            String stopSeq = "\n<|from|>assistant\n<|recipient|>";
            List<String> buffer = new ArrayList<>();
            boolean isEnd = false;
            int i = 0;
            for (Object chunkObj : list(completion)) {
              Map<String, Object> chunk = map(chunkObj);
              String chunkText = (String) map(list(chunk.get("choices")).get(0)).get("text");
              completionText.append(chunkText);
              if (isEnd) {
                buffer.add(chunkText.strip());
                if (stopSeq.startsWith(String.join("", buffer))) {
                  continue;
                } else {
                  buffer.remove(buffer.size() - 1);
                  while (!buffer.isEmpty()) {
                    out.add(
                        contentChunk(
                            chunk,
                            buffer.remove(0),
                            PythonChatCompletions.convertTextCompletionLogprobsToChat(
                                map(list(chunk.get("choices")).get(0)).get("logprobs"))));
                  }
                  isEnd = false;
                }
              } else if ("\n".equals(chunkText)) {
                isEnd = true;
                buffer.add(chunkText.strip());
                continue;
              }
              if (buffer.isEmpty() && chunkText.length() > 0) {
                out.add(
                    contentChunk(
                        chunk,
                        i > 0 ? chunkText : chunkText.stripLeading(),
                        PythonChatCompletions.convertTextCompletionLogprobsToChat(
                            map(list(chunk.get("choices")).get(0)).get("logprobs"))));
              }
              i++;
            }
            if (completionText.toString().contains("<|from|> assistant")
                || completionText.toString().contains("<|from|>assistant")) {
              String cleaned;
              if (completionText.toString().endsWith("\n<|from|>assistant\n")) {
                cleaned =
                    completionText
                        .substring(0, completionText.length() - "\n<|from|>assistant\n".length())
                        .strip();
              } else if (completionText.toString().endsWith("\n<|from|> assistant\n")) {
                cleaned =
                    completionText
                        .substring(0, completionText.length() - "\n<|from|> assistant\n".length())
                        .strip();
              } else {
                cleaned = completionText.toString().strip();
              }
              prompt += cleaned + "\n<|from|>assistant\n<|recipient|>";
            } else {
              out.add(stopChunk(lastChunk));
              break;
            }
          } else {
            prompt += functionName + "\n<|content|>";
            Object grammarObj =
                PythonChatFormatHandlers.functionaryGetGrammar(
                    grammar, functionName, functions, tools, llama.verbose());
            String id = toolId != null ? toolId.get() : "TOOLID";
            Map<String, Object> d1 = new LinkedHashMap<>();
            d1.put("role", null);
            d1.put("content", null);
            out.add(
                chunk(
                    lastChunk,
                    d1,
                    funcCallDict(tools, toolIndex, id, functionName, ""),
                    null,
                    null));
            completion =
                createCompletion(prompt, List.of(recipientToken, stopToken), grammarObj, kw);
            StringBuilder completionText = new StringBuilder();
            for (Object chunkObj : list(completion)) {
              Map<String, Object> chunk = map(chunkObj);
              lastChunk = chunk;
              String t = (String) map(list(chunk.get("choices")).get(0)).get("text");
              completionText.append(t);
              String stripped = t.strip();
              if (!stripped.isEmpty()) {
                Map<String, Object> d2 = new LinkedHashMap<>();
                d2.put("role", null);
                d2.put("content", null);
                out.add(
                    chunk(
                        chunk,
                        d2,
                        funcCallDict(tools, toolIndex, id, null, stripped),
                        PythonChatCompletions.convertTextCompletionLogprobsToChat(
                            map(list(chunk.get("choices")).get(0)).get("logprobs")),
                        null));
              }
            }
            if (completionText.toString().contains("<|from|> assistant")
                || completionText.toString().contains("<|from|>assistant")) {
              String cleaned;
              if (completionText.toString().endsWith("\n<|from|>assistant\n")) {
                cleaned =
                    completionText
                        .substring(0, completionText.length() - "\n<|from|>assistant\n".length())
                        .strip();
              } else if (completionText.toString().endsWith("\n<|from|> assistant\n")) {
                cleaned =
                    completionText
                        .substring(0, completionText.length() - "\n<|from|> assistant\n".length())
                        .strip();
              } else {
                cleaned = completionText.toString().strip();
              }
              prompt += cleaned + "\n<|from|>assistant\n<|recipient|>";
              out.add(
                  chunk(
                      lastChunk,
                      deltaEnd(),
                      null,
                      null,
                      tools != null ? "tool_calls" : "function_call"));
            } else {
              out.add(
                  chunk(
                      lastChunk,
                      deltaEnd(),
                      null,
                      null,
                      tools != null ? "tool_calls" : "function_call"));
              break;
            }
          }
          toolIndex++;
        }
      }
      return out;
    }

    private Map<String, Object> funcCallDict(
        List<Object> tools, int index, String id, String name, String arguments) {
      if (tools != null) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("arguments", arguments);
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("index", index);
        tc.put("id", "call_" + id);
        tc.put("type", "function");
        tc.put("function", fn);
        return Map.of("tool_calls", List.of(tc));
      }
      Map<String, Object> fn = new LinkedHashMap<>();
      fn.put("name", name);
      fn.put("arguments", arguments);
      return Map.of("function_call", fn);
    }

    private Map<String, Object> deltaEnd() {
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("role", null);
      d.put("content", null);
      d.put("function_call", null);
      d.put("tool_calls", null);
      return d;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chunk(
        Map<String, Object> src,
        Map<String, Object> deltaBase,
        Object funcCallDict,
        Object logprobs,
        String finish) {
      Map<String, Object> delta = new LinkedHashMap<>(deltaBase);
      if (funcCallDict instanceof Map) delta.putAll((Map<String, Object>) funcCallDict);
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("index", 0);
      if (finish != null) choice.put("finish_reason", finish);
      choice.put("logprobs", logprobs);
      choice.put("delta", delta);
      Map<String, Object> chunk = new LinkedHashMap<>();
      chunk.put("id", "chat" + src.get("id"));
      chunk.put("object", "chat.completion.chunk");
      chunk.put("created", src.get("created"));
      chunk.put("model", src.get("model"));
      chunk.put("choices", List.of(choice));
      return chunk;
    }

    private Map<String, Object> contentChunk(
        Map<String, Object> src, String content, Object logprobs) {
      Map<String, Object> delta = new LinkedHashMap<>();
      delta.put("role", "assistant");
      delta.put("content", content);
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("index", 0);
      choice.put("delta", delta);
      choice.put("logprobs", logprobs);
      choice.put("finish_reason", null);
      Map<String, Object> chunk = new LinkedHashMap<>();
      chunk.put("id", "chat" + src.get("id"));
      chunk.put("model", src.get("model"));
      chunk.put("created", src.get("created"));
      chunk.put("object", "chat.completion.chunk");
      chunk.put("choices", List.of(choice));
      return chunk;
    }

    private Map<String, Object> stopChunk(Map<String, Object> src) {
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("index", 0);
      choice.put("delta", Map.of());
      choice.put("logprobs", null);
      choice.put("finish_reason", "stop");
      Map<String, Object> chunk = new LinkedHashMap<>();
      chunk.put("id", "chat" + src.get("id"));
      chunk.put("model", src.get("model"));
      chunk.put("created", src.get("created"));
      chunk.put("object", "chat.completion.chunk");
      chunk.put("choices", List.of(choice));
      return chunk;
    }
  }

  // ---------------------------------------------------------------------------
  // Media handlers (Llava15ChatHandler / MTMDChatHandler)

  /** mtmd_cpp module surface used by the media handlers. */
  public interface MtmdPort {
    default Object contextParamsDefault() {
      return new LinkedHashMap<String, Object>();
    }

    void suppress(boolean disable);

    /** llama_cpp.LLAMA_FLASH_ATTN_TYPE_* constants: "ENABLED"/"DISABLED". */
    String flashAttnEnabled();

    String flashAttnDisabled();

    Object initFromFile(byte[] path, Object llamaModel, Object params);

    boolean supportVision(Object ctx);

    void free(Object ctx);

    Object defaultMarker(); // bytes

    /** print(text, file=sys.stderr) when verbose. */
    void printErr(Object value);

    Object bitmapInitFromBuf(Object ctx, byte[] data, int len, boolean addSpecial);

    Object inputText();

    Object chunksInit();

    int mtmdTokenize(Object ctx, Object chunks, Object inputText, Object bitmaps, int n);

    int chunksSize(Object chunks);

    Object chunksGet(Object chunks, int i);

    int chunkType(Object chunk);

    int textChunkType();

    int imageChunkType();

    int audioChunkType();

    List<Object> chunkTokensText(Object chunk);

    int chunkNTokens(Object chunk);

    int evalChunkSingle(
        Object ctx,
        Object llamaCtx,
        Object chunk,
        int nPast,
        int seqId,
        int nBatch,
        boolean logitsLast,
        Object nPastOut);

    void chunksFree(Object chunks);

    void bitmapFree(Object bitmap);
  }

  /** llama surface for the media handlers (broader than LlamaPort). */
  public interface MediaLlama extends LlamaPort {
    Map<String, Object> metadata();

    int nThreads();

    Object contextParamsFlashAttn();

    Object model();

    Object ctx();

    void reset();

    void kvCacheClear();

    int nTokens();

    void setNTokens(int n);

    int nCtx();

    int nBatch();

    void eval(List<Object> tokens);

    Object inputIdsSlice(int n);

    void stackCallback(Runnable callback);

    boolean pathExists(String path);
  }

  /** huggingface_hub surface for from_pretrained. */
  public interface HfHub {
    void validateRepoId(String repoId) throws RuntimeException;

    List<Object> ls(String repoId);

    String download(Map<String, Object> kwargs);

    default String fnmatch(String name, String pattern) {
      String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
      return name.matches(regex) ? name : null;
    }
  }

  /** Shared init for Llava15/MTMD __init__ bodies. */
  public static void mediaInit(
      MediaHandler handler,
      String clipModelPath,
      boolean verbose,
      Boolean useGpu,
      boolean pathExists) {
    handler.clipModelPath = clipModelPath;
    handler.verbose = verbose;
    handler.useGpu = useGpu;
    handler.mtmdCtx = null;
    if (!pathExists) {
      throw error(PythonErrorType.VALUE_ERROR, "Clip model path does not exist: " + clipModelPath);
    }
  }

  public static final class MediaHandler {
    public String clipModelPath;
    public boolean verbose;
    public Boolean useGpu;
    public Object mtmdCtx;
  }

  /** _init_mtmd_context + nested mtmd_free (useGpu is null for Llava15 => use_gpu=True). */
  public static void initMtmdContext(
      MtmdPort mtmd, MediaLlama llama, MediaHandler handler, boolean llavaStyle) {
    if (!llavaStyle) {
      handler.verbose = llama.verbose();
    }
    if (handler.mtmdCtx != null) {
      return;
    }
    mtmd.suppress(handler.verbose);
    mtmd.contextParamsDefault();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("use_gpu", llavaStyle ? true : handler.useGpu);
    params.put("print_timings", handler.verbose);
    params.put("n_threads", llama.nThreads());
    params.put(
        "flash_attn_type",
        Objects.equals(llama.contextParamsFlashAttn(), mtmd.flashAttnEnabled())
            ? mtmd.flashAttnEnabled()
            : mtmd.flashAttnDisabled());
    handler.mtmdCtx =
        mtmd.initFromFile(
            handler.clipModelPath.getBytes(StandardCharsets.UTF_8), llama.model(), params);
    if (handler.mtmdCtx == null) {
      throw error(
          PythonErrorType.VALUE_ERROR,
          "Failed to load mtmd context from: " + handler.clipModelPath);
    }
    if (!mtmd.supportVision(handler.mtmdCtx)) {
      throw error(PythonErrorType.VALUE_ERROR, "Vision is not supported by this model");
    }
    llama.stackCallback(
        () -> {
          mtmd.suppress(handler.verbose);
          if (handler.mtmdCtx != null) {
            mtmd.free(handler.mtmdCtx);
            handler.mtmdCtx = null;
          }
        });
  }

  public static byte[] loadImage(String imageUrl) {
    return loadImageStatic(imageUrl);
  }

  /** _load_image: data: base64 / urllib fallback (the URL fetch is a port-free fake in tests). */
  public static byte[] loadImageStatic(String imageUrl) {
    if (imageUrl.startsWith("data:")) {
      return java.util.Base64.getDecoder().decode(imageUrl.split(",")[1]);
    }
    throw error(PythonErrorType.VALUE_ERROR, "urlopen unavailable in stub: " + imageUrl);
  }

  public static Object createBitmapFromBytes(
      MtmdPort mtmd, MediaHandler handler, byte[] imageBytes) {
    if (handler.mtmdCtx == null) {
      throw error(PythonErrorType.VALUE_ERROR, "mtmd context not initialized");
    }
    mtmd.suppress(handler.verbose);
    Object bitmap = mtmd.bitmapInitFromBuf(handler.mtmdCtx, imageBytes, imageBytes.length, false);
    if (bitmap == null) {
      throw error(PythonErrorType.VALUE_ERROR, "Failed to create bitmap from image bytes");
    }
    return bitmap;
  }

  public static String getChatTemplate(MediaLlama llama, String className) {
    Object template = llama.metadata().get("tokenizer.chat_template");
    if (!(template instanceof String) || ((String) template).isEmpty()) {
      throw error(
          PythonErrorType.VALUE_ERROR, className + " requires tokenizer.chat_template metadata");
    }
    return (String) template;
  }

  /** from_pretrained shared body (huggingface_hub port). */
  public static Map<String, Object> fromPretrained(
      HfHub hub,
      String repoId,
      String filename,
      Object localDir,
      Object localDirUseSymlinks,
      Object cacheDir,
      Map<String, Object> kwargs) {
    hub.validateRepoId(repoId);
    List<Object> files = hub.ls(repoId);
    List<String> fileList = new ArrayList<>();
    for (Object fileObj : files) {
      String file = fileObj instanceof Map ? (String) map(fileObj).get("name") : (String) fileObj;
      String rel = file.startsWith(repoId + "/") ? file.substring(repoId.length() + 1) : file;
      fileList.add(rel);
    }
    List<String> matching = new ArrayList<>();
    for (String file : fileList) {
      if (hub.fnmatch(file, filename) != null) matching.add(file);
    }
    if (matching.isEmpty()) {
      throw error(
          PythonErrorType.VALUE_ERROR,
          "No file found in "
              + repoId
              + " that match "
              + filename
              + "\n\nAvailable Files:\n"
              + PythonJson.dumps(fileList));
    }
    if (matching.size() > 1) {
      throw error(
          PythonErrorType.VALUE_ERROR,
          "Multiple files found in "
              + repoId
              + " matching "
              + filename
              + "\n\nAvailable Files:\n"
              + PythonJson.dumps(files));
    }
    String matchingFile = matching.get(0);
    String subfolder;
    String name;
    int slash = matchingFile.lastIndexOf('/');
    if (slash >= 0) {
      subfolder = matchingFile.substring(0, slash);
      name = matchingFile.substring(slash + 1);
    } else {
      subfolder = ".";
      name = matchingFile;
    }
    Map<String, Object> dl = new LinkedHashMap<>();
    dl.put("repo_id", repoId);
    dl.put("filename", name);
    dl.put("subfolder", subfolder);
    dl.put("local_dir", localDir);
    dl.put("local_dir_use_symlinks", localDirUseSymlinks);
    dl.put("cache_dir", cacheDir);
    hub.download(dl);
    String modelPath;
    if (localDir == null) {
      Map<String, Object> dl2 = new LinkedHashMap<>(dl);
      dl2.put("local_files_only", true);
      modelPath = hub.download(dl2);
    } else {
      modelPath = localDir + "/" + name;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("clip_model_path", modelPath);
    out.putAll(kwargs);
    return out;
  }

  // ---------------------------------------------------------------------------
  // Llava15/MTMD __call__ bodies

  /**
   * Shared media __call__ pipeline. mode: "llava" uses CHAT_FORMAT render+marker replace; "mtmd"
   * uses metadata template + _get_template_messages + postprocess.
   */
  public static Object mediaCall(
      MtmdPort mtmd,
      PythonChatFormatHandlers.JinjaPort jinja,
      MediaLlama llama,
      MediaHandler handler,
      String mode,
      String chatFormat,
      String defaultSystemMessage,
      Map<String, Object> kw) {
    initMtmdContext(mtmd, llama, handler, "llava".equals(mode));
    List<Object> messages = list(kw.get("messages"));
    List<Object> tools = (List<Object>) kw.get("tools");
    Object toolChoice = kw.get("tool_choice");
    Object functionCall = kw.get("function_call");
    Object functions = kw.get("functions");
    Object responseFormat = kw.get("response_format");
    boolean stream = Boolean.TRUE.equals(kw.get("stream"));
    Object stop = kw.get("stop");
    Object grammar = kw.get("grammar");

    String systemPrompt = systemMessageOf(messages);
    if (systemPrompt.isEmpty() && defaultSystemMessage != null) {
      List<Object> prefixed = new ArrayList<>();
      Map<String, Object> sys = new LinkedHashMap<>();
      sys.put("role", "system");
      sys.put("content", defaultSystemMessage);
      prefixed.add(sys);
      prefixed.addAll(messages);
      messages = prefixed;
    }

    List<String> imageUrls = PythonChatFormatHandlers.getImageUrls(messages);
    String mediaMarker = new String((byte[]) mtmd.defaultMarker(), StandardCharsets.UTF_8);

    String text;
    if ("llava".equals(mode)) {
      jinja.envInit(List.of("lstrip_blocks", "trim_blocks"), LLAVA_CHAT_FORMAT);
      Map<String, Object> ctx = new LinkedHashMap<>();
      ctx.put("messages", messages);
      ctx.put("add_generation_prompt", true);
      ctx.put("eos_token", llama.detokenize(List.of(llama.tokenEos())));
      ctx.put("bos_token", llama.detokenize(List.of(llama.tokenBos())));
      text = jinja.render(ctx);
      for (String url : imageUrls) text = text.replace(url, mediaMarker);
    } else {
      String template = getChatTemplate(llama, "MTMDChatHandler");
      jinja.envInit(List.of("extensions", "lstrip_blocks", "trim_blocks"), template);
      List<Object> templateMessages =
          PythonChatFormatHandlers.getTemplateMessages(messages, mediaMarker);
      Map<String, Object> ctx = new LinkedHashMap<>();
      ctx.put("messages", templateMessages);
      ctx.put("add_generation_prompt", true);
      ctx.put("eos_token", decode(llama.detokenize(List.of(llama.tokenEos()))));
      ctx.put("bos_token", decode(llama.detokenize(List.of(llama.tokenBos()))));
      ctx.put("raise_exception", "raise_exception");
      ctx.put("functions", functions);
      ctx.put("function_call", functionCall);
      ctx.put("tools", tools);
      ctx.put("tool_choice", toolChoice);
      ctx.put("strftime_now", "strftime_now");
      text = jinja.render(ctx);
      text = PythonChatFormatHandlers.postprocessTemplateText(text, imageUrls, mediaMarker);
    }
    if (handler.verbose) {
      mtmd.printErr(text);
    }

    List<Object> bitmaps = new ArrayList<>();
    List<Object> bitmapCleanup = new ArrayList<>();
    try {
      for (String url : imageUrls) {
        byte[] bytes = loadImage(url);
        Object bitmap = createBitmapFromBytes(mtmd, handler, bytes);
        bitmaps.add(bitmap);
        bitmapCleanup.add(bitmap);
      }
      byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
      Object inputText = mtmd.inputText();
      Object chunks = mtmd.chunksInit();
      if (chunks == null) {
        throw error(PythonErrorType.VALUE_ERROR, "Failed to create input chunks");
      }
      try {
        int result = mtmd.mtmdTokenize(handler.mtmdCtx, chunks, inputText, bitmaps, bitmaps.size());
        if (result != 0) {
          throw error(
              PythonErrorType.VALUE_ERROR, "Failed to tokenize input: error code " + result);
        }
        llama.reset();
        llama.kvCacheClear();
        int nChunks = mtmd.chunksSize(chunks);
        for (int i = 0; i < nChunks; i++) {
          Object chunk = mtmd.chunksGet(chunks, i);
          if (chunk == null) continue;
          int chunkType = mtmd.chunkType(chunk);
          if (chunkType == mtmd.textChunkType()) {
            List<Object> tokens = mtmd.chunkTokensText(chunk);
            if (tokens != null && !tokens.isEmpty()) {
              if (llama.nTokens() + tokens.size() > llama.nCtx()) {
                throw error(
                    PythonErrorType.VALUE_ERROR,
                    "Prompt exceeds n_ctx: "
                        + (llama.nTokens() + tokens.size())
                        + " > "
                        + llama.nCtx());
              }
              llama.eval(tokens);
            }
          } else if (chunkType == mtmd.imageChunkType() || chunkType == mtmd.audioChunkType()) {
            int chunkNTokens = mtmd.chunkNTokens(chunk);
            if (llama.nTokens() + chunkNTokens > llama.nCtx()) {
              throw error(
                  PythonErrorType.VALUE_ERROR,
                  "Prompt exceeds n_ctx: "
                      + (llama.nTokens() + chunkNTokens)
                      + " > "
                      + llama.nCtx());
            }
            Object nPastOut = new Object();
            int r =
                mtmd.evalChunkSingle(
                    handler.mtmdCtx,
                    llama.ctx(),
                    chunk,
                    llama.nTokens(),
                    0,
                    llama.nBatch(),
                    false,
                    nPastOut);
            if (r != 0) {
              throw error(PythonErrorType.VALUE_ERROR, "Failed to evaluate chunk: error code " + r);
            }
            llama.setNTokens(((int[]) nPastOut)[0]);
          }
        }
        Object prompt = llama.inputIdsSlice(llama.nTokens());
        kw.put("_prompt", prompt);
      } finally {
        mtmd.chunksFree(chunks);
      }
    } finally {
      for (Object bitmap : bitmapCleanup) {
        mtmd.bitmapFree(bitmap);
      }
    }
    return finishMediaCall(
        grammar,
        mtmd,
        llama,
        kw,
        tools,
        toolChoice,
        functionCall,
        functions,
        responseFormat,
        stream,
        stop);
  }

  private static Object decode(Object v) {
    return v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v;
  }

  private static String systemMessageOf(List<Object> messages) {
    for (Object m : messages) {
      if ("system".equals(map(m).get("role"))) {
        Object c = map(m).get("content");
        return c == null ? "" : c.toString();
      }
    }
    return "";
  }

  @SuppressWarnings("unchecked")
  private static Object finishMediaCall(
      Object grammar,
      MtmdPort mtmd,
      LlamaPort llama,
      Map<String, Object> kw,
      List<Object> tools,
      Object toolChoice,
      Object functionCall,
      Object functions,
      Object responseFormat,
      boolean stream,
      Object stop) {
    if (responseFormat instanceof Map && "json_object".equals(map(responseFormat).get("type"))) {
      grammar = kw.get("_grammar_from_response_format");
    }
    if (functions != null) {
      List<Object> ts = new ArrayList<>();
      for (Object f : list(functions)) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", f);
        ts.add(t);
      }
      tools = ts;
    }
    if (functionCall != null) {
      if (functionCall instanceof String
          && ("none".equals(functionCall) || "auto".equals(functionCall))) {
        toolChoice = functionCall;
      }
      if (functionCall instanceof Map && map(functionCall).containsKey("name")) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", map(functionCall).get("name"));
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("type", "function");
        tc.put("function", fn);
        toolChoice = tc;
      }
    }
    Object tool = null;
    if (toolChoice instanceof Map && tools != null) {
      Object name =
          map(toolChoice).containsKey("function")
              ? map(map(toolChoice).get("function")).get("name")
              : null;
      for (Object t : tools) {
        if (name != null && name.equals(map(map(t).get("function")).get("name"))) {
          tool = t;
          break;
        }
      }
      if (tool == null && name != null) {
        throw error(PythonErrorType.VALUE_ERROR, "Tool choice '" + name + "' not found in tools.");
      }
      if (tool != null) {
        Object schema = map(map(tool).get("function")).get("parameters");
        try {
          grammar = kw.get("_grammar_schema");
        } catch (RuntimeException e) {
          grammar = kw.get("_grammar_default");
        }
      }
    }
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("prompt", kw.get("_prompt"));
    args.put("temperature", kw.get("temperature"));
    args.put("top_p", kw.get("top_p"));
    args.put("top_k", kw.get("top_k"));
    args.put("min_p", kw.get("min_p"));
    args.put("typical_p", kw.get("typical_p"));
    args.put("logprobs", Boolean.TRUE.equals(kw.get("logprobs")) ? kw.get("top_logprobs") : null);
    args.put("stream", stream);
    args.put("stop", stop);
    args.put("seed", kw.get("seed"));
    args.put("max_tokens", kw.get("max_tokens"));
    args.put("presence_penalty", kw.get("presence_penalty"));
    args.put("frequency_penalty", kw.get("frequency_penalty"));
    args.put("repeat_penalty", kw.get("repeat_penalty"));
    args.put("tfs_z", kw.get("tfs_z"));
    args.put("mirostat_mode", kw.get("mirostat_mode"));
    args.put("mirostat_tau", kw.get("mirostat_tau"));
    args.put("mirostat_eta", kw.get("mirostat_eta"));
    args.put("model", kw.get("model"));
    args.put("logits_processor", kw.get("logits_processor"));
    args.put("grammar", grammar);
    args.put("logit_bias", kw.get("logit_bias"));
    Object completion = llama.createCompletion(args);
    if (tool != null) {
      return PythonChatFormatHandlers.convertCompletionToChatFunction(
          (String) map(map(tool).get("function")).get("name"), completion, stream);
    }
    return PythonChatCompletions.convertCompletionToChat(completion, stream);
  }

  // ---------------------------------------------------------------------------
  // chatml_function_calling

  public static Object chatmlFunctionCalling(
      PythonChatFormatHandlers.JinjaPort jinja,
      PythonChatFormatHandlers.GrammarPort grammar,
      LlamaPort llama,
      String template,
      Map<String, Object> kw) {
    List<Object> messages = list(kw.get("messages"));
    Object functions = kw.get("functions");
    Object functionCall = kw.get("function_call");
    List<Object> tools = (List<Object>) kw.get("tools");
    Object toolChoice = kw.get("tool_choice");
    boolean stream = Boolean.TRUE.equals(kw.get("stream"));
    Object responseFormat = kw.get("response_format");
    Object stop = kw.get("stop");

    jinja.envFromString(CHATML_TEMPLATE);

    if (functions != null) {
      List<Object> ts = new ArrayList<>();
      for (Object f : list(functions)) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "function");
        t.put("function", f);
        ts.add(t);
      }
      tools = ts;
    }
    if (functionCall != null) {
      if (functionCall instanceof String
          && ("none".equals(functionCall) || "auto".equals(functionCall))) {
        toolChoice = functionCall;
      }
      if (functionCall instanceof Map && map(functionCall).containsKey("name")) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", map(functionCall).get("name"));
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("type", "function");
        tc.put("function", fn);
        toolChoice = tc;
      }
    }
    Object stopFinal =
        stop instanceof String
            ? List.of(stop, "<|im_end|>")
            : (stop instanceof List && !list(stop).isEmpty()
                ? concat(list(stop), "<|im_end|>")
                : List.of("<|im_end|>"));

    // Case 1
    if (toolChoice == null
        || (toolChoice instanceof String && "none".equals(toolChoice))
        || tools == null
        || tools.isEmpty()) {
      Map<String, Object> ctx = new LinkedHashMap<>();
      ctx.put("messages", messages);
      ctx.put("tools", List.of());
      ctx.put("tool_calls", null);
      ctx.put("add_generation_prompt", true);
      String prompt = jinja.render(ctx);
      Object grammarObj = kw.get("grammar");
      if (responseFormat instanceof Map && "json_object".equals(map(responseFormat).get("type"))) {
        grammarObj = kw.get("_grammar_from_response_format");
      }
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("prompt", prompt);
      args.put("temperature", kw.get("temperature"));
      args.put("top_p", kw.get("top_p"));
      args.put("top_k", kw.get("top_k"));
      args.put("min_p", kw.get("min_p"));
      args.put("typical_p", kw.get("typical_p"));
      args.put("stream", stream);
      args.put("stop", stopFinal);
      args.put("max_tokens", kw.get("max_tokens"));
      args.put("presence_penalty", kw.get("presence_penalty"));
      args.put("frequency_penalty", kw.get("frequency_penalty"));
      args.put("repeat_penalty", kw.get("repeat_penalty"));
      args.put("tfs_z", kw.get("tfs_z"));
      args.put("mirostat_mode", kw.get("mirostat_mode"));
      args.put("mirostat_tau", kw.get("mirostat_tau"));
      args.put("mirostat_eta", kw.get("mirostat_eta"));
      args.put("model", kw.get("model"));
      args.put("logits_processor", kw.get("logits_processor"));
      args.put("grammar", grammarObj);
      args.put("logprobs", Boolean.TRUE.equals(kw.get("logprobs")) ? kw.get("top_logprobs") : null);
      return PythonChatCompletions.convertCompletionToChat(llama.createCompletion(args), stream);
    }

    // Case 2
    if (toolChoice instanceof Map) {
      Object toolName = map(map(toolChoice).get("function")).get("name");
      Object tool = null;
      for (Object t : tools) {
        if (toolName.equals(map(map(t).get("function")).get("name"))) {
          tool = t;
          break;
        }
      }
      if (tool == null) {
        throw error(
            PythonErrorType.VALUE_ERROR, "Tool with name '" + toolName + "' not found in tools");
      }
      Map<String, Object> ctx = new LinkedHashMap<>();
      ctx.put("messages", messages);
      ctx.put("tools", tools);
      ctx.put("tool_calls", true);
      ctx.put("add_generation_prompt", true);
      String prompt = jinja.render(ctx) + "functions." + toolName + ":\n";
      Object grammarObj;
      try {
        Object schema = map(map(tool).get("function")).get("parameters");
        grammarObj = grammar.fromJsonSchema(PythonJson.dumps(schema), llama.verbose());
      } catch (RuntimeException e) {
        grammarObj = grammar.fromString(grammar.jsonGbnf(), llama.verbose());
      }
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("prompt", prompt);
      args.put("temperature", kw.get("temperature"));
      args.put("top_p", kw.get("top_p"));
      args.put("top_k", kw.get("top_k"));
      args.put("min_p", kw.get("min_p"));
      args.put("typical_p", kw.get("typical_p"));
      args.put("stream", stream);
      args.put("stop", stopFinal);
      args.put("max_tokens", kw.get("max_tokens"));
      args.put("presence_penalty", kw.get("presence_penalty"));
      args.put("frequency_penalty", kw.get("frequency_penalty"));
      args.put("repeat_penalty", kw.get("repeat_penalty"));
      args.put("tfs_z", kw.get("tfs_z"));
      args.put("mirostat_mode", kw.get("mirostat_mode"));
      args.put("mirostat_tau", kw.get("mirostat_tau"));
      args.put("mirostat_eta", kw.get("mirostat_eta"));
      args.put("model", kw.get("model"));
      args.put("logits_processor", kw.get("logits_processor"));
      args.put("grammar", grammarObj);
      return PythonChatFormatHandlers.convertCompletionToChatFunction(
          (String) toolName, llama.createCompletion(args), stream);
    }

    // Case 3
    if (!(toolChoice instanceof String) || !"auto".equals(toolChoice)) {
      throw error(PythonErrorType.ASSERTION_ERROR, "");
    }
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < tools.size(); i++) {
      if (i > 0) names.append(" | ");
      names
          .append("\"functions.")
          .append(map(map(tools.get(i)).get("function")).get("name"))
          .append(":\"");
    }
    String initialGbnf = "root   ::= functions | \"message:\"\nfunctions ::= " + names + "\n";
    String followGbnf = "root   ::= functions | \"<|im_end|>\"\nfunctions ::= " + names + "\n";
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("messages", messages);
    ctx.put("tools", tools);
    ctx.put("tool_calls", true);
    ctx.put("add_generation_prompt", true);
    String prompt = jinja.render(ctx);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("prompt", prompt);
    args.put("temperature", 0);
    args.put("top_p", kw.get("top_p"));
    args.put("top_k", kw.get("top_k"));
    args.put("min_p", kw.get("min_p"));
    args.put("typical_p", kw.get("typical_p"));
    args.put("stream", false);
    args.put("stop", List.of(":"));
    args.put("max_tokens", null);
    args.put("presence_penalty", kw.get("presence_penalty"));
    args.put("frequency_penalty", kw.get("frequency_penalty"));
    args.put("repeat_penalty", kw.get("repeat_penalty"));
    args.put("tfs_z", kw.get("tfs_z"));
    args.put("mirostat_mode", kw.get("mirostat_mode"));
    args.put("mirostat_tau", kw.get("mirostat_tau"));
    args.put("mirostat_eta", kw.get("mirostat_eta"));
    args.put("model", kw.get("model"));
    args.put("logits_processor", kw.get("logits_processor"));
    args.put("grammar", grammar.fromString(initialGbnf, llama.verbose()));
    Object completion = llama.createCompletion(args);
    String text = (String) map(list(map(completion).get("choices")).get(0)).get("text");
    if (text.contains("message")) {
      Map<String, Object> args2 = new LinkedHashMap<>();
      args2.put("prompt", prompt + "message:\n");
      args2.put("temperature", kw.get("temperature"));
      args2.put("top_p", kw.get("top_p"));
      args2.put("top_k", kw.get("top_k"));
      args2.put("min_p", kw.get("min_p"));
      args2.put("typical_p", kw.get("typical_p"));
      args2.put("stream", stream);
      args2.put("stop", List.of("<|im_end|>"));
      args2.put(
          "logprobs", Boolean.TRUE.equals(kw.get("logprobs")) ? kw.get("top_logprobs") : null);
      args2.put("max_tokens", null);
      args2.put("presence_penalty", kw.get("presence_penalty"));
      args2.put("frequency_penalty", kw.get("frequency_penalty"));
      args2.put("repeat_penalty", kw.get("repeat_penalty"));
      args2.put("tfs_z", kw.get("tfs_z"));
      args2.put("mirostat_mode", kw.get("mirostat_mode"));
      args2.put("mirostat_tau", kw.get("mirostat_tau"));
      args2.put("mirostat_eta", kw.get("mirostat_eta"));
      args2.put("model", kw.get("model"));
      args2.put("logits_processor", kw.get("logits_processor"));
      args2.put("grammar", grammar.fromString(followGbnf, llama.verbose()));
      return PythonChatCompletions.convertCompletionToChat(llama.createCompletion(args2), stream);
    }
    String toolName =
        text.length() >= "functions.".length() ? text.substring("functions.".length()) : "";
    Object tool = null;
    for (Object t : tools) {
      if (toolName.equals(map(map(t).get("function")).get("name"))) {
        tool = t;
        break;
      }
    }
    if (!stream) {
      List<Object> completions = new ArrayList<>();
      List<Object> completionsToolName = new ArrayList<>();
      while (tool != null) {
        prompt += "functions." + toolName + ":\n";
        Object grammarObj;
        try {
          Object schema = map(map(tool).get("function")).get("parameters");
          grammarObj = grammar.fromJsonSchema(PythonJson.dumps(schema), llama.verbose());
        } catch (RuntimeException e) {
          grammarObj = grammar.fromString(grammar.jsonGbnf(), llama.verbose());
        }
        Map<String, Object> args3 = new LinkedHashMap<>();
        args3.put("prompt", prompt);
        args3.put("temperature", kw.get("temperature"));
        args3.put("top_p", kw.get("top_p"));
        args3.put("top_k", kw.get("top_k"));
        args3.put("min_p", kw.get("min_p"));
        args3.put("typical_p", kw.get("typical_p"));
        args3.put("stream", false);
        args3.put("stop", stopFinal);
        args3.put("max_tokens", null);
        args3.put("presence_penalty", kw.get("presence_penalty"));
        args3.put("frequency_penalty", kw.get("frequency_penalty"));
        args3.put("repeat_penalty", kw.get("repeat_penalty"));
        args3.put("tfs_z", kw.get("tfs_z"));
        args3.put("mirostat_mode", kw.get("mirostat_mode"));
        args3.put("mirostat_tau", kw.get("mirostat_tau"));
        args3.put("mirostat_eta", kw.get("mirostat_eta"));
        args3.put("model", kw.get("model"));
        args3.put("logits_processor", kw.get("logits_processor"));
        args3.put("grammar", grammarObj);
        Object c = llama.createCompletion(args3);
        completions.add(c);
        completionsToolName.add(toolName);
        prompt += map(list(map(c).get("choices")).get(0)).get("text");
        prompt += "\n";
        Map<String, Object> args4 = new LinkedHashMap<>(args3);
        args4.put("prompt", prompt);
        args4.put("grammar", grammar.fromString(followGbnf, llama.verbose()));
        Object response = llama.createCompletion(args4);
        toolName =
            ((String) map(list(map(response).get("choices")).get(0)).get("text"))
                .substring("functions.".length());
        tool = null;
        for (Object t : tools) {
          if (toolName.equals(map(map(t).get("function")).get("name"))) {
            tool = t;
            break;
          }
        }
      }
      Map<String, Object> functionCallDict = new LinkedHashMap<>();
      if (completions.size() == 1) {
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("name", toolName);
        fc.put("arguments", map(list(map(completions.get(0)).get("choices")).get(0)).get("text"));
        functionCallDict.put("function_call", fc);
      }
      List<Object> toolCalls = new ArrayList<>();
      for (int i = 0; i < completions.size(); i++) {
        Map<String, Object> c = map(completions.get(i));
        String tn = (String) completionsToolName.get(i);
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", tn);
        fn.put("arguments", map(list(c.get("choices")).get(0)).get("text"));
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("id", "call_" + "_" + i + "_" + tn + "_" + c.get("id"));
        tc.put("type", "function");
        tc.put("function", fn);
        toolCalls.add(tc);
      }
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("role", "assistant");
      message.put("content", null);
      message.put("tool_calls", toolCalls);
      message.putAll(functionCallDict);
      Map<String, Object> first = map(list(map(completion).get("choices")).get(0));
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("finish_reason", "tool_calls");
      choice.put("index", 0);
      choice.put(
          "logprobs",
          PythonChatCompletions.convertTextCompletionLogprobsToChat(first.get("logprobs")));
      choice.put("message", message);
      Map<String, Object> usage = new LinkedHashMap<>();
      usage.put("completion_tokens", sumUsage(completions, "completion_tokens"));
      usage.put("prompt_tokens", sumUsage(completions, "prompt_tokens"));
      usage.put("total_tokens", sumUsage(completions, "total_tokens"));
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", "chat" + map(completion).get("id"));
      result.put("object", "chat.completion");
      result.put("created", map(completion).get("created"));
      result.put("model", map(completion).get("model"));
      result.put("choices", List.of(choice));
      result.put("usage", usage);
      return result;
    }
    throw error(PythonErrorType.VALUE_ERROR, "Automatic streaming tool choice is not supported");
  }

  private static List<Object> concat(List<Object> items, Object extra) {
    List<Object> out = new ArrayList<>(items);
    out.add(extra);
    return out;
  }

  private static long sumUsage(List<Object> completions, String key) {
    long total = 0;
    for (Object c : completions) {
      Object usage = map(c).get("usage");
      if (usage instanceof Map) {
        Object v = map(usage).get(key);
        if (v instanceof Number) total += ((Number) v).longValue();
      }
    }
    return total;
  }
}
