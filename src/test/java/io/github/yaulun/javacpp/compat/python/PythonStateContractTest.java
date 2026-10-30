package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class PythonStateContractTest {
  @TestFactory
  Stream<DynamicTest> matchesExecutedPythonStateAndProcessorFunctions() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-state-contracts.tsv"),
            "Generate ignored fixtures with tools/python_compat/generate_state_contracts.py --source-root .tools/python-api-audit")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index -> {
              String[] parts = lines.get(index).split("\t");
              return DynamicTest.dynamicTest(
                  (index + 1) + ":" + parts[0],
                  () -> {
                    Object argument =
                        PythonContractData.read(
                            new DataInputStream(
                                new ByteArrayInputStream(Base64.getDecoder().decode(parts[1]))));
                    Object result = capture(() -> execute(parts[0], argument));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    PythonContractData.write(new DataOutputStream(bytes), result);
                    assertEquals(
                        parts[2],
                        Base64.getEncoder().encodeToString(bytes.toByteArray()),
                        "operation=" + parts[0] + ", argument=" + argument + ", result=" + result);
                  });
            });
  }

  private static Object capture(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static Object execute(String kind, Object argument) {
    if (kind.equals("prefix")) {
      List<?> values = (List<?>) argument;
      return PythonTokens.longestTokenPrefix(key(values.get(0)), key(values.get(1)));
    }
    if (kind.equals("processors") || kind.equals("stopping"))
      return callbacks((List<Map<String, Object>>) argument, kind.equals("stopping"));
    Map<String, Object> values = (Map<String, Object>) argument;
    if (kind.equals("cache")) return cache(values);
    if (kind.equals("minTokens")) return minTokens(values);
    if (kind.equals("state")) return state(values);
    throw new AssertionError(kind);
  }

  private static List<Object> key(Object value) {
    return ((List<?>) value)
        .stream()
        .map(token -> token == null ? null : (Object) new BigInteger((String) token))
        .toList();
  }

  private static Object keyStrings(List<?> key) {
    return key == null
        ? null
        : key.stream().map(token -> token == null ? null : token.toString()).toList();
  }

  private static int number(Object value) {
    return ((Number) value).intValue();
  }

  private static int[] ids(Object value) {
    return value == null
        ? null
        : ((List<?>) value).stream().mapToInt(PythonStateContractTest::number).toArray();
  }

  private static float[] scores(Object value) {
    if (value == null) return null;
    List<?> values = (List<?>) value;
    float[] result = new float[values.size()];
    for (int i = 0; i < result.length; i++) result[i] = ((Number) values.get(i)).floatValue();
    return result;
  }

  private static Object scoreValues(float[] values) {
    if (values == null) return null;
    List<Object> result = new ArrayList<>();
    for (float value : values) result.add((double) value);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Object cache(Map<String, Object> argument) {
    PythonLlamaRamCache cache =
        new PythonLlamaRamCache(new BigInteger((String) argument.get("capacity")));
    List<PythonLlamaState> states = new ArrayList<>();
    for (Object size : (List<?>) argument.get("sizes"))
      states.add(
          "nullState".equals(size)
              ? null
              : new PythonLlamaState(
                  new int[0],
                  new float[0][],
                  0,
                  new byte[0],
                  size == null ? null : new BigInteger((String) size),
                  0));
    List<Object> events = new ArrayList<>();
    for (Object value : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) value;
      Object result =
          capture(
              () -> {
                switch ((String) step.get(0)) {
                  case "put" -> cache.put(key(step.get(1)), states.get(number(step.get(2))));
                  case "get" -> {
                    Object found = cache.get(key(step.get(1)));
                    return found == null ? null : states.indexOf(found);
                  }
                  case "contains" -> {
                    return cache.contains(key(step.get(1)));
                  }
                  case "find" -> {
                    return keyStrings(cache.findLongestPrefixKey(key(step.get(1))));
                  }
                  case "capacity" -> cache.setCapacityBytes(new BigInteger((String) step.get(1)));
                  case "size" ->
                      states
                          .get(number(step.get(1)))
                          .setLlamaStateSize(
                              step.get(2) == null ? null : new BigInteger((String) step.get(2)));
                  default -> throw new AssertionError(step);
                }
                return null;
              });
      events.add(
          dict(
              "result",
              result,
              "order",
              cache.cacheState().keySet().stream()
                  .map(PythonStateContractTest::keyStrings)
                  .toList(),
              "size",
              capture(() -> cache.cacheSize().toString())));
    }
    return events;
  }

  @SuppressWarnings("unchecked")
  private static Object minTokens(Map<String, Object> argument) {
    var processor =
        new PythonMinTokensLogitsProcessor(
            new BigInteger((String) argument.get("minimum")), number(argument.get("eos")));
    List<Object> events = new ArrayList<>();
    for (Object value : (List<?>) argument.get("steps")) {
      Map<String, Object> step = (Map<String, Object>) value;
      if (step.containsKey("prompt"))
        processor.setPromptTokens(step.get("prompt") == null ? null : number(step.get("prompt")));
      int[] inputIds = ids(step.get("ids"));
      float[] inputScores = scores(step.get("scores"));
      Object result =
          capture(
              () -> {
                float[] found = processor.apply(inputIds, inputScores);
                return dict("scores", scoreValues(found), "sameScores", found == inputScores);
              });
      events.add(dict("result", result, "promptTokens", processor.promptTokens()));
    }
    return events;
  }

  private static Object state(Map<String, Object> argument) {
    int[] inputIds = ids(argument.get("ids"));
    float[][] inputScores =
        argument.get("scores") == null
            ? null
            : ((List<?>) argument.get("scores"))
                .stream().map(PythonStateContractTest::scores).toArray(float[][]::new);
    byte[] data =
        argument.get("data") == null
            ? null
            : HexFormat.of().parseHex((String) argument.get("data"));
    var state =
        new PythonLlamaState(
            inputIds,
            inputScores,
            number(argument.get("nTokens")),
            data,
            new BigInteger((String) argument.get("size")),
            ((Number) argument.get("seed")).longValue());
    return dict(
        "ids",
        state.inputIds() == null ? null : IntStream.of(state.inputIds()).boxed().toList(),
        "scores",
        state.scores() == null
            ? null
            : Stream.of(state.scores()).map(PythonStateContractTest::scoreValues).toList(),
        "nTokens",
        state.nTokens(),
        "data",
        state.llamaState() == null ? null : HexFormat.of().formatHex(state.llamaState()),
        "size",
        state.llamaStateSize().toString(),
        "seed",
        state.seed(),
        "idsSame",
        state.inputIds() == inputIds,
        "scoresSame",
        state.scores() == inputScores,
        "dataSame",
        state.llamaState() == data);
  }

  private static Object callbacks(List<Map<String, Object>> specs, boolean stopping) {
    List<Object> trace = new ArrayList<>();
    int[] ids = {1, 2};
    float[] initial = {0.5f, -2.0f, 1.25f};
    var processors = new PythonLogitsProcessorList();
    var criteria = new PythonStoppingCriteriaList();
    class Callbacks {
      PythonLogitsProcessor processor(Map<String, Object> spec, int index) {
        if (spec.get("kind").equals("null")) return null;
        return new PythonLogitsProcessor() {
          @Override
          public float[] apply(int[] inputIds, float[] scores) {
            trace.add(
                dict("index", index, "idsSame", inputIds == ids, "scoresSame", scores == initial));
            String kind = (String) spec.get("kind");
            if (kind.equals("raise"))
              throw PythonValues.error(PythonErrorType.VALUE_ERROR, "callback failure");
            if (kind.equals("append")) processors.add(processor(dict("kind", "identity"), 99));
            if (kind.equals("remove")) processors.remove(this);
            if (kind.equals("replace")) return new float[] {3, 4};
            if (kind.equals("none")) return null;
            if (kind.equals("add")) for (int i = 0; i < scores.length; i++) scores[i] += 0.5f;
            if (kind.equals("scale")) for (int i = 0; i < scores.length; i++) scores[i] *= 2f;
            return scores;
          }
        };
      }

      PythonStoppingCriteria criterion(Map<String, Object> spec, int index) {
        if (spec.get("kind").equals("null")) return null;
        return new PythonStoppingCriteria() {
          @Override
          public Object test(int[] inputIds, float[] scores) {
            trace.add(
                dict("index", index, "idsSame", inputIds == ids, "scoresSame", scores == initial));
            String kind = (String) spec.get("kind");
            if (kind.equals("raise"))
              throw PythonValues.error(PythonErrorType.VALUE_ERROR, "callback failure");
            if (kind.equals("append"))
              criteria.add(criterion(dict("kind", "identity", "value", true), 99));
            if (kind.equals("remove")) criteria.remove(this);
            if (kind.equals("clearResult"))
              ((List<?>) specs.get(number(spec.get("target"))).get("value")).clear();
            return spec.getOrDefault("value", false);
          }
        };
      }
    }
    Callbacks factory = new Callbacks();
    for (int i = 0; i < specs.size(); i++) {
      if (stopping) criteria.add(factory.criterion(specs.get(i), i));
      else processors.add(factory.processor(specs.get(i), i));
    }
    Object result =
        capture(
            () -> {
              if (stopping) return criteria.test(ids, initial);
              float[] found = processors.apply(ids, initial);
              return dict("scores", scoreValues(found), "sameScores", found == initial);
            });
    return dict(
        "result", result, "trace", trace, "count", stopping ? criteria.size() : processors.size());
  }

  @Test
  void defaultCapacityAndMutableStateReferencesMatchPython() {
    assertEquals(BigInteger.ONE.shiftLeft(31), new PythonLlamaRamCache().capacityBytes());
    int[] ids = {1};
    float[][] scores = {{0.5f}};
    byte[] data = {1};
    var state = new PythonLlamaState(ids, scores, 1, data, BigInteger.ONE, 2);
    var cache = new PythonLlamaRamCache(BigInteger.TEN);
    cache.put(List.<Object>of(BigInteger.ONE), state);
    assertSame(ids, state.inputIds());
    assertSame(scores, state.scores());
    assertSame(data, state.llamaState());
    state.setLlamaStateSize(BigInteger.valueOf(20));
    assertEquals(BigInteger.valueOf(20), cache.cacheSize());
    assertSame(state, cache.get(List.<Object>of(BigInteger.ONE)));
    assertEquals(BigInteger.valueOf(20), cache.cacheSize());
    cache.put(List.<Object>of(BigInteger.ONE), state);
    assertTrue(cache.cacheState().isEmpty());
  }
}
