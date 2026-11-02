package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import io.github.yaulun.javacpp.python.PythonValues;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonChatHandlersContractTest {
  @TestFactory
  Stream<DynamicTest> matchesOriginalChatHandlerBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-chathandlers-contracts.tsv"),
            "Run generate_chathandlers_contracts.py --source-root .tools/python-api-audit")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "chathandlers:" + i,
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

  private record Handle(String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Object show(Object value) {
    if (value instanceof FakeLlama) return "llama";
    if (value instanceof Handle handle) return handle.name();
    if (value instanceof List<?> items)
      return items.stream().map(PythonChatHandlersContractTest::show).toList();
    if (value instanceof Map<?, ?> items) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (var entry : items.entrySet())
        out.put(String.valueOf(entry.getKey()), show(entry.getValue()));
      return out;
    }
    if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
    return value;
  }

  private static final class Ports
      implements PythonChatFormatHandlers.GrammarPort,
          PythonChatFormatHandlers.TokenizerPort,
          PythonChatFormatHandlers.JinjaPort {
    private final Map<String, Object> kase;
    private final List<Object> trace;

    Ports(Map<String, Object> kase, List<Object> trace) {
      this.kase = kase;
      this.trace = trace;
    }

    private boolean flag(String key, boolean fallback) {
      Object value = kase.get(key);
      return value == null ? fallback : (boolean) value;
    }

    @Override
    public Object fromString(String grammar) {
      trace.add(List.of("from_string", grammar));
      return new Handle("grammar");
    }

    @Override
    public Object fromString(String grammar, boolean verbose) {
      trace.add(List.of("from_string", grammar, Map.of("verbose", verbose)));
      return new Handle("grammar");
    }

    @Override
    public Object fromJsonSchema(String schema, boolean verbose) {
      trace.add(List.of("from_json_schema", schema, Map.of("verbose", verbose)));
      if (flag("schemaFail", false)) {
        throw PythonValues.error(PythonErrorType.VALUE_ERROR, "bad schema");
      }
      return new Handle("schema_grammar");
    }

    @Override
    public Object jsonSchemaToGbnf(String json) {
      trace.add(List.of("json_schema_to_gbnf", json));
      return "gbnf:" + json.length();
    }

    @Override
    public String jsonGbnf() {
      return "JSON_GBNF_TEXT";
    }

    @Override
    public void printOut(Object value) {
      trace.add(List.of("print", String.valueOf(value)));
    }

    @Override
    public void suppress(boolean disable) {
      trace.add(List.of("suppress_stdout_stderr", disable));
    }

    @Override
    public Object fromPretrained(Object path) {
      trace.add(List.of("from_pretrained", path));
      return new Handle("tokenizer");
    }

    @Override
    public void useDefaultSystemPrompt(Object tokenizer, boolean value) {}

    @Override
    public Object applyChatTemplate(Object tokenizer, Object messages, Map<String, Object> kwargs) {
      Map<String, Object> kw = new LinkedHashMap<>();
      kw.put("tokenize", false);
      kw.putAll(kwargs);
      trace.add(List.of("apply_chat_template", show(messages), show(kw)));
      return kase.getOrDefault("prompt", "PROMPT");
    }

    @Override
    public Object eosToken(Object tokenizer) {
      return "</s>";
    }

    @Override
    public void envFromString(String template) {
      trace.add(List.of("env_init", List.of("lstrip_blocks", "trim_blocks")));
      trace.add(List.of("from_string", template.length()));
    }

    @Override
    public String render(Map<String, Object> context) {
      trace.add(List.of("render", show(context)));
      return (String) kase.getOrDefault("rendered", "RENDERED");
    }
  }

  private static final class FakeLlama implements PythonChatFormatHandlers.QwenLlama {
    private final List<Object> trace;
    private int nTokens;

    FakeLlama(List<Object> trace) {
      this.trace = trace;
      nTokens = 7;
      trace.add(List.of("n_tokens", 7));
    }

    @Override
    public void reset() {
      trace.add(List.of("reset"));
    }

    @Override
    public void kvCacheClear() {
      trace.add(List.of("kv_cache_clear"));
    }

    @Override
    public void setNTokens(int value) {
      nTokens = value;
      trace.add(List.of("n_tokens", value));
    }

    @Override
    public boolean hasInputIds() {
      return true;
    }

    @Override
    public void inputIdsFill(int value) {
      trace.add(List.of("fill", value));
    }
  }

  private static Map<String, Object> formatterResponse(PythonChatFormatHandlers.FormatterResult r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("prompt", r.prompt());
    out.put("stop", r.stop());
    out.put("added_special", r.addedSpecial());
    out.put("stopping_criteria", null);
    return out;
  }

  private static Map<String, Object> msg(String role, Object content) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", role);
    m.put("content", content);
    return m;
  }

  @SuppressWarnings("unchecked")
  private static Object run(
      Map<String, Object> kase,
      List<Object> step,
      Map<String, Object> box,
      Ports ports,
      List<Object> trace)
      throws Exception {
    String name = (String) step.get(0);
    switch (name) {
      case "gJson" -> {
        return show(PythonChatFormatHandlers.grammarForJson(ports, (boolean) step.get(1)));
      }
      case "gSchema" -> {
        return show(
            PythonChatFormatHandlers.grammarForJsonSchema(
                ports, (String) step.get(1), (boolean) step.get(2), (boolean) step.get(3)));
      }
      case "gResp" -> {
        boolean verbose = step.size() > 2 && (boolean) step.get(2);
        return show(
            PythonChatFormatHandlers.grammarForResponseFormat(ports, map(step.get(1)), verbose));
      }
      case "autoFmt" -> {
        box.put("fmt", PythonChatFormatHandlers.hfAutotokenizerToChatFormatter(ports, step.get(1)));
        return "ok";
      }
      case "autoCall" -> {
        var fmt =
            (java.util.function.Function<Object[], PythonChatFormatHandlers.FormatterResult>)
                box.get("fmt");
        PythonChatFormatHandlers.FormatterResult r =
            fmt.apply(new Object[] {step.get(1), step.get(2)});
        return show(formatterResponse(r));
      }
      case "autoHandler" -> {
        PythonChatFormatHandlers.hfAutotokenizerToChatCompletionHandler(ports, step.get(1));
        return "chat_completion_handler";
      }
      case "cfgFmt" -> {
        boolean addGen = step.size() > 2 ? (boolean) step.get(2) : true;
        box.put(
            "fmt",
            PythonChatFormatHandlers.hfTokenizerConfigToChatFormatter(
                ports, map(step.get(1)), addGen));
        return "ok";
      }
      case "cfgCall" -> {
        var fmt =
            (java.util.function.Function<Object[], PythonChatFormatHandlers.FormatterResult>)
                box.get("fmt");
        PythonChatFormatHandlers.FormatterResult r =
            fmt.apply(new Object[] {step.get(1), step.get(2)});
        return show(formatterResponse(r));
      }
      case "cfgHandler" -> {
        boolean addGen = step.size() > 2 ? (boolean) step.get(2) : true;
        PythonChatFormatHandlers.hfTokenizerConfigToChatCompletionHandler(
            ports, map(step.get(1)), addGen);
        return "chat_completion_handler";
      }
      case "convFn" -> {
        return show(
            PythonChatFormatHandlers.convertCompletionToChatFunction(
                (String) step.get(1), step.get(2), (boolean) step.get(3)));
      }
      case "convStream" -> {
        return show(
            PythonChatFormatHandlers.convertCompletionToChatFunction(
                (String) step.get(1), list(step.get(2)), true));
      }
      case "typeDef" -> {
        return PythonChatFormatHandlers.generateTypeDefinition(
            map(step.get(1)), i(step.get(2)), Map.of());
      }
      case "sharedDefs" -> {
        return PythonChatFormatHandlers.generateSharedDefinitions(map(step.get(1)), i(step.get(2)));
      }
      case "schemaFns" -> {
        return "v2".equals(step.get(2))
            ? PythonChatFormatHandlers.generateSchemaFromFunctionsV2(list(step.get(1)), "functions")
            : PythonChatFormatHandlers.generateSchemaFromFunctionsV1(
                list(step.get(1)), "functions");
      }
      case "prepV1" -> {
        return PythonChatFormatHandlers.prepareMessagesForInferenceV1(
            list(step.get(1)), (List<Object>) step.get(2), (List<Object>) step.get(3));
      }
      case "prepV2" -> {
        PythonChatFormatHandlers.TokenizerPort silent =
            new PythonChatFormatHandlers.TokenizerPort() {
              public Object fromPretrained(Object path) {
                return null;
              }

              public void useDefaultSystemPrompt(Object t, boolean v) {}

              public Object applyChatTemplate(Object t, Object m, Map<String, Object> k) {
                return step.get(1);
              }

              public Object eosToken(Object t) {
                return "</s>";
              }
            };
        return PythonChatFormatHandlers.prepareMessagesForInferenceV2(
            silent,
            new Handle("tokenizer"),
            list(step.get(2)),
            (String) step.get(3),
            (List<Object>) step.get(4),
            (List<Object>) step.get(5),
            step.get(6));
      }
      case "getGrammar" -> {
        return show(
            PythonChatFormatHandlers.functionaryGetGrammar(
                ports,
                step.get(1),
                (List<Object>) step.get(2),
                (List<Object>) step.get(3),
                (boolean) kase.getOrDefault("verbose", false)));
      }
      case "imgUrls" -> {
        return PythonChatFormatHandlers.getImageUrls(list(step.get(1)));
      }
      case "splitUrls" -> {
        return PythonChatFormatHandlers.splitTextOnImageUrls(
            (String) step.get(1), list(step.get(2)).stream().map(v -> (String) v).toList());
      }
      case "mtmdImgUrls" -> {
        return PythonChatFormatHandlers.getImageUrls(list(step.get(1)));
      }
      case "tmplMsgs" -> {
        return show(
            PythonChatFormatHandlers.getTemplateMessages(list(step.get(1)), (String) step.get(2)));
      }
      case "convMsg" -> {
        return show(
            PythonChatFormatHandlers.convertMessageForTemplate(
                map(step.get(1)), (String) step.get(2)));
      }
      case "convPart" -> {
        return show(
            PythonChatFormatHandlers.convertContentPartForTemplate(
                step.get(1), (String) step.get(2)));
      }
      case "decPiece" -> {
        Object arg =
            (boolean) step.get(2) ? HexFormat.of().parseHex((String) step.get(1)) : step.get(1);
        return PythonChatFormatHandlers.decodeTokenPiece(arg);
      }
      case "postText" -> {
        return PythonChatFormatHandlers.postprocessTemplateText(
            (String) step.get(1),
            list(step.get(2)).stream().map(v -> (String) v).toList(),
            (String) step.get(3));
      }
      case "qwen" -> {
        Map<String, Object> kwargs = new LinkedHashMap<>(map(step.get(1)));
        FakeLlama llama = (FakeLlama) box.computeIfAbsent("llama", k -> new FakeLlama(trace));
        kwargs.put("llama", llama);
        Object parentResult =
            PythonChatFormatHandlers.qwen25vlCall(
                kwargs,
                llama,
                (boolean) kase.getOrDefault("verbose", false),
                (key, v) -> {},
                false,
                kw -> {
                  trace.add(List.of("parent_call", show(kw)));
                  return "PARENT";
                },
                text -> trace.add(List.of("print", text)));
        return parentResult;
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
    Ports ports = new Ports(kase, trace);
    List<Object> results = new ArrayList<>();
    Map<String, Object> box = new HashMap<>();
    for (Object op : list(kase.get("ops"))) {
      List<Object> step = list(op);
      results.add(capture(() -> run(kase, step, box, ports, trace)));
    }
    return dict("results", results, "trace", trace);
  }
}
