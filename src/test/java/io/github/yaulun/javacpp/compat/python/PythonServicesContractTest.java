package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonServicesContractTest {
  @TestFactory
  Stream<DynamicTest> originalServiceBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-services-contracts.tsv"),
            "Run generate_services_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "services:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object argument = read(parts[0]), expected = read(parts[1]);
                      Object actual = run(map(argument));
                      compare(expected, actual, "services:" + i);
                    }));
  }

  static Object read(String value) throws Exception {
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

  private static Object capture(Supplier<?> call) {
    try {
      return call.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  private static Object run(Map<String, Object> arg) {
    List<Object> trace = new ArrayList<>();
    String kind = (String) arg.get("kind"), fail = (String) arg.getOrDefault("fail", "");
    PythonLlama llama =
        new PythonLlama(new int[8], new float[8][4], 0, 8, true, false, false, () -> null);
    var services = llama.services();
    services.setModelPath("model.gguf");
    services.setVerbose(truth(arg.get("verbose")));
    Object result;
    if (kind.equals("sampling")) {
      var custom = new java.util.concurrent.atomic.AtomicReference<Consumer<float[]>>();
      var arguments = new LinkedHashMap<>(map(arg.get("options")));
      if (arg.get("processors") != null) {
        llama.setEvaluatedTokens(2);
        llama.inputIds().put(0, 1);
        llama.inputIds().put(1, 2);
        PythonLogitsProcessorList processors = new PythonLogitsProcessorList();
        for (Object item : (List<?>) arg.get("processors")) {
          String name = (String) item;
          if (name.equals("null")) {
            processors.add(null);
            continue;
          }
          processors.add(
              (ids, scores) -> {
                List<Float> values = new ArrayList<>();
                for (float v : scores) values.add(v);
                trace.add(List.of("processor", name, Arrays.stream(ids).boxed().toList(), values));
                if (name.equals("mutate")) {
                  ids[0] = 5;
                  float[] resultScores = scores.clone();
                  for (int i = 0; i < resultScores.length; i++) resultScores[i]++;
                  return resultScores;
                }
                if (name.equals("scalar")) return new float[] {7};
                if (name.equals("none")) return null;
                if (name.equals("wrong")) return new float[] {1, 1, 1, 1, 1};
                float[] resultScores = scores.clone();
                for (int i = 0; i < resultScores.length; i++) resultScores[i] *= 2;
                return resultScores;
              });
        }
        arguments.put("logits_processor", processors);
      }
      llama.setSeed(new BigInteger((String) arg.get("seed")));
      llama.setLastNTokensSize(n(arg.get("last")));
      llama.setSamplingBackend(
          () -> {
            trace.add(List.of("new"));
            return new PythonSamplerBuilder() {
              public void add(String operation, Object... args) {
                if (operation.equals("custom")) {
                  @SuppressWarnings("unchecked")
                  Consumer<float[]> callback = (Consumer<float[]>) args[0];
                  custom.set(callback);
                  args = new Object[] {"callback"};
                }
                List<Object> values = new ArrayList<>();
                for (Object value : args)
                  values.add(value instanceof BigInteger ? value.toString() : value);
                trace.add(List.of(operation, values));
                if (operation.equals(fail))
                  throw error(PythonErrorType.RUNTIME_ERROR, "failed " + operation);
              }

              public PythonLlamaSampler build() {
                return index -> 0;
              }
            };
          });
      result =
          capture(
              () -> {
                llama.initSampler(arguments);
                if (arg.get("processors") == null) return true;
                float[] values = {1, 2, 3, 4};
                Object status =
                    capture(
                        () -> {
                          custom.get().accept(values);
                          return null;
                        });
                List<Float> scores = new ArrayList<>();
                for (float v : values) scores.add(v);
                return dict(
                    "status",
                    status,
                    "scores",
                    scores,
                    "ids",
                    Arrays.stream(llama.inputIds().array()).limit(2).boxed().toList());
              });
    } else if (kind.equals("embedding")) {
      List<int[]> batch = new ArrayList<>();
      List<Double> live = new ArrayList<>();
      llama.setTokenizer(
          new PythonTokenizer() {
            public int[] tokenize(byte[] text, boolean bos, boolean special) {
              trace.add(List.of("tokenize", HexFormat.of().formatHex(text)));
              int[] tokens = new int[text.length];
              for (int i = 0; i < tokens.length; i++) tokens[i] = Byte.toUnsignedInt(text[i]);
              return tokens;
            }

            public byte[] detokenize(int[] tokens, int[] previous, boolean special) {
              throw new AssertionError();
            }
          });
      services.setEmbeddingBackend(
          new PythonEmbeddingBackend() {
            public int dimensions() {
              trace.add(List.of("dimensions"));
              return 3;
            }

            public int batchSize() {
              return n(arg.get("batch"));
            }

            public int sequenceLimit() {
              return n(arg.get("sequences"));
            }

            public int poolingType() {
              trace.add(List.of("pooling"));
              return n(arg.get("pooling"));
            }

            public boolean enabled() {
              return (boolean) arg.get("enabled");
            }

            public void resetTimings() {
              trace.add(List.of("resetTimings"));
            }

            public void printTimings() {
              trace.add(List.of("printTimings"));
            }

            public void resetBatch() {
              trace.add(List.of("resetBatch"));
              batch.clear();
            }

            public void addSequence(int[] tokens, int sequence, boolean all) {
              trace.add(List.of("add", Arrays.stream(tokens).boxed().toList(), sequence, all));
              batch.add(tokens);
            }

            public void clearMemory() {
              trace.add(List.of("clear"));
            }

            public void decode() {
              trace.add(List.of("decode"));
              live.clear();
              for (int i = 0; i < batch.stream().mapToInt(a -> a.length).sum() * 3; i++)
                live.add(i + 0.25);
              if (fail.equals("decode"))
                throw error(PythonErrorType.RUNTIME_ERROR, "decode failed");
            }

            public double[] embeddings() {
              trace.add(List.of("embeddings"));
              return live.stream().mapToDouble(Double::doubleValue).toArray();
            }

            public double[] sequenceEmbedding(int sequence) {
              trace.add(List.of("sequence", sequence));
              return new double[] {-(double) sequence, 2.0 + sequence, 0.0};
            }
          });
      result =
          capture(
              () ->
                  truth(arg.get("response"))
                      ? llama.createEmbedding(map(arg.get("options")))
                      : llama.embed(map(arg.get("options"))));
      // Original reset is the supplied callback; trace its successful invocation.
      if (llama.requiresEval()) trace.add(List.of("reset"));
    } else {
      services.setCompletionFactory(
          options -> {
            trace.add(List.of("completion", options));
            if (truth(arg.get("empty"))) return Collections.emptyIterator();
            return new Iterator<Map<String, Object>>() {
              int index;

              public boolean hasNext() {
                return index < 2;
              }

              public Map<String, Object> next() {
                if (index >= 2) throw new NoSuchElementException();
                trace.add(List.of("advance"));
                if (fail.equals("advance")) {
                  index = 2;
                  throw error(PythonErrorType.RUNTIME_ERROR, "advance failed");
                }
                return dict("answer", ++index);
              }
            };
          });
      Function<String, Function<Map<String, Object>, Object>> handler =
          name ->
              options -> {
                var forwarded = new LinkedHashMap<>(options);
                forwarded.remove("llama");
                trace.add(List.of(name, forwarded));
                if (fail.equals("handler"))
                  throw error(PythonErrorType.IMPORT_ERROR, "inner import");
                return truth(options.get("stream"))
                    ? List.of(dict("answer", 1), dict("answer", 2)).iterator()
                    : dict("answer", 1);
              };
      services.setChatFormat("format");
      if (arg.get("handler") != null && arg.get("handler").equals("explicit"))
        services.setChatHandler(handler.apply("explicit"));
      if (arg.get("handler") != null && arg.get("handler").equals("model"))
        services.modelHandlers().put("format", handler.apply("model"));
      services.setChatRegistry(
          format -> {
            trace.add(List.of("lookup", format));
            return handler.apply("global");
          });
      if (!arg.containsKey("installed") || truth(arg.get("installed")))
        services.setOpenAiTypes(
            new PythonLlamaServices.OpenAiTypes() {
              private Object convert(String name, Map<String, Object> value) {
                trace.add(List.of(name, value));
                if (fail.equals(name)) throw error(PythonErrorType.IMPORT_ERROR, "convert import");
                return dict("converted", value);
              }

              public Object completion(Map<String, Object> value) {
                return convert("convert", value);
              }

              public Object chunk(Map<String, Object> value) {
                return convert("chunk", value);
              }
            });
      result =
          capture(
              () -> {
                Object value =
                    switch (kind) {
                      case "completion" -> llama.createCompletion(map(arg.get("options")));
                      case "call" -> llama.call(map(arg.get("options")));
                      case "chat" -> llama.createChatCompletion(map(arg.get("options")));
                      case "openai" -> llama.createChatCompletionOpenAiV1(map(arg.get("options")));
                      default -> throw new AssertionError();
                    };
                if (value instanceof Iterator<?> iterator) {
                  List<Object> events = new ArrayList<>();
                  boolean[] ended = {false};
                  for (int i = 0; i < 3; i++)
                    events.add(
                        capture(
                            () -> {
                              if (ended[0]) return null;
                              try {
                                return iterator.next();
                              } catch (NoSuchElementException e) {
                                ended[0] = true;
                                return null;
                              } catch (PythonException e) {
                                ended[0] = true;
                                throw e;
                              }
                            }));
                  return events;
                }
                return value;
              });
    }
    return dict("result", result, "trace", trace);
  }

  static void compare(Object expected, Object actual, String path) {
    if (expected instanceof Number a && actual instanceof Number b) {
      double x = a.doubleValue(), y = b.doubleValue();
      if (Double.isFinite(x)) assertEquals(x, y, 1e-12 * Math.max(1, Math.abs(x)), path);
      else assertEquals(x, y, path);
    } else if (expected instanceof List<?> a && actual instanceof List<?> b) {
      assertEquals(a.size(), b.size(), path + " expected=" + expected + " actual=" + actual);
      for (int i = 0; i < a.size(); i++) compare(a.get(i), b.get(i), path + "[" + i + "]");
    } else if (expected instanceof Map<?, ?> a && actual instanceof Map<?, ?> b) {
      assertEquals(new ArrayList<>(a.keySet()), new ArrayList<>(b.keySet()), path);
      for (Object key : a.keySet()) compare(a.get(key), b.get(key), path + "." + key);
    } else assertEquals(expected, actual, path);
  }
}
