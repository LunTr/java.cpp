package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonExecutionContractTest {
  @TestFactory
  Stream<DynamicTest> originalExecutionBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-execution-contracts.tsv"),
            "Run generate_execution_contracts.py --source-root .tools/python-api-audit")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "execution:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object actual = run(map(read(parts[0])));
                      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(bytes), actual);
                      assertEquals(
                          parts[1],
                          Base64.getEncoder().encodeToString(bytes.toByteArray()),
                          () -> "expected=" + uncheckedRead(parts[1]) + " actual=" + actual);
                    }));
  }

  private static Object uncheckedRead(String value) {
    try {
      return read(value);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static Object read(String value) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static int n(Object value) {
    return ((Number) value).intValue();
  }

  private static int[] ids(Object value) {
    return ((List<?>) value).stream().mapToInt(PythonExecutionContractTest::n).toArray();
  }

  private static float[][] scores(Object value) {
    return ((List<?>) value)
        .stream()
            .map(
                row -> {
                  List<?> values = (List<?>) row;
                  float[] result = new float[values.size()];
                  for (int i = 0; i < result.length; i++)
                    result[i] = ((Number) values.get(i)).floatValue();
                  return result;
                })
            .toArray(float[][]::new);
  }

  private static List<List<Float>> values(float[][] scores) {
    return Stream.of(scores)
        .map(
            row -> {
              List<Float> result = new ArrayList<>();
              for (float v : row) result.add(v);
              return result;
            })
        .toList();
  }

  private static List<Integer> values(int[] ids) {
    return Arrays.stream(ids).boxed().toList();
  }

  private static Object capture(Supplier<?> call) {
    try {
      return call.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  private static Object run(Map<String, Object> arg) {
    List<Object> trace = new ArrayList<>();
    List<String> logs = new ArrayList<>();
    int[] input = ids(arg.get("ids"));
    float[][] scores = scores(arg.get("scores"));
    String failure = (String) arg.get("failure");
    int[] decodedHolder[] = new int[1][];
    int[] sampled = {0};
    int[] sampledTokens = ids(arg.get("sampled"));
    PythonLlamaExecution backend =
        new PythonLlamaExecution() {
          public int batchSize() {
            return n(arg.get("batch"));
          }

          public boolean removeSequence(int position) {
            trace.add(List.of("remove", position));
            return (boolean) arg.get("remove");
          }

          public void decode(int[] batch, int position, boolean all) {
            decodedHolder[0] = batch;
            trace.add(List.of("decode", values(batch), position, all));
            if (failure.equals("decode"))
              throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "llama_decode returned -7");
          }

          public float[][] logits(int rows, int columns) {
            trace.add(List.of("logits"));
            if (failure.equals("logits"))
              throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "logits unavailable");
            float[][] result = new float[rows][columns];
            for (int row = 0; row < rows; row++)
              for (int col = 0; col < columns; col++)
                result[row][col] = decodedHolder[0][row] + col / 4f;
            return result;
          }

          public long stateSize() {
            trace.add(List.of("stateSize"));
            return n(arg.get("size"));
          }

          public long copyState(byte[] target) {
            trace.add(List.of("copy", target.length));
            for (int i = 0; i < target.length; i++) target[i] = (byte) (i + 1);
            return n(arg.get("count"));
          }

          public long restoreState(byte[] source) {
            trace.add(List.of("restore", HexFormat.of().formatHex(source)));
            return n(arg.get("count"));
          }
        };
    PythonLlama llama =
        new PythonLlama(
            input,
            scores,
            n(arg.get("cursor")),
            input.length,
            (boolean) arg.get("all"),
            false,
            false,
            () -> null);
    llama.setExecution(backend);
    llama.setDiagnostics(logs::add);
    llama.setSeed(new BigInteger((String) arg.get("seed")));
    llama.setCloseAction(
        () -> {
          trace.add(List.of("close"));
          if (failure.equals("close"))
            throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "close failed");
        });
    llama.setSamplerFactory(
        () -> {
          trace.add(List.of("factory"));
          return index -> {
            trace.add(List.of("sample", index));
            if (failure.equals("sample"))
              throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "sample failed");
            return sampledTokens[sampled[0]++ % sampledTokens.length];
          };
        });
    Object result;
    switch ((String) arg.get("kind")) {
      case "close" -> {
        List<Object> events = new ArrayList<>();
        for (int i = 0; i < 2; i++)
          events.add(
              capture(
                  () -> {
                    llama.close();
                    return null;
                  }));
        result = events;
      }
      case "eval" ->
          result =
              capture(
                  () -> {
                    llama.eval(ids(arg.get("tokens")));
                    return null;
                  });
      case "sample" -> {
        List<Object> events = new ArrayList<>();
        for (int i = 0; i < 2; i++)
          events.add(
              capture(() -> llama.sample(arg.get("index") == null ? null : n(arg.get("index")))));
        result = events;
      }
      case "save" ->
          result =
              capture(
                  () -> {
                    PythonLlamaState saved = llama.saveState();
                    return dict(
                        "ids",
                        values(saved.inputIds()),
                        "scores",
                        values(saved.scores()),
                        "cursor",
                        saved.nTokens(),
                        "data",
                        HexFormat.of().formatHex(saved.llamaState()),
                        "size",
                        saved.llamaStateSize().toString(),
                        "seed",
                        saved.seedValue().toString());
                  });
      case "load" ->
          result =
              capture(
                  () -> {
                    llama.loadState(
                        new PythonLlamaState(
                            ids(arg.get("savedIds")),
                            scores(arg.get("savedScores")),
                            n(arg.get("savedCursor")),
                            HexFormat.of().parseHex((String) arg.get("data")),
                            BigInteger.valueOf(n(arg.get("size"))),
                            new BigInteger((String) arg.get("seed"))));
                    return null;
                  });
      case "generate" -> {
        List<Object> events = new ArrayList<>();
        try (var gen =
            llama.generate(
                ids(arg.get("tokens")),
                (boolean) arg.get("reset"),
                stopping(arg, trace),
                arg.get("draft") == null
                    ? null
                    : draftIds -> {
                      trace.add(List.of("draft", values(draftIds)));
                      if (failure.equals("draft"))
                        throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "draft failed");
                      return ids(arg.get("draft"));
                    })) {
          for (Object sent : (List<?>) arg.get("sends"))
            events.add(capture(() -> gen.send(sent == null ? null : ids(sent))));
        }
        result = events;
      }
      default -> throw new AssertionError();
    }
    return dict(
        "result",
        result,
        "trace",
        trace,
        "logs",
        logs,
        "ids",
        values(llama.inputIds().array()),
        "scores",
        values(scores),
        "cursor",
        llama.nTokens(),
        "requiresEval",
        llama.requiresEval(),
        "seed",
        llama.seed().toString(),
        "sampler",
        llama.sampler() != null);
  }

  private static PythonStoppingCriteriaList stopping(Map<String, Object> arg, List<Object> trace) {
    if (arg.get("stopAfter") == null) return null;
    PythonStoppingCriteriaList result = new PythonStoppingCriteriaList();
    result.add(
        (ids, scores) -> {
          List<Float> row = new ArrayList<>();
          for (float value : scores) row.add(value);
          trace.add(List.of("stop", values(ids), row));
          if (arg.get("failure").equals("stop"))
            throw PythonValues.error(PythonErrorType.RUNTIME_ERROR, "stop failed");
          return ids.length >= n(arg.get("stopAfter"));
        });
    return result;
  }
}
