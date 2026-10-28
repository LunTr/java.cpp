package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.math.BigInteger;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class PythonLlamaContractTest {
  @TestFactory
  Stream<DynamicTest> matchesExecutedPythonLlamaMethods() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-llama-contracts.tsv"),
            "Generate fixtures with tools/python_compat/generate_llama_contracts.py --source-root .tools/python-api-audit")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index -> {
              String[] parts = lines.get(index).split("\t");
              return DynamicTest.dynamicTest(
                  (index + 1) + ":" + parts[0],
                  () -> {
                    Map<String, Object> argument = map(read(parts[1]));
                    Object expected = read(parts[2]);
                    Object actual = capture(() -> execute(parts[0], argument));
                    compare(
                        expected,
                        actual,
                        parts[0],
                        parts[0].equals("vector") || parts[0].equals("matrix"));
                  });
            });
  }

  private static Object read(String value) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static int number(Object value) {
    return ((Number) value).intValue();
  }

  private static float[] vector(Object value) {
    List<?> items = (List<?>) value;
    float[] result = new float[items.size()];
    for (int i = 0; i < result.length; i++) result[i] = ((Number) items.get(i)).floatValue();
    return result;
  }

  private static float[][] matrix(Object value) {
    return ((List<?>) value).stream().map(PythonLlamaContractTest::vector).toArray(float[][]::new);
  }

  private static List<Float> floats(float[] values) {
    List<Float> result = new ArrayList<>();
    for (float value : values) result.add(value);
    return result;
  }

  private static List<List<Float>> floats(float[][] values) {
    return Stream.of(values).map(PythonLlamaContractTest::floats).toList();
  }

  private static Object capture(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  private static Object execute(String kind, Map<String, Object> argument) {
    return switch (kind) {
      case "views" -> views(argument);
      case "settings" -> settings(argument);
      case "reset" -> reset(argument);
      case "vector" -> floats(PythonLlama.logitsToLogprobs(vector(argument.get("values"))));
      case "matrix" ->
          floats(
              PythonLlama.logitsToLogprobs(
                  matrix(argument.get("values")), number(argument.get("axis"))));
      default -> throw new AssertionError(kind);
    };
  }

  private static Object views(Map<String, Object> arg) {
    int[] ids =
        ((List<?>) arg.get("ids")).stream().mapToInt(PythonLlamaContractTest::number).toArray();
    float[][] scores = matrix(arg.get("scores"));
    PythonLlama llama =
        new PythonLlama(
            ids,
            scores,
            number(arg.get("cursor")),
            number(arg.get("context")),
            (boolean) arg.get("all"),
            false,
            false,
            () -> {
              throw new AssertionError("Unexpected memory query");
            });
    IntBuffer idsView = llama.inputIds();
    List<FloatBuffer> scoresView = llama.scores();
    Object tokens = capture(llama::evalTokens);
    Object logits = capture(llama::evalLogits);
    if (idsView.hasRemaining()) idsView.put(0, 123);
    if (!scoresView.isEmpty() && scoresView.getFirst().hasRemaining())
      scoresView.getFirst().put(0, 12.5f);
    ids[ids.length - 1] = -456;
    scores[scores.length - 1][scores[0].length - 1] = -25.25f;
    List<List<Float>> viewedScores =
        scoresView.stream()
            .map(
                row -> {
                  List<Float> values = new ArrayList<>();
                  for (int i = 0; i < row.limit(); i++) values.add(row.get(i));
                  return values;
                })
            .toList();
    return dict(
        "idsView",
        IntStream.range(0, idsView.limit()).map(idsView::get).boxed().toList(),
        "scoresView",
        viewedScores,
        "tokens",
        tokens,
        "logits",
        logits,
        "ids",
        IntStream.of(ids).boxed().toList(),
        "scores",
        floats(scores));
  }

  private static PythonLlama emptyLlama() {
    return new PythonLlama(new int[0], new float[0][], 3, 4, false, false, false, () -> null);
  }

  private static Object settings(Map<String, Object> arg) {
    PythonLlama llama = emptyLlama();
    PythonLlamaRamCache cache = new PythonLlamaRamCache();
    List<Object> events = new ArrayList<>();
    List<?> seeds = (List<?>) arg.get("seeds");
    for (int i = 0; i < seeds.size(); i++) {
      llama.setSeed(new BigInteger((String) seeds.get(i)));
      llama.setCache(i == 1 ? null : cache);
      events.add(
          dict(
              "seed",
              llama.seed().toString(),
              "sameCache",
              llama.cache() == cache,
              "nullCache",
              llama.cache() == null,
              "cursor",
              llama.nTokens(),
              "requiresEval",
              llama.requiresEval()));
    }
    return events;
  }

  private static Object reset(Map<String, Object> arg) {
    List<Object> trace = new ArrayList<>();
    PythonLlama[] holder = new PythonLlama[1];
    String mode = (String) arg.get("memory");
    Supplier<Runnable> memory =
        () -> {
          PythonLlama llama = holder[0];
          trace.add(List.of("get", true, llama.nTokens(), llama.requiresEval()));
          if (mode.equals("getFail"))
            throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "get memory failed");
          if (mode.equals("none")) return null;
          return () -> {
            trace.add(List.of("clear", true, true, llama.nTokens(), llama.requiresEval()));
            if (mode.equals("clearFail"))
              throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "clear memory failed");
          };
        };
    int[] ids = {1, 2};
    float[][] scores = {{3.0f}};
    PythonLlama llama =
        new PythonLlama(
            ids,
            scores,
            number(arg.get("cursor")),
            4,
            false,
            (boolean) arg.get("recurrent"),
            (boolean) arg.get("hybrid"),
            memory);
    holder[0] = llama;
    llama.setSeed(BigInteger.valueOf(99));
    PythonLlamaRamCache cache = new PythonLlamaRamCache();
    llama.setCache(cache);
    List<Object> results = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Object result =
          capture(
              () -> {
                llama.reset();
                return null;
              });
      results.add(
          dict("result", result, "cursor", llama.nTokens(), "requiresEval", llama.requiresEval()));
    }
    return dict(
        "results",
        results,
        "trace",
        trace,
        "ids",
        IntStream.of(ids).boxed().toList(),
        "scores",
        floats(scores),
        "seed",
        llama.seed().toString(),
        "sameCache",
        llama.cache() == cache);
  }

  private static void compare(Object expected, Object actual, String path, boolean approximate) {
    if (expected instanceof Number left && actual instanceof Number right) {
      double a = left.doubleValue(), b = right.doubleValue();
      if (approximate && Double.isFinite(a)) assertEquals(a, b, 4e-6 + 4e-6 * Math.abs(a), path);
      else assertEquals(a, b, path);
    } else if (expected instanceof List<?> left && actual instanceof List<?> right) {
      assertEquals(left.size(), right.size(), path);
      for (int i = 0; i < left.size(); i++)
        compare(left.get(i), right.get(i), path + "[" + i + "]", approximate);
    } else if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
      assertEquals(left.keySet(), right.keySet(), path);
      for (Object key : left.keySet())
        compare(left.get(key), right.get(key), path + "." + key, approximate);
    } else assertEquals(expected, actual, path);
  }

  @Test
  void emptyStateViewsAndNumericalInputOwnership() {
    PythonLlama llama = emptyLlama();
    assertEquals(0, llama.inputIds().remaining());
    assertTrue(llama.scores().isEmpty());
    assertTrue(llama.evalTokens().isEmpty());
    assertTrue(llama.evalLogits().isEmpty());
    llama.reset();
    assertTrue(llama.requiresEval());
    llama.setEvaluatedTokens(2);
    assertEquals(2, llama.nTokens());
    assertFalse(llama.requiresEval());
    float[][] values = {{1, 2}, {3, 4}};
    float[][] result = PythonLlama.logitsToLogprobs(values, 0);
    assertArrayEquals(new float[] {1, 2}, values[0]);
    assertNotSame(values[0], result[0]);
    assertThrows(IllegalArgumentException.class, () -> PythonLlama.logitsToLogprobs(values, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> PythonLlama.logitsToLogprobs(new float[][] {{1}, {2, 3}}, 0));
  }
}
