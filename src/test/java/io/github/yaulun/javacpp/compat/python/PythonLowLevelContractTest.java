package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.yaulun.javacpp.LlamaContext;
import io.github.yaulun.javacpp.LlamaModel;
import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PyAttributed;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
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
import sun.misc.Unsafe;

class PythonLowLevelContractTest {
  @TestFactory
  Stream<DynamicTest> matchesPinnedLowLevelBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-lowlevel-contracts.tsv"),
            "Run generate_lowlevel_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index ->
                DynamicTest.dynamicTest(
                    "lowlevel:" + index,
                    () -> {
                      String[] parts = lines.get(index).split("\t");
                      Object argument = read(parts[1]);
                      Object actual =
                          switch (parts[0]) {
                            case "log" -> runLog(argument);
                            case "warn" -> runWarn(argument);
                            case "lora" -> runLora(argument);
                            case "dunder" -> runDunder(argument);
                            case "tokenizer" -> runTokenizer(argument);
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

  @SuppressWarnings("unchecked")
  private static Object runLog(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> writes = new ArrayList<>();
    var logger = new PythonLlamaLogger(writes::add);
    logger.setLevel(((Number) argument.get("level")).intValue());
    List<Object> events = new ArrayList<>();
    for (Object item : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) item;
      Object result =
          capture(
              () -> {
                switch ((String) step.get(0)) {
                  case "callback" -> {
                    byte[] text = new byte[step.get(2).toString().length() / 2];
                    String hex = (String) step.get(2);
                    for (int i = 0; i < text.length; i++)
                      text[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                    logger.logCallback(((Number) step.get(1)).intValue(), text, null);
                    return null;
                  }
                  case "verbose" -> {
                    logger.setVerbose((Boolean) step.get(1));
                    return null;
                  }
                  default -> throw new AssertionError(step);
                }
              });
      events.add(
          dict(
              "result",
              result,
              "level",
              logger.level(),
              "last",
              logger.lastLogLevel(),
              "stderr",
              new ArrayList<>(writes)));
      writes.clear();
    }
    return events;
  }

  @SuppressWarnings("unchecked")
  private static Object runWarn(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> emitted = new ArrayList<>();
    var registry =
        PythonWarnings.withAction(
            PythonWarningAction.valueOf(((String) argument.get("action")).toUpperCase()),
            warning ->
                emitted.add(
                    dict(
                        "category", warning.category().pythonName(),
                        "message", warning.message())));
    var location = new PythonWarningLocation("caller", 1, (String) argument.get("module"), null);
    List<Object> results = new ArrayList<>();
    int calls = ((Number) argument.get("calls")).intValue();
    for (int i = 0; i < calls; i++)
      results.add(
          capture(
              () -> {
                registry.deprecated("legacy_symbol", "use replacement", location);
                return null;
              }));
    return dict("results", results, "emitted", emitted);
  }

  @SuppressWarnings("unchecked")
  private static Object runLora(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var resource = new PythonLlamaResource<>((Object) argument.get("adapter"));
    Object result =
        capture(
            () -> {
              resource.free(
                  value -> {
                    trace.add(value);
                    if ((Boolean) argument.get("fail"))
                      throw new PythonException(PythonErrorType.VALUE_ERROR, "free failed", null);
                  });
              return null;
            });
    return dict("result", result, "adapter", resource.value(), "trace", trace);
  }

  @SuppressWarnings("unchecked")
  private static Object runDunder(Object input) throws Exception {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var llama =
        new PythonLlama(new int[0], new float[0][], 0, 0, false, false, false, () -> () -> {});
    llama.setCloseAction(() -> trace.add("close"));
    return switch ((String) argument.get("member")) {
      case "ctx" -> dict("result", attachAndGet(llama, LlamaContext.class, "nativeContext", "CTX"));
      case "model" -> dict("result", attachAndGet(llama, LlamaModel.class, "nativeModel", "MODEL"));
      default -> {
        llama.close();
        yield dict("result", null, "trace", trace);
      }
    };
  }

  /** Property pass-through: attach a stand-in field value and check identity on the getter. */
  private static <T> Object attachAndGet(
      PythonLlama llama, Class<T> type, String field, String marker) throws Exception {
    Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    T handle = type.cast(((Unsafe) unsafeField.get(null)).allocateInstance(type));
    Field target = PythonLlama.class.getDeclaredField(field);
    target.setAccessible(true);
    target.set(llama, handle);
    Object value = field.equals("nativeContext") ? llama.ctx() : llama.model();
    return value == handle ? marker : value;
  }

  @SuppressWarnings("unchecked")
  private static Object runTokenizer(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    PythonTokenizerModel stub =
        new PythonTokenizerModel() {
          public int[] tokenize(byte[] text, boolean addBos, boolean special) {
            return new int[0];
          }

          public byte[] detokenize(int[] tokens, boolean special) {
            return new byte[0];
          }
        };
    var llama =
        new PyAttributed() {
          public Object attr(String name) {
            return name.equals("_model") ? stub : Py.ABSENT;
          }
        };
    var tokenizer =
        PythonLlamaTokenizer.fromGgmlFile(
            (String) argument.get("path"),
            keywords -> {
              trace.add(new LinkedHashMap<>(keywords));
              return llama;
            });
    return dict(
        "keywords",
        trace,
        "model",
        tokenizerModelOf(tokenizer) == stub ? "internals-model" : tokenizerModelOf(tokenizer));
  }

  private static Object tokenizerModelOf(PythonLlamaTokenizer tokenizer) {
    try {
      Field field = PythonLlamaTokenizer.class.getDeclaredField("model");
      field.setAccessible(true);
      return field.get(tokenizer);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
