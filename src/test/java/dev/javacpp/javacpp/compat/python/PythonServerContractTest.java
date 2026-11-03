package dev.javacpp.compat.python;

import static dev.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.javacpp.python.Py;
import dev.javacpp.python.PythonErrorType;
import dev.javacpp.python.PythonException;
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

class PythonServerContractTest {
  @TestFactory
  Stream<DynamicTest> matchesPinnedServerHelpers() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-server-contracts.tsv"),
            "Run generate_server_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index ->
                DynamicTest.dynamicTest(
                    "server:" + index,
                    () -> {
                      String[] parts = lines.get(index).split("\t");
                      Object argument = read(parts[1]);
                      Object actual =
                          switch (parts[0]) {
                            case "getbase" -> runGetbase(argument);
                            case "bool" -> runBool(argument);
                            case "json" -> runJson(argument);
                            case "addargs" -> runAddArgs(argument);
                            case "parsemodel" -> runParseModel(argument);
                            case "dyn" -> runDyn(argument);
                            case "app" -> runApp(argument);
                            case "proxy" -> runProxy(argument);
                            case "appcreate" -> runAppCreate(argument);
                            case "appprepare" -> runAppPrepare(argument);
                            case "apppublish" -> runAppPublish(argument);
                            case "appcompletion" -> runAppCompletion(argument);
                            case "appchat" -> runAppChat(argument);
                            case "appembed" -> runAppEmbed(argument);
                            case "loadmodel" -> runLoadModel(argument);
                            case "servermain" -> runServerMain(argument);
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

  // ---- annotation port ------------------------------------------------------

  private record Ann(String origin, String base, List<PythonServerCli.Annotation> args)
      implements PythonServerCli.Annotation {
    public String baseType() {
      return base;
    }
  }

  private static Ann ann(Object spec) {
    return switch ((String) spec) {
      case "literal" ->
          new Ann(
              "literal",
              null,
              List.of(new Ann(null, "str", List.of()), new Ann(null, "str", List.of())));
      case "union" ->
          new Ann(
              "union",
              null,
              List.of(new Ann(null, "int", List.of()), new Ann("none", null, List.of())));
      case "union_empty" -> new Ann(null, "NoneType", List.of());
      case "list" -> new Ann("list", null, List.of(new Ann(null, "int", List.of())));
      case "list_lit" ->
          new Ann(
              "list",
              null,
              List.of(new Ann("literal", null, List.of(new Ann(null, "str", List.of())))));
      case "lit_list" ->
          new Ann("literal", null, List.of(new Ann("list", "_GenericAlias", List.of())));
      case "dict" -> new Ann("dict", "_GenericAlias", List.of());
      case "lit_union_list" ->
          new Ann(
              "union",
              null,
              List.of(
                  new Ann("literal", null, List.of(new Ann("list", "_GenericAlias", List.of()))),
                  new Ann(null, "int", List.of())));
      case "lit_union_dict" ->
          new Ann(
              "union",
              null,
              List.of(
                  new Ann("literal", null, List.of(new Ann("dict", "_GenericAlias", List.of()))),
                  new Ann(null, "int", List.of())));
      case "int" -> new Ann(null, "int", List.of());
      case "bool" -> new Ann(null, "bool", List.of());
      default -> null;
    };
  }

  private static Object runGetbase(Object input) {
    Ann node = ann((String) input);
    return dict(
        "base",
        capture(
            () -> {
              String base = PythonServerCli.getBaseType(node);
              // typing _GenericAlias lands on the same base label Python reports.
              return base == null ? "_GenericAlias" : base;
            }),
        "list",
        capture(() -> PythonServerCli.containsListType(node)),
        "dict",
        capture(() -> PythonServerCli.containsDictType(node)));
  }

  private static Object runBool(Object input) {
    return capture(() -> PythonServerCli.parseBoolArg(input));
  }

  private static Object runJson(Object input) {
    return capture(() -> PythonServerCli.parseJsonObjectArg(input));
  }

  private record Field(
      String name, Ann annotation, String description, Object defaultValue, boolean required)
      implements PythonServerCli.ModelField {}

  private static Object runAddArgs(Object input) {
    List<Object> trace = new ArrayList<>();
    var parser =
        (PythonServerCli.ArgumentParser)
            (flag, dest, nargs, type, help) ->
                trace.add(java.util.Arrays.asList(flag, dest, nargs, type, help));
    var fields =
        List.<PythonServerCli.ModelField>of(
            new Field("a", ann("literal"), "an opt", "d0", false),
            new Field("b", ann("list"), "many", null, false),
            new Field("c", ann("bool"), "flag", null, false),
            new Field("d", ann("dict"), "m", null, false),
            new Field("e", ann("int"), null, null, false),
            new Field("f", null, "noann", null, false),
            new Field("g", ann("int"), "req", null, true));
    var model =
        new PythonServerCli.Model() {
          public List<PythonServerCli.ModelField> modelFields() {
            return fields;
          }

          public Object construct(Map<String, Object> kwargs) {
            return kwargs;
          }
        };
    PythonServerCli.addArgsFromModel(parser, model);
    return trace;
  }

  @SuppressWarnings("unchecked")
  private static Object runParseModel(Object input) {
    var argument = (Map<String, Object>) input;
    var model =
        new PythonServerCli.Model() {
          public List<PythonServerCli.ModelField> modelFields() {
            return List.of(
                new Field("a", null, null, null, false), new Field("b", null, null, null, false));
          }

          public Object construct(Map<String, Object> kwargs) {
            return Map.of("made", kwargs);
          }
        };
    return capture(() -> modelConstruct(model, (Map<String, Object>) argument.get("ns")));
  }

  private static Object modelConstruct(PythonServerCli.Model model, Map<String, Object> namespace) {
    Object made = PythonServerCli.parseModelFromArgs(model, namespace);
    return made; // construct already wraps in {"made": kwargs}
  }

  @SuppressWarnings("unchecked")
  private static Object runDyn(Object input) {
    var argument = (Map<String, Object>) input;
    Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) argument.get("values"));
    Object result = capture(() -> PythonServerSettings.setDynamicDefaults(values, () -> 8));
    return dict("result", result, "values", values);
  }

  // ---- app module helpers ------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Object runApp(Object input) {
    var argument = (Map<String, Object>) input;
    var state = new PythonServerApp.ModuleState();
    List<Object> lockTrace = new ArrayList<>();
    var outer =
        new PythonServerApp.Lock() {
          public void acquire() {
            lockTrace.add(List.of("outer", "acquire"));
          }

          public void release() {
            lockTrace.add(List.of("outer", "release"));
          }
        };
    var inner =
        new PythonServerApp.Lock() {
          public void acquire() {
            lockTrace.add(List.of("inner", "acquire"));
          }

          public void release() {
            lockTrace.add(List.of("inner", "release"));
          }
        };
    List<Object> results = new ArrayList<>();
    for (Object item : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) item;
      List<Object> stepTrace = new ArrayList<>();
      Object result =
          capture(
              () -> {
                switch ((String) step.get(0)) {
                  case "set_settings" -> {
                    PythonServerApp.setServerSettings(state, step.get(1));
                    return "ok";
                  }
                  case "get_settings" -> {
                    List<Object> out = new ArrayList<>();
                    for (Object v : PythonServerApp.getServerSettings(state)) out.add(v);
                    return out;
                  }
                  case "set_proxy" -> {
                    PythonServerApp.setLlamaProxy(
                        state, (List<?>) step.get(1), models -> "proxy" + models);
                    return "ok";
                  }
                  case "set_ping" -> {
                    PythonServerApp.setPingMessageFactory(
                        state, () -> ((String) step.get(1)).getBytes());
                    return "ok";
                  }
                  case "get_proxy" -> {
                    state.llamaProxy = "PROXY";
                    Object proxy = PythonServerApp.getLlamaProxy(state, outer, inner);
                    stepTrace.addAll(lockTrace);
                    lockTrace.clear();
                    return proxy;
                  }
                  case "bias" -> {
                    var llama =
                        (PythonServerApp.Tokenizer)
                            (token, addBos, special) -> {
                              stepTrace.add(List.of("tokenize", hexOf(token), addBos, special));
                              return java.util.stream.IntStream.range(0, token.length)
                                  .map(i -> token[i] & 0xff)
                                  .toArray();
                            };
                    return PythonServerApp.logitBiasTokensToInputIds(
                        llama, (Map<String, Object>) step.get(1));
                  }
                  default -> throw new AssertionError(step);
                }
              });
      // Lock trace must interleave outer/inner like the async version.
      results.add(dict("result", result, "trace", stepTrace));
    }
    if (results.size() == 1
        && ((List<?>) argument.get("steps")).get(0) instanceof List<?> s
        && s.get(0).equals("get_proxy")) {
      return results.get(0);
    }
    return results;
  }

  private static String hexOf(byte[] bytes) {
    var out = new StringBuilder();
    for (byte b : bytes)
      out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
    return out.toString();
  }

  // ---- LlamaProxy ----------------------------------------------------------------

  private static final class Settings implements PythonLlamaProxy.ModelSettings {
    final String model;
    final List<Object> trace;
    String currentAlias;

    Settings(String model, String alias, List<Object> trace) {
      this.model = model;
      this.currentAlias = alias;
      this.trace = trace;
    }

    public String modelAlias() {
      return currentAlias;
    }

    public void setModelAlias(String alias) {
      this.currentAlias = alias;
    }

    public String modelName() {
      return model;
    }

    public Object modelDump() {
      trace.add(List.of("dump", model));
      return Map.of("alias", currentAlias);
    }
  }

  private static final class FakeModel implements PythonLlamaProxy.LlamaModel {
    final String alias;
    final List<Object> trace;

    FakeModel(String alias, List<Object> trace) {
      this.alias = alias;
      this.trace = trace;
    }

    public void close() {
      trace.add(java.util.Arrays.asList("close", alias));
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runProxy(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> loaderCalls = new ArrayList<>();
    List<Object> trace = new ArrayList<>();
    java.util.function.Function<PythonLlamaProxy.ModelSettings, PythonLlamaProxy.LlamaModel>
        loader =
            settings -> {
              loaderCalls.add(settings.modelAlias());
              return new FakeModel(settings.modelAlias(), trace);
            };
    List<PythonLlamaProxy.ModelSettings> models = new ArrayList<>();
    for (int i = 0; i < ((Number) argument.get("models")).intValue(); i++) {
      models.add(new Settings("m" + (i + 1), null, trace));
    }
    List<Object> results = new ArrayList<>();
    Object overall =
        capture(
            () -> {
              var proxy = new PythonLlamaProxy(models, loader);
              for (Object item : (List<?>) argument.get("steps")) {
                List<?> step = (List<?>) item;
                List<Object> stepTrace = new ArrayList<>();
                Object result =
                    capture(
                        () ->
                            switch ((String) step.get(0)) {
                              case "call" -> {
                                var model = proxy.call((String) step.get(1));
                                yield model instanceof FakeModel m ? m.alias : null;
                              }
                              case "get" -> proxy.get((String) step.get(1));
                              case "set" -> {
                                proxy.set(
                                    (String) step.get(1),
                                    step.get(2),
                                    json -> {
                                      trace.add(List.of("validate", json));
                                      return new Settings("V:" + json, null, trace);
                                    });
                                yield "ok";
                              }
                              case "iter" -> {
                                List<Object> out = new ArrayList<>();
                                for (String key : proxy.keys()) out.add(key);
                                yield out;
                              }
                              case "free" -> {
                                proxy.free();
                                yield "ok";
                              }
                              default -> throw new AssertionError(step);
                            });
                stepTrace.addAll(trace);
                trace.clear();
                results.add(dict("result", result, "trace", stepTrace));
              }
              return results;
            });
    return dict("result", overall instanceof List ? overall : overall, "loader", loaderCalls);
  }

  // ---- app.py endpoints --------------------------------------------------------

  private interface Marker {
    String mark();
  }

  @SuppressWarnings("unchecked")
  private static Object mark(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    if (value instanceof byte[] bytes) {
      return Map.of("bytes", hexOf(bytes));
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object item : list) out.add(mark(item));
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(mark(k), mark(v)));
      return out;
    }
    if (value instanceof java.util.Set<?> set) {
      List<Object> items = new ArrayList<>();
      for (Object item : set) items.add(mark(item));
      items.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
      return Map.of("set", items);
    }
    if (value instanceof Marker tagged) {
      return tagged.mark();
    }
    if (value instanceof Supplier || value instanceof java.util.function.Function) {
      return "_callable";
    }
    if (value instanceof PythonServerApp.BoundCall bound) {
      Map<String, Object> bc = new LinkedHashMap<>();
      bc.put("function", bound.function());
      bc.put("keywords", mark(bound.keywords()));
      return bc;
    }
    return String.valueOf(value);
  }

  private static Object captureEp(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (PythonServerApp.HttpException error) {
      return dict(
          "error", "HTTPException", "status_code", error.statusCode(), "detail", error.detail());
    } catch (PythonServerApp.CancelledError error) {
      return dict("error", "CancelledError", "message", "");
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  private static Object epResult(Object result) {
    if (result instanceof Map<?, ?> map && map.containsKey("error")) {
      return result;
    }
    return mark(result);
  }

  private static final class BodyStub
      implements PythonServerApp.CompletionBody,
          PythonServerApp.ChatBody,
          PythonServerApp.EmbeddingBody,
          Marker {
    final Map<String, Object> spec;
    final List<Object> trace;
    Object promptValue;

    BodyStub(Map<String, Object> spec, List<Object> trace) {
      this.spec = spec;
      this.trace = trace;
      this.promptValue = spec.get("prompt");
    }

    public Object logitBias() {
      return spec.get("logit_bias");
    }

    public String logitBiasType() {
      return (String) spec.get("logit_bias_type");
    }

    public String grammar() {
      return (String) spec.get("grammar");
    }

    public long minTokens() {
      return spec.get("min_tokens") instanceof Number n ? n.longValue() : 0;
    }

    public Object prompt() {
      return promptValue;
    }

    public void setPrompt(Object prompt) {
      promptValue = prompt;
    }

    public Object model() {
      return spec.get("model");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> modelDump(java.util.Set<String> exclude) {
      trace.add(
          java.util.Arrays.asList(
              "model_dump",
              exclude == null ? null : new ArrayList<>(new java.util.TreeSet<>(exclude))));
      return new LinkedHashMap<>((Map<String, Object>) spec.getOrDefault("dump", Map.of()));
    }

    public String mark() {
      return "body";
    }
  }

  private static final class LlamaStub implements PythonServerApp.ResourceLlama, Marker {
    final List<Object> trace;
    final int eos;

    LlamaStub(List<Object> trace, int eos) {
      this.trace = trace;
      this.eos = eos;
    }

    public int[] tokenize(byte[] token, boolean addBos, boolean special) {
      trace.add(List.of("tokenize", hexOf(token), addBos, special));
      return new String(token, StandardCharsets.UTF_8).codePoints().toArray();
    }

    public int tokenEos() {
      trace.add(List.of("token_eos"));
      return eos;
    }

    public String mark() {
      return "llama";
    }
  }

  private static final class RequestStub implements PythonServerApp.Request, Marker {
    final String path;
    final String client;
    final java.util.Deque<Boolean> disconnect;
    final List<Object> trace;

    @SuppressWarnings("unchecked")
    RequestStub(Map<String, Object> spec, List<Object> trace) {
      this.trace = trace;
      path = (String) spec.getOrDefault("path", "/v1/completions");
      client = (String) spec.getOrDefault("client", "client-1");
      disconnect =
          new java.util.ArrayDeque<>((List<Boolean>) spec.getOrDefault("disconnect", List.of()));
    }

    public String urlPath() {
      return path;
    }

    public boolean isDisconnected() {
      boolean value = disconnect.isEmpty() ? false : disconnect.poll();
      trace.add(List.of("is_disconnected", value));
      return value;
    }

    public String client() {
      return client;
    }

    public String mark() {
      return "request";
    }
  }

  private static final class ChanStub implements PythonServerApp.SendChannel {
    final List<Object> trace;

    ChanStub(List<Object> trace) {
      this.trace = trace;
    }

    public void enter() {
      trace.add(List.of("chan", "enter"));
    }

    public void send(Map<String, Object> event) {
      trace.add(List.of("send", mark(event)));
    }

    public void close() {
      trace.add(List.of("chan", "close"));
    }
  }

  private static PythonServerApp.Lock recLock(String name, List<Object> trace) {
    return new PythonServerApp.Lock() {
      public void acquire() {
        trace.add(List.of(name, "acquire"));
      }

      public void release() {
        trace.add(List.of(name, "release"));
      }
    };
  }

  private static PythonServerApp.OuterLock recOuter(
      String name, boolean locked, List<Object> trace) {
    return new PythonServerApp.OuterLock() {
      public void acquire() {
        trace.add(List.of(name, "acquire"));
      }

      public void release() {
        trace.add(List.of(name, "release"));
      }

      public boolean locked() {
        trace.add(List.of(name, "locked"));
        return locked;
      }
    };
  }

  private record Irq(boolean on) {}

  private static PythonServerApp.EndpointDeps endpointDeps(
      List<Object> trace, Object tpResult, RuntimeException tpError) {
    return new PythonServerApp.EndpointDeps() {
      public Object grammarFromString(String grammar) {
        trace.add(List.of("grammar_from_string", grammar));
        return List.of("grammar", grammar);
      }

      public Object minTokensLogitsProcessor(long minTokens, int tokenEos) {
        trace.add(List.of("mtp", minTokens, tokenEos));
        return List.of("mtp", minTokens, tokenEos);
      }

      public java.util.List<Object> logitsProcessorList(java.util.List<Object> processors) {
        trace.add(List.of("lpl", mark(processors)));
        return new ArrayList<>(processors);
      }

      public Object runInThreadpool(Object callable, Object[] args, Map<String, Object> kwargs) {
        trace.add(
            List.of(
                "threadpool", mark(callable), mark(java.util.Arrays.asList(args)), mark(kwargs)));
        if (tpError != null) {
          throw tpError;
        }
        return tpResult;
      }

      @SuppressWarnings("unchecked")
      public java.util.Iterator<Object> iterateInThreadpool(Object iterator) {
        return ((List<Object>) iterator).iterator();
      }

      public Object[] memoryObjectStream(int capacity) {
        trace.add(List.of("memory_stream", capacity));
        return new Object[] {"send_chan", "recv_chan"};
      }

      public Object eventSourceResponse(
          Object receiveChannel,
          PythonServerApp.BoundCall dataSenderCallable,
          String sep,
          Object pingMessageFactory) {
        List<Object> payload =
            java.util.Arrays.asList(
                mark(receiveChannel),
                dataSenderCallable.function(),
                mark(dataSenderCallable.keywords()),
                sep,
                mark(pingMessageFactory));
        List<Object> entry = new ArrayList<>();
        entry.add("esr");
        entry.addAll(payload);
        trace.add(entry);
        return Map.of("esr", payload);
      }

      public Object llamaCall() {
        return "Llama.__call__";
      }

      public Object llamaCreateChatCompletion() {
        return "Llama.create_chat_completion";
      }

      public Object boundMethod(Object target, String name) {
        return name;
      }

      public boolean interruptRequests(Object serverSettings) {
        return serverSettings instanceof Irq irq && irq.on();
      }

      public void print(String line) {
        trace.add(List.of("print", line));
      }

      public void moveOnAfter(long seconds, boolean shield) {
        trace.add(List.of("move_on_after", seconds, shield));
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static Object runAppPrepare(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    Map<String, Object> kwargs =
        new LinkedHashMap<>((Map<String, Object>) argument.getOrDefault("kwargs", Map.of()));
    var llama = new LlamaStub(trace, ((Number) argument.getOrDefault("token_eos", 0)).intValue());
    PythonServerApp.ResourceProxy proxy =
        (Boolean) argument.getOrDefault("proxy", true)
            ? model -> {
              trace.add(java.util.Arrays.asList("proxy_call", mark(model)));
              return llama;
            }
            : null;
    var body = new BodyStub((Map<String, Object>) argument.get("body"), trace);
    var deps = endpointDeps(trace, null, null);
    Object result =
        captureEp(
            () ->
                mark(
                    PythonServerApp.prepareRequestResources(
                        body, proxy, argument.get("body_model"), kwargs, deps)));
    return dict("result", result, "kwargs", mark(kwargs), "trace", trace);
  }

  @SuppressWarnings("unchecked")
  private static Object runAppPublish(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var state = new PythonServerApp.ModuleState();
    if ((Boolean) argument.getOrDefault("settings", true)) {
      state.serverSettings = new Irq((Boolean) argument.getOrDefault("interrupt", false));
    }
    var outer = recOuter("outer", (Boolean) argument.getOrDefault("outer_locked", false), trace);
    var inner = recLock("inner", trace);
    var llama = new LlamaStub(trace, ((Number) argument.getOrDefault("token_eos", 0)).intValue());
    state.llamaProxy =
        (Boolean) argument.getOrDefault("proxy", true)
            ? (PythonServerApp.ResourceProxy)
                model -> {
                  trace.add(java.util.Arrays.asList("proxy_call", mark(model)));
                  return llama;
                }
            : null;
    var chan = new ChanStub(trace);
    var request =
        new RequestStub((Map<String, Object>) argument.getOrDefault("request", Map.of()), trace);
    var body = new BodyStub((Map<String, Object>) argument.getOrDefault("body", Map.of()), trace);
    var callSpec = (Map<String, Object>) argument.get("call");
    Object tpResult = argument.getOrDefault("chunks", List.of());
    RuntimeException tpError =
        callSpec != null && callSpec.containsKey("raise")
            ? dev.javacpp.python.Py.error(
                dev.javacpp.python.PythonErrorType.VALUE_ERROR, (String) callSpec.get("raise"))
            : null;
    var deps = endpointDeps(trace, tpResult, tpError);
    Object result =
        captureEp(
            () -> {
              PythonServerApp.getEventPublisher(
                  state,
                  outer,
                  inner,
                  request,
                  chan,
                  body,
                  argument.get("body_model"),
                  "llama_call",
                  new LinkedHashMap<>(
                      (Map<String, Object>) argument.getOrDefault("kwargs", Map.of())),
                  deps);
              return null;
            });
    return dict("result", result, "trace", trace);
  }

  private static PythonServerApp.ModuleState endpointState(
      Map<String, Object> argument, List<Object> trace) {
    var state = new PythonServerApp.ModuleState();
    var llama = new LlamaStub(trace, ((Number) argument.getOrDefault("token_eos", 0)).intValue());
    state.llamaProxy =
        (Boolean) argument.getOrDefault("proxy", true)
            ? (PythonServerApp.ResourceProxy)
                model -> {
                  trace.add(java.util.Arrays.asList("proxy_call", mark(model)));
                  return llama;
                }
            : null;
    if ((Boolean) argument.getOrDefault("ping", false)) {
      state.pingMessageFactory = () -> new byte[0];
    }
    return state;
  }

  @SuppressWarnings("unchecked")
  private static Object runAppCompletion(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var state = endpointState(argument, trace);
    var outer = recOuter("outer", false, trace);
    var inner = recLock("inner", trace);
    var request =
        new RequestStub((Map<String, Object>) argument.getOrDefault("request", Map.of()), trace);
    var body = new BodyStub((Map<String, Object>) argument.get("body"), trace);
    var deps = endpointDeps(trace, argument.getOrDefault("tp_result", "RES"), null);
    Object result =
        captureEp(() -> PythonServerApp.createCompletion(state, outer, inner, request, body, deps));
    return dict("result", epResult(result), "prompt", mark(body.prompt()), "trace", trace);
  }

  @SuppressWarnings("unchecked")
  private static Object runAppChat(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var state = endpointState(argument, trace);
    var outer = recOuter("outer", false, trace);
    var inner = recLock("inner", trace);
    var request =
        new RequestStub((Map<String, Object>) argument.getOrDefault("request", Map.of()), trace);
    var body = new BodyStub((Map<String, Object>) argument.get("body"), trace);
    var deps = endpointDeps(trace, argument.getOrDefault("tp_result", "CHAT"), null);
    Object result =
        captureEp(
            () -> PythonServerApp.createChatCompletion(state, outer, inner, request, body, deps));
    return dict("result", epResult(result), "trace", trace);
  }

  @SuppressWarnings("unchecked")
  private static Object runAppEmbed(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var body = new BodyStub((Map<String, Object>) argument.get("body"), trace);
    var llama = new LlamaStub(trace, 0);
    PythonServerApp.ResourceProxy proxy =
        (Boolean) argument.getOrDefault("proxy", true)
            ? model -> {
              trace.add(java.util.Arrays.asList("proxy_call", mark(model)));
              return llama;
            }
            : null;
    var deps = endpointDeps(trace, argument.getOrDefault("tp_result", "EMB"), null);
    Object result = captureEp(() -> PythonServerApp.createEmbedding(body, proxy, deps));
    return dict("result", epResult(result), "trace", trace);
  }

  // ---- create_app ----------------------------------------------------------------

  private record Cfg(List<Object> models) implements Marker {
    public String mark() {
      return "config";
    }
  }

  private record Srv(String name, Object rootPath, boolean disable) implements Marker {
    public String mark() {
      return name;
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runAppCreate(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var state = new PythonServerApp.ModuleState();
    Map<String, byte[]> files = new LinkedHashMap<>();
    for (var entry : ((Map<String, String>) argument.getOrDefault("files", Map.of())).entrySet()) {
      byte[] bytes = new byte[entry.getValue().length() / 2];
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = (byte) Integer.parseInt(entry.getValue().substring(2 * i, 2 * i + 2), 16);
      }
      files.put(entry.getKey(), bytes);
    }
    Map<String, String> env = (Map<String, String>) argument.getOrDefault("env", Map.of());
    var serverSpec = (Map<String, Object>) argument.getOrDefault("server", Map.of());
    var deps =
        new PythonServerApp.CreateAppDeps() {
          public String getenv(String name) {
            return env.get(name);
          }

          public boolean pathExists(String path) {
            trace.add(List.of("exists", path));
            return files.containsKey(path);
          }

          public byte[] readFileBytes(String path) {
            trace.add(List.of("open", path, "rb"));
            return files.get(path);
          }

          public Object yamlSafeLoad(byte[] data) {
            trace.add(List.of("yaml_safe_load", hexOf(data)));
            return argument.get("yaml_result");
          }

          public Object configFileValidateJson(Object payload) {
            trace.add(java.util.Arrays.asList("config_validate", mark(payload)));
            return new Cfg(
                new ArrayList<>((List<Object>) argument.getOrDefault("config_models", List.of())));
          }

          public Object configModels(Object config) {
            return ((Cfg) config).models();
          }

          public Object serverSettingsValidate(Object value) {
            trace.add(java.util.Arrays.asList("server_validate", mark(value)));
            return new Srv(
                (String) serverSpec.get("mark"),
                serverSpec.get("root_path"),
                (Boolean) serverSpec.getOrDefault("disable_ping", false));
          }

          public Object modelSettingsValidate(Object value) {
            trace.add(java.util.Arrays.asList("model_validate", mark(value)));
            return argument.get("model_mark");
          }

          public Object newSettings() {
            trace.add(List.of("settings"));
            return "settings";
          }

          public Object rootPath(Object serverSettings) {
            return ((Srv) serverSettings).rootPath();
          }

          public boolean disablePingEvents(Object serverSettings) {
            return ((Srv) serverSettings).disable();
          }

          public Object rawContextMiddlewareClass() {
            return "RawContextMiddleware";
          }

          public Object requestIdPlugin() {
            return "RequestIdPlugin";
          }

          public Object corsMiddlewareClass() {
            return "CORSMiddleware";
          }

          public Object middleware(Object cls, java.util.List<Object> plugins) {
            Map<String, Object> mw = new LinkedHashMap<>();
            mw.put("mw", mark(cls));
            mw.put("plugins", mark(plugins));
            return mw;
          }

          public Object router() {
            return "router";
          }

          public Object fastApi(Map<String, Object> kwargs) {
            trace.add(List.of("fastapi", mark(kwargs)));
            return "app";
          }

          public void addMiddleware(Object app, Object cls, Map<String, Object> kwargs) {
            trace.add(List.of("add_middleware", mark(cls), mark(kwargs)));
          }

          public void includeRouter(Object app, Object router) {
            trace.add(List.of("include_router", mark(router)));
          }

          public Object createProxy(java.util.List<?> modelSettings) {
            List<Object> marks = new ArrayList<>();
            for (Object model : modelSettings) marks.add(mark(model));
            return List.of("proxy", marks);
          }
        };
    List<?> args = (List<?>) argument.getOrDefault("args", List.of());
    Object settings = args.size() > 0 ? args.get(0) : null;
    Object serverArg = args.size() > 1 ? args.get(1) : null;
    if (serverArg instanceof Map<?, ?> spec) {
      serverArg =
          new Srv(
              (String) spec.get("mark"),
              spec.get("root_path"),
              Boolean.TRUE.equals(spec.get("disable_ping")));
    }
    Object models = args.size() > 2 ? args.get(2) : null;
    final Object serverArgFinal = serverArg;
    Object result =
        captureEp(
            () ->
                PythonServerApp.createApp(
                    state, settings, serverArgFinal, (java.util.List<?>) models, deps));
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("server_settings", mark(state.serverSettings));
    snapshot.put("llama_proxy", mark(state.llamaProxy));
    snapshot.put("ping", mark(state.pingMessageFactory));
    return dict("result", epResult(result), "state", snapshot, "trace", trace);
  }

  // ---- model.py load_llama_from_model_settings -------------------------------

  @SuppressWarnings("unchecked")
  private static Object runLoadModel(Object input) {
    var argument = (Map<String, Object>) input;
    Map<String, Object> settings =
        new LinkedHashMap<>((Map<String, Object>) argument.get("settings"));
    List<Object> calls = new ArrayList<>();
    List<Object> made = new ArrayList<>();
    Map<String, Object> files = (Map<String, Object>) argument.getOrDefault("files", Map.of());
    class FakeModel implements PythonLlamaProxy.LoadedLlama {
      Object chatHandler =
          argument.containsKey("model_chat_handler") ? argument.get("model_chat_handler") : "base";
      final Map<String, Object> chatHandlers = new LinkedHashMap<>();
      Object cache;

      FakeModel() {
        for (Object key :
            ((Map<String, Object>) argument.getOrDefault("model_chat_handlers", Map.of()))
                .keySet()) {
          chatHandlers.put((String) key, "base");
        }
      }

      public Object chatHandler() {
        return chatHandler;
      }

      public void setChatHandler(Object handler) {
        chatHandler = handler;
      }

      public Map<String, Object> chatHandlers() {
        return chatHandlers;
      }

      public Object chatFormat() {
        return argument.getOrDefault("model_chat_format", "chatml");
      }

      public void setCache(Object cacheValue) {
        calls.add(List.of("set_cache", cacheValue));
        cache = cacheValue;
      }
    }
    var deps =
        new PythonLlamaProxy.LoaderDeps() {
          public Object chatHandler(String cls, Object clipModelPath, Object verbose) {
            calls.add(
                List.of(
                    "handler_ctor",
                    cls,
                    dict("clip_model_path", clipModelPath, "verbose", verbose)));
            return dict("handler", cls);
          }

          public Object chatHandlerFromPretrained(
              String cls, Object repoId, Object filename, Object verbose) {
            calls.add(
                List.of(
                    "handler_pretrained",
                    cls,
                    dict("repo_id", repoId, "filename", filename, "verbose", verbose)));
            return dict("handler", cls + ".pretrained");
          }

          public Object hfAutotokenizerHandler(Object nameOrPath) {
            calls.add(List.of("hf_autotokenizer", nameOrPath));
            return dict("handler", "hf_auto");
          }

          public Object readJsonFile(Object path) {
            calls.add(List.of("open", path, "r"));
            return PythonJson.loads((String) files.get(path));
          }

          public Object hfTokenizerConfigHandler(Object config) {
            calls.add(List.of("hf_config", config));
            return dict("handler", "hf_cfg");
          }

          public Object hfTokenizer(Object nameOrPath) {
            calls.add(List.of("hf_tokenizer", nameOrPath));
            return List.of("tok", nameOrPath);
          }

          public Object draftModel(Object numPredTokens) {
            calls.add(List.of("draft", numPredTokens));
            return List.of("draft", numPredTokens);
          }

          public Object llamaFromPretrained(
              Object repoId, Object filename, Map<String, Object> kwargs) {
            calls.add(List.of("llama_pretrained", repoId, filename, kwargs));
            made.add(List.of("from_pretrained", repoId, filename, kwargs));
            return new FakeModel();
          }

          public Object llamaCreate(Map<String, Object> kwargs) {
            calls.add(List.of("llama_ctor", kwargs));
            made.add(List.of("ctor", kwargs));
            return new FakeModel();
          }

          public Object diskCache(Object capacityBytes) {
            calls.add(List.of("disk_cache", capacityBytes));
            return List.of("disk", capacityBytes);
          }

          public Object ramCache(Object capacityBytes) {
            calls.add(List.of("ram_cache", capacityBytes));
            return List.of("ram", capacityBytes);
          }

          public void print(Object message) {}

          public Object callChatHandler(
              Object handler, List<Object> args, Map<String, Object> kwargs) {
            calls.add(List.of("base_handler", args, kwargs));
            return "BASE";
          }

          public Object registryChatHandler(Object chatFormat) {
            calls.add(List.of("registry_handler", chatFormat));
            return dict("handler", "registry:" + chatFormat);
          }
        };
    try {
      var model = PythonLlamaProxy.loadLlamaFromModelSettings(settings, deps);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("model", "ok");
      result.put("calls", calls);
      result.put("made", made);
      if (model.chatHandler() instanceof PythonLlamaProxy.ChatHandlerWithKwargs wrapped) {
        Object inner =
            wrapped.call(
                (List<Object>) argument.getOrDefault("handler_args", List.of()),
                (Map<String, Object>) argument.getOrDefault("handler_kwargs", Map.of()));
        result.put("handler_result", inner);
        result.put("handler_calls", calls);
      }
      return result;
    } catch (PythonException error) {
      return dict(
          "error", error.type().pythonName(), "message", error.getMessage(), "calls", calls);
    }
  }

  // ---- __main__.py main --------------------------------------------------------

  private static final class MainExit extends RuntimeException {
    final int code;

    MainExit(int code) {
      this.code = code;
    }
  }

  private record SrvBox(String kind, Object host, Object port, Object key, Object cert) {}

  @SuppressWarnings("unchecked")
  private static Object runServerMain(Object input) {
    var argument = (Map<String, Object>) input;
    Map<String, Object> env = (Map<String, Object>) argument.getOrDefault("env", Map.of());
    Map<String, Object> files = (Map<String, Object>) argument.getOrDefault("files", Map.of());
    Map<String, Object> namespace =
        new LinkedHashMap<>((Map<String, Object>) argument.getOrDefault("args", Map.of()));
    namespace.putIfAbsent("config_file", null);
    List<Object> trace = new ArrayList<>();
    StringBuilder stderr = new StringBuilder();
    final Object[] validated = {null};
    var deps =
        new PythonServerCli.MainDeps() {
          public PythonServerCli.MainParser parser(String description) {
            trace.add(List.of("ArgumentParser", description));
            return new PythonServerCli.MainParser() {
              public void addArgument(
                  String flag, String dest, Object nargs, Object type, Object help) {
                trace.add(List.of("add_argument", flag, type, help));
              }

              public Map<String, Object> parseArgs() {
                trace.add(List.of("parse_args"));
                return namespace;
              }

              public void printHelp() {
                trace.add(List.of("print_help"));
              }
            };
          }

          public PythonServerCli.Model settingsModel() {
            return new PythonServerCli.Model() {
              public java.util.List<PythonServerCli.ModelField> modelFields() {
                return List.of(
                    new Field("a", new Ann(null, "str", List.of()), "an opt", "d0", false),
                    new Field(
                        "b",
                        new Ann("list", null, List.of(new Ann(null, "int", List.of()))),
                        "many",
                        null,
                        false),
                    new Field("c", new Ann(null, "bool", List.of()), "flag", null, false));
              }

              public Object construct(Map<String, Object> kwargs) {
                return kwargs;
              }
            };
          }

          public PythonServerCli.Model serverSettingsModel() {
            return parseModel("ServerSettings");
          }

          public PythonServerCli.Model modelSettingsModel() {
            return parseModel("ModelSettings");
          }

          private PythonServerCli.Model parseModel(String name) {
            return new PythonServerCli.Model() {
              public java.util.List<PythonServerCli.ModelField> modelFields() {
                return List.of();
              }

              public Object construct(Map<String, Object> kwargs) {
                trace.add(List.of("parse_model", name));
                return new SrvBox("parsed", "h", 1L, null, null);
              }
            };
          }

          public String envGet(String name) {
            Object value = env.get(name);
            return value instanceof String text ? text : null;
          }

          public boolean pathExists(String path) {
            trace.add(List.of("exists", path));
            return files.containsKey(path);
          }

          public Object configValidateJson(String json) {
            trace.add(List.of("config_validate_json", json));
            if (Boolean.TRUE.equals(argument.get("config_fail"))) {
              throw Py.error(PythonErrorType.VALUE_ERROR, "bad config");
            }
            Map<String, Object> spec =
                (Map<String, Object>) argument.getOrDefault("config_result", Map.of());
            return spec.getOrDefault("models", List.of("m1"));
          }

          public Object yamlSafeLoad(String path) {
            trace.add(List.of("open", path, "rb"));
            Object content = files.get(path);
            if (content instanceof byte[] bytes) {
              return PythonJson.loads(new String(bytes, StandardCharsets.UTF_8));
            }
            return PythonJson.loads((String) content);
          }

          public String openRead(String path) {
            trace.add(List.of("open", path, "rb"));
            Object content = files.get(path);
            return content instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : (String) content;
          }

          public Object serverSettingsValidate(Object configFileSettings) {
            trace.add(List.of("server_validate", configFileSettings));
            var box =
                new SrvBox(
                    "validated",
                    argument.getOrDefault("host", "0.0.0.0"),
                    argument.getOrDefault("port", 8000L),
                    argument.get("key"),
                    argument.get("cert"));
            validated[0] = box;
            return box;
          }

          public List<Object> configModels(Object configFileSettings) {
            return (List<Object>) configFileSettings;
          }

          public void printErr(String message) {
            stderr.append(message).append('\n');
          }

          public void exit(int code) {
            trace.add(List.of("exit", code));
            throw new MainExit(code);
          }

          public Object createApp(Object serverSettings, List<Object> modelSettings) {
            trace.add(
                List.of(
                    "create_app",
                    serverSettings == validated[0] ? "validated" : "parsed",
                    modelSettings.size()));
            return "app";
          }

          public Object serverHost(Object serverSettings) {
            return ((SrvBox) serverSettings).host();
          }

          public Object serverPort(Object serverSettings) {
            return ((SrvBox) serverSettings).port();
          }

          public Object sslKeyfile(Object serverSettings) {
            return ((SrvBox) serverSettings).key();
          }

          public Object sslCertfile(Object serverSettings) {
            return ((SrvBox) serverSettings).cert();
          }

          public void uvicornRun(
              Object app, Object host, int port, Object sslKeyfile, Object sslCertfile) {
            trace.add(
                List.of(
                    "uvicorn",
                    app,
                    dict(
                        "host", host,
                        "port", (long) port,
                        "ssl_keyfile", sslKeyfile,
                        "ssl_certfile", sslCertfile)));
          }
        };
    try {
      PythonServerCli.main(deps);
      return dict("result", "ok", "trace", trace, "stderr", stderr.toString());
    } catch (MainExit exit) {
      return dict("result", "exit", "trace", trace, "stderr", stderr.toString());
    } catch (Exception error) {
      return dict(
          "error",
          error instanceof PythonException failure ? failure.type().pythonName() : "Error",
          "message",
          error.getMessage(),
          "trace",
          trace,
          "stderr",
          stderr.toString());
    }
  }
}
