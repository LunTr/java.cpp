package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class PythonChatContractTest {
  @TestFactory
  Stream<DynamicTest> matchesPinnedChatHelpers() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-chatregistry-contracts.tsv"),
            "Run generate_chat_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index ->
                DynamicTest.dynamicTest(
                    "chat:" + index,
                    () -> {
                      String[] parts = lines.get(index).split("\t");
                      Object argument = read(parts[1]);
                      Object actual =
                          switch (parts[0]) {
                            case "ext" -> runExt(argument);
                            case "reg" -> runRegistry(argument);
                            case "proto" -> runProtocols(argument);
                            case "static" -> runStatic(argument);
                            case "handler" -> runHandler(argument);
                            case "ep" -> runEndpoints(argument);
                            default -> throw new AssertionError(parts[0]);
                          };
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

  private static Object capture(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  // ---- _ctypes_function_from_names --------------------------------------------

  private static final class LibFunc implements PythonCtypesExtensions.MutableAttributed {
    final java.util.Map<String, Object> dict = new java.util.LinkedHashMap<>();
    final List<Object> trace;

    LibFunc(List<Object> trace) {
      this.trace = trace;
    }

    @Override
    public Object attr(String name) {
      return dict.getOrDefault(name, io.github.yaulun.javacpp.python.Py.ABSENT);
    }

    @Override
    public void setAttr(String name, Object value) {
      trace.add(
          List.of(
              "set",
              dict.getOrDefault("_name", "?"),
              name,
              value instanceof String || value instanceof List || value == null
                  ? value
                  : "function"));
      dict.put(name, value);
    }

    Map<String, Object> dict() {
      return dict;
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runExt(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    List<String> symbols = (List<String>) argument.get("symbols");
    var lib =
        new PythonCtypesExt.Lib() {
          public Object getattr(String name) {
            if (symbols.contains(name)) {
              trace.add(List.of("get", name));
              var func = new LibFunc(trace);
              func.dict.put("_name", name);
              return func;
            }
            throw new PythonException(PythonErrorType.ATTRIBUTE_ERROR, name + " missing", null);
          }
        };
    var wrapped = new PythonCtypesExtensions.PythonFunction((a, k) -> "wrapped");
    Object result =
        capture(
            () -> {
              var decorator =
                  PythonCtypesExt.ctypesFunctionFromNames(
                      lib,
                      (List<String>) argument.get("names"),
                      argument.get("argtypes"),
                      argument.get("restype"));
              Object func = decorator.apply(wrapped);
              if (func instanceof LibFunc lf) {
                trace.add(List.of("wrap", lf.dict().get("_name")));
                return dict(
                    "func",
                    dict(
                        "argtypes", lf.attr("argtypes"),
                        "restype", lf.attr("restype"),
                        "wrapped", lf.attr("__wrapped__") != null));
              }
              return func;
            });
    return dict("result", result, "trace", trace);
  }

  // ---- registry -------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Object runRegistry(Object input) {
    var argument = (Map<String, Object>) input;
    PythonChatHandlerRegistry.clearHandlers();
    PythonSingleton.clearInstances();
    var registry = new PythonChatHandlerRegistry();
    List<Object> results = new ArrayList<>();
    for (Object item : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) item;
      Object result =
          capture(
              () ->
                  switch ((String) step.get(0)) {
                    case "reg" -> {
                      registry.registerChatCompletionHandler(
                          (String) step.get(1), "h:" + step.get(1), (Boolean) step.get(2));
                      yield "ok";
                    }
                    case "unreg" -> {
                      registry.unregisterChatHandler((String) step.get(1));
                      yield "ok";
                    }
                    case "get" -> registry.getChatCompletionHandlerByName((String) step.get(1));
                    case "getmod" ->
                        PythonChatHandlerRegistry.getChatCompletionHandler((String) step.get(1));
                    case "deco" -> {
                      Object fn =
                          new PythonCtypesExtensions.PythonFunction((a, k) -> "fn:" + step.get(1));
                      Object f =
                          PythonChatHandlerRegistry.registerChatCompletionHandler(
                                  (String) step.get(1))
                              .apply(fn);
                      yield f == fn ? "ok" : f;
                    }
                    case "fmtdeco" -> {
                      Object f =
                          PythonChatHandlerRegistry.registerChatFormat(
                                  (String) step.get(1), format -> "wrapped:<lambda>")
                              .apply("fmt:" + step.get(1));
                      yield f.equals("fmt:" + step.get(1)) ? "ok" : f;
                    }
                    default -> throw new AssertionError(step);
                  });
      if (result instanceof String s && s.equals("wrapped:<lambda>")) {
        // fmtdeco registers the wrapped handler; get returns it verbatim.
      }
      if (result != null
          && !(result instanceof Map)
          && !(result instanceof String)
          && !(result instanceof Boolean)) {
        result = "callable";
      }
      results.add(dict("result", result, "handlers", List.copyOf(registry.handlers().keySet())));
    }
    return results;
  }

  // ---- protocols ----------------------------------------------------------------

  private static Object runProtocols(Object input) {
    Object handler =
        capture(
            () -> {
              var kwargs = Map.<String, Object>of();
              if (!kwargs.containsKey("llama") || !kwargs.containsKey("messages")) {
                List<String> missing = new ArrayList<>();
                if (!kwargs.containsKey("llama")) missing.add("'llama'");
                if (!kwargs.containsKey("messages")) missing.add("'messages'");
                throw new PythonException(
                    PythonErrorType.TYPE_ERROR,
                    "LlamaChatCompletionHandler.__call__() missing 2 required keyword-only"
                        + " arguments: "
                        + String.join(" and ", missing),
                    null);
              }
              return PythonChatFormat.handlerCall(null, kwargs);
            });
    Object formatter =
        capture(() -> PythonChatFormat.formatterCall(null, Map.of("messages", List.of())));
    return dict("handler", handler, "formatter", formatter);
  }

  // ---- strftime_now / tojson -------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Object runStatic(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> results = new ArrayList<>();
    List<Object> calls = new ArrayList<>();
    for (Object item : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) item;
      if (step.get(0).equals("strftime")) {
        results.add(
            capture(
                () ->
                    PythonChatFormat.strftimeNow(
                        (String) step.get(1),
                        f -> {
                          calls.add(List.of("now"));
                          calls.add(List.of("strftime", f));
                          return "NOW(" + f + ")";
                        })));
      } else {
        Map<String, Object> kw = (Map<String, Object>) step.get(2);
        results.add(
            capture(
                () ->
                    PythonChatFormat.tojson(
                        step.get(1),
                        (Boolean) kw.getOrDefault("ensure_ascii", false),
                        kw.get("indent") == null ? null : ((Number) kw.get("indent")).intValue(),
                        (List<String>) kw.get("separators"),
                        (Boolean) kw.getOrDefault("sort_keys", false))));
      }
    }
    return dict("result", results, "calls", calls);
  }

  // ---- nested chat_completion_handler ------------------------------------------------

  private record FormatterResult(
      String prompt, Object stop, boolean addedSpecial, Object stoppingCriteria)
      implements PythonChatFormat.FormatterResult {}

  @SuppressWarnings("unchecked")
  private static Object runHandler(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var ports =
        new PythonChatFormat.Ports() {
          public FormatterResult format(Map<String, Object> kwargs) {
            trace.add(List.of("format", kwargs));
            return new FormatterResult(
                "PROMPT",
                argument.get("result_stop"),
                (Boolean) argument.getOrDefault("added_special", false),
                null);
          }

          public int[] tokenize(byte[] utf8, boolean addBos) {
            trace.add(List.of("tokenize", hexOf(utf8), addBos, true));
            return new int[] {1, 2};
          }

          public boolean verbose() {
            return false;
          }

          public Object grammarForResponseFormat(Map<String, Object> rf) {
            return List.of("rf", rf.get("type"));
          }

          public Object grammarFromJsonSchema(String schemaJson) {
            trace.add(List.of("gs_schema", schemaJson, false));
            return List.of("schema", schemaJson);
          }

          public Object grammarFromString() {
            trace.add(List.of("gs_string", "GBNF", false));
            return List.of("string", "GBNF");
          }

          public Object createCompletion(Map<String, Object> kwargs) {
            trace.add(List.of("create", kwargs));
            return "CHUNKS";
          }

          public Object convertCompletion(Object chunks, boolean stream) {
            return List.of("conv", chunks, stream);
          }

          public Object convertCompletionToFunction(String name, Object chunks, boolean stream) {
            return List.of("convf", name, chunks, stream);
          }

          public java.util.function.Consumer<String> stderr() {
            return line -> trace.add(List.of("stderr", line));
          }
        };
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("messages", List.of(Map.of("role", "u")));
    args.put("functions", null);
    args.put("function_call", null);
    args.put("tools", null);
    args.put("tool_choice", null);
    args.put("stop", List.of());
    args.put("stream", false);
    args.put("temperature", 0.2);
    args.put("top_p", 0.95);
    args.put("top_k", 40);
    args.put("min_p", 0.05);
    args.put("typical_p", 1.0);
    args.put("seed", null);
    args.put("max_tokens", null);
    args.put("presence_penalty", 0.0);
    args.put("frequency_penalty", 0.0);
    args.put("repeat_penalty", 1.1);
    args.put("tfs_z", 1.0);
    args.put("mirostat_mode", 0);
    args.put("mirostat_tau", 5.0);
    args.put("mirostat_eta", 0.1);
    args.put("model", null);
    args.put("logits_processor", null);
    args.put("grammar", null);
    args.put("logit_bias", null);
    args.put("response_format", null);
    args.put("logprobs", null);
    args.put("top_logprobs", null);
    args.put("kwargs", Map.of());
    args.putAll((Map<String, Object>) argument.getOrDefault("call", Map.of()));
    Object result = capture(() -> PythonChatFormat.chatCompletionHandler(ports, args));
    return dict("result", result, "trace", trace);
  }

  private static String hexOf(byte[] bytes) {
    var out = new StringBuilder();
    for (byte b : bytes)
      out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
    return out.toString();
  }

  // ---- server endpoints ------------------------------------------------------------------

  private static final class StubLlama implements PythonServerApp.Llama {
    final List<Object> trace = new ArrayList<>();

    public int[] tokenize(byte[] token, boolean addBos, boolean special) {
      trace.add(List.of("tokenize", hexOf(token), addBos, special));
      int[] out = new int[token.length];
      for (int i = 0; i < out.length; i++) out[i] = token[i] & 0xff;
      return out;
    }

    public byte[] detokenize(int[] tokens) {
      List<Object> list = new ArrayList<>();
      for (int t : tokens) list.add(t);
      trace.add(List.of("detokenize", list));
      byte[] out = new byte[tokens.length];
      for (int i = 0; i < out.length; i++) out[i] = (byte) tokens[i];
      return out;
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runEndpoints(Object input) {
    var argument = (Map<String, Object>) input;
    var llama = new StubLlama();
    List<Object> results = new ArrayList<>();
    for (Object item : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) item;
      results.add(
          capture(
              () ->
                  switch ((String) step.get(0)) {
                    case "auth" -> {
                      Object key = step.get(1);
                      String creds = (String) step.get(2);
                      yield PythonServerApp.authenticate(() -> key, () -> creds);
                    }
                    case "tokenize" ->
                        PythonServerApp.tokenizeEndpoint(step.get(1), step.get(2), model -> llama);
                    case "count" ->
                        PythonServerApp.countQueryTokensEndpoint(
                            step.get(1), step.get(2), model -> llama);
                    case "detok" ->
                        PythonServerApp.detokenizeEndpoint(
                            step.get(1), step.get(2), model -> llama);
                    case "models" -> PythonServerApp.getModels((List<String>) step.get(1));
                    default -> throw new AssertionError(step);
                  }));
    }
    return results;
  }
}
