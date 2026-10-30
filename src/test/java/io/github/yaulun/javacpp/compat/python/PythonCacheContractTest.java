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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class PythonCacheContractTest {
  @TestFactory
  Stream<DynamicTest> matchesPinnedDiskCacheControlFlow() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-cache-contracts.tsv"),
            "Run generate_cache_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index ->
                DynamicTest.dynamicTest(
                    "disk:" + index,
                    () -> {
                      String[] parts = lines.get(index).split("\t");
                      Object argument =
                          PythonContractData.read(
                              new DataInputStream(
                                  new ByteArrayInputStream(Base64.getDecoder().decode(parts[1]))));
                      Object actual = parts[0].equals("base") ? baseCase(argument) : run(argument);
                      var bytes = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(bytes), actual);
                      assertEquals(
                          parts[2],
                          Base64.getEncoder().encodeToString(bytes.toByteArray()),
                          "argument=" + argument + ", result=" + actual);
                    }));
  }

  @Test
  void baseDefaultsAndDiskFactoryArguments() {
    var cache =
        new PythonLlamaCache() {
          public BigInteger cacheSize() {
            return BigInteger.ZERO;
          }

          public Object get(List<Object> key) {
            return null;
          }

          public boolean contains(List<Object> key) {
            return false;
          }

          public void put(List<Object> key, Object state) {}
        };
    assertEquals(BigInteger.ONE.shiftLeft(31), cache.capacityBytes());
    assertNull(cache.findLongestPrefixKey(null));
    cache.setCapacityBytes(BigInteger.valueOf(-1));
    assertEquals(BigInteger.valueOf(-1), cache.capacityBytes());
    var directories = new ArrayList<String>();
    var store = new TracedStore();
    var disk =
        new PythonLlamaDiskCache(
            directory -> {
              directories.add(directory);
              return store;
            });
    assertEquals(List.of(".cache/llama_cache"), directories);
    assertEquals(BigInteger.ONE.shiftLeft(31), disk.capacityBytes());
    assertSame(store, disk.cache());
    var llama =
        new PythonLlama(new int[0], new float[0][], 0, 0, false, false, false, () -> () -> {});
    llama.setCache(disk);
    assertSame(disk, llama.cache());
    llama.setCache(null);
    assertNull(llama.cache());
  }

  private static Object baseCase(Object capacity) {
    var cache =
        new PythonLlamaCache(
            capacity == null ? BigInteger.ONE.shiftLeft(31) : new BigInteger((String) capacity)) {
          public BigInteger cacheSize() {
            return BigInteger.ZERO;
          }

          public Object get(List<Object> key) {
            return null;
          }

          public boolean contains(List<Object> key) {
            return false;
          }

          public void put(List<Object> key, Object state) {}
        };
    return dict(
        "capacity", cache.capacityBytes().toString(), "prefix", cache.findLongestPrefixKey(null));
  }

  private static Object capture(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  private static List<Object> key(Object value) {
    return ((List<?>) value)
        .stream()
        .map(token -> token == null ? null : (Object) new BigInteger((String) token))
        .toList();
  }

  private static Object strings(List<?> key) {
    return key == null
        ? null
        : key.stream().map(token -> token == null ? null : token.toString()).toList();
  }

  @SuppressWarnings("unchecked")
  private static Object run(Object input) {
    var argument = (java.util.Map<String, Object>) input;
    var store = new TracedStore();
    List<Object> writes = new ArrayList<>();
    var cache =
        new PythonLlamaDiskCache(
            (String) argument.get("directory"),
            new BigInteger((String) argument.get("capacity")),
            directory -> {
              store.call("open", directory);
              return store;
            },
            writes::add);
    List<Object> events = new ArrayList<>();
    for (Object item : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) item;
      Object result =
          capture(
              () -> {
                switch ((String) step.get(0)) {
                  case "put" ->
                      cache.put(
                          key(step.get(1)),
                          new PythonLlamaState(
                              null, null, 0, null, new BigInteger((String) step.get(2)), 0));
                  case "get" -> {
                    Object found = cache.get(key(step.get(1)));
                    return found == null
                        ? null
                        : ((PythonLlamaState) found).llamaStateSize().toString();
                  }
                  case "contains" -> {
                    return cache.contains(key(step.get(1)));
                  }
                  case "find" -> {
                    return strings(cache.findLongestPrefixKey(key(step.get(1))));
                  }
                  case "volume" -> {
                    return cache.cacheSize().toString();
                  }
                  case "capacity" -> cache.setCapacityBytes(new BigInteger((String) step.get(1)));
                  case "failure" -> store.failure = (String) step.get(1);
                  default -> throw new AssertionError(step);
                }
                return null;
              });
      events.add(
          dict(
              "result",
              result,
              "order",
              store.data.keySet().stream().map(PythonCacheContractTest::strings).toList(),
              "trace",
              new ArrayList<>(store.trace),
              "stderr",
              new ArrayList<>(writes)));
      store.trace.clear();
      writes.clear();
    }
    return events;
  }

  private static final class TracedStore implements PythonLlamaDiskCache.Store {
    private final LinkedHashMap<List<Object>, Object> data = new LinkedHashMap<>();
    private final List<Object> trace = new ArrayList<>();
    private String failure;

    void call(String name, Object... values) {
      List<Object> event = new ArrayList<>();
      event.add(name);
      java.util.Collections.addAll(event, values);
      trace.add(event);
      if (name.equals(failure))
        throw PythonValues.error(PythonErrorType.VALUE_ERROR, "store:" + name);
    }

    public BigInteger volume() {
      call("volume");
      return data.values().stream()
          .map(value -> ((PythonLlamaState) value).llamaStateSize())
          .reduce(BigInteger.TEN, BigInteger::add);
    }

    public Iterable<List<Object>> iterKeys() {
      call("iterKeys");
      return data.keySet();
    }

    public Object pop(List<Object> key) {
      call("pop", strings(key));
      return data.remove(key);
    }

    public boolean contains(List<Object> key) {
      call("contains", strings(key));
      return data.containsKey(key);
    }

    public void delete(List<Object> key) {
      call("delete", strings(key));
      data.remove(key);
    }

    public void put(List<Object> key, Object value) {
      call("put", strings(key), ((PythonLlamaState) value).llamaStateSize().toString());
      data.put(key, value);
    }

    public BigInteger size() {
      call("size");
      return BigInteger.valueOf(data.size());
    }

    public List<Object> firstKey() {
      call("firstKey");
      return data.keySet().iterator().next();
    }
  }
}
