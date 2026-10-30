package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.yaulun.javacpp.python.PythonException;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonCompletionContractTest {
  @TestFactory
  Stream<DynamicTest> originalCompletionBody() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-completion-contracts.tsv"),
            "Run generate_completion_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "completion:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object expected = read(parts[1]);
                      Object actual = run(map(read(parts[0])));
                      compare(expected, actual, "case:" + i);
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

  private static int n(Object value) {
    return ((Number) value).intValue();
  }

  private static int[] ids(Object value) {
    return ((List<?>) value).stream().mapToInt(PythonCompletionContractTest::n).toArray();
  }

  private static Object capture(Supplier<?> call) {
    try {
      return call.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  private static Object run(Map<String, Object> arg) {
    List<Object> trace = new ArrayList<>(), warnings = new ArrayList<>();
    int capacity = n(arg.get("capacity"));
    PythonLlama llama =
        new PythonLlama(
            new int[capacity],
            new float[capacity][260],
            0,
            capacity,
            (boolean) arg.get("all"),
            false,
            false,
            () -> null);
    llama.setSeed(new BigInteger((String) arg.get("initialSeed")));
    int[][] current = {new int[0]};
    int[] counter = {0};
    int[] sampled = ids(arg.get("sampled"));
    llama.setExecution(
        new PythonLlamaExecution() {
          public int batchSize() {
            return 4;
          }

          public boolean removeSequence(int position) {
            trace.add(List.of("remove", position));
            return true;
          }

          public void decode(int[] tokens, int position, boolean all) {
            current[0] = tokens;
            trace.add(List.of("decode", Arrays.stream(tokens).boxed().toList(), position));
          }

          public float[][] logits(int rows, int columns) {
            float[][] values = new float[rows][columns];
            for (int i = 0; i < rows; i++)
              for (int j = 0; j < columns; j++)
                values[i][j] = (float) (current[0][i] % 7 - 3 + j * 0.03);
            return values;
          }

          public long stateSize() {
            throw new AssertionError();
          }

          public long copyState(byte[] value) {
            throw new AssertionError();
          }

          public long restoreState(byte[] value) {
            throw new AssertionError();
          }
        });
    llama.setTokenizer(
        new PythonTokenizer() {
          public int[] tokenize(byte[] text, boolean bos, boolean special) {
            trace.add(List.of("tokenize", HexFormat.of().formatHex(text), bos, special));
            int[] result = new int[text.length + (bos ? 1 : 0)];
            for (int i = 0; i < text.length; i++)
              result[i + (bos ? 1 : 0)] = Byte.toUnsignedInt(text[i]) + 1;
            return result;
          }

          public byte[] detokenize(int[] tokens, int[] previous, boolean special) {
            if (truth(arg.get("traceDetok")))
              trace.add(
                  Arrays.asList(
                      "detokenize",
                      Arrays.stream(tokens).boxed().toList(),
                      previous == null ? null : Arrays.stream(previous).boxed().toList()));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int token : tokens) if (token >= 1 && token <= 256) bytes.write(token - 1);
            return bytes.toByteArray();
          }
        });
    llama.setSamplingBackend(
        () -> {
          trace.add(List.of("sampler", llama.seed().toString()));
          return new PythonSamplerBuilder() {
            private Consumer<float[]> processor;

            @SuppressWarnings("unchecked")
            public void add(String operation, Object... args) {
              if (operation.equals("custom")) processor = (Consumer<float[]>) args[0];
            }

            public PythonLlamaSampler build() {
              return index -> {
                trace.add(List.of("sample", index));
                if (processor != null) {
                  int token = current[0][index < 0 ? current[0].length + index : index];
                  float[] values = new float[260];
                  for (int j = 0; j < 260; j++) values[j] = (float) (token % 7 - 3 + j * 0.03);
                  processor.accept(values);
                }
                return sampled[counter[0]++ % sampled.length];
              };
            }
          };
        });
    var services = llama.services();
    services.setCompletionModel(
        new PythonCompletionModel() {
          public int bos() {
            return 0;
          }

          public int eos() {
            return 257;
          }

          public int cls() {
            return n(arg.get("cls"));
          }

          public int sep() {
            return n(arg.get("sep"));
          }

          public boolean addBos() {
            return (boolean) arg.get("bos");
          }

          public boolean addEos() {
            return (boolean) arg.get("eos");
          }

          public boolean isEog(int token) {
            return token == 257;
          }

          public String tokenText(int token) {
            return "<s>";
          }

          public String metadata(String key, String fallback) {
            return truth(arg.get("space")) ? "true" : "false";
          }

          public int contextLength() {
            return capacity;
          }

          public String modelPath() {
            return "model.gguf";
          }

          public void resetTimings() {
            trace.add(List.of("resetTimings"));
          }

          public void printTimings() {
            trace.add(List.of("printTimings"));
          }
        });
    services.setSpmInfill((boolean) arg.get("infill"));
    services.setVerbose((boolean) arg.get("verbose"));
    services.setCompletionIdentity(() -> "cmpl-fixed", () -> 1234);
    services.setWarnings(
        PythonWarnings.withAction(
            PythonWarningAction.ALWAYS,
            value -> warnings.add(List.of(value.category().pythonName(), value.message()))));
    Map<String, Object> options = new LinkedHashMap<>(map(arg.get("options")));
    if (arg.get("stopAfter") != null) {
      PythonStoppingCriteriaList stopping = new PythonStoppingCriteriaList();
      stopping.add(
          (tokens, scores) -> {
            trace.add(List.of("stop", tokens.length));
            return tokens.length >= n(arg.get("stopAfter"));
          });
      options.put("stopping_criteria", stopping);
    }
    List<Object> chunks = new ArrayList<>();
    Object result =
        capture(
            () -> {
              Object value = llama.createCompletion(options);
              if (value instanceof Iterator<?> iterator) {
                while (iterator.hasNext()) chunks.add(iterator.next());
                return chunks;
              }
              return value;
            });
    return dict(
        "result",
        result,
        "chunks",
        chunks,
        "trace",
        trace,
        "seed",
        llama.seed().toString(),
        "cursor",
        llama.nTokens(),
        "warnings",
        warnings);
  }

  private static void compare(Object expected, Object actual, String path) {
    if (expected instanceof Number a && actual instanceof Number b) {
      double x = a.doubleValue(), y = b.doubleValue();
      if (Double.isFinite(x)) assertEquals(x, y, 4e-6 * Math.max(1, Math.abs(x)), path);
      else assertEquals(x, y, path);
    } else if (expected instanceof List<?> a && actual instanceof List<?> b) {
      assertEquals(a.size(), b.size(), path + " expected=" + expected + " actual=" + actual);
      for (int i = 0; i < a.size(); i++) compare(a.get(i), b.get(i), path + "[" + i + "]");
    } else if (expected instanceof Map<?, ?> a && actual instanceof Map<?, ?> b) {
      assertEquals(
          new ArrayList<>(a.keySet()),
          new ArrayList<>(b.keySet()),
          path + " expected=" + expected + " actual=" + actual);
      for (Object key : a.keySet()) compare(a.get(key), b.get(key), path + "." + key);
    } else assertEquals(expected, actual, path);
  }
}
