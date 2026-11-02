package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
