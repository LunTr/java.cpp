package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pinned LlamaDiskCache control flow over an explicit diskcache storage port. The port owns key
 * ordering, serialized values, physical volume and storage errors.
 */
public final class PythonLlamaDiskCache extends PythonLlamaCache {
  /** Operations used by the original diskcache.Cache calls, in their original evaluation order. */
  public interface Store {
    Object volume();

    Iterable<List<Object>> iterKeys();

    Object pop(List<Object> key);

    boolean contains(List<Object> key);

    void delete(List<Object> key);

    void put(List<Object> key, Object value);

    BigInteger size();

    List<Object> firstKey();
  }

  @FunctionalInterface
  public interface Factory {
    Store open(String directory);
  }

  private final Store cache;
  private final Consumer<String> stderr;

  public PythonLlamaDiskCache(Factory factory) {
    this(".cache/llama_cache", BigInteger.ONE.shiftLeft(31), factory, System.err::print);
  }

  public PythonLlamaDiskCache(
      String directory, Object capacityBytes, Factory factory, Consumer<String> stderr) {
    super(capacityBytes);
    cache = factory.open(directory);
    this.stderr = stderr;
  }

  public Store cache() {
    return cache;
  }

  @Override
  public Object cacheSize() {
    return cache.volume();
  }

  @Override
  public List<Object> findLongestPrefixKey(List<Object> key) {
    int minimumLength = 0;
    List<Object> minimumKey = null;
    for (List<Object> candidate : cache.iterKeys()) {
      int length = PythonTokens.longestTokenPrefix(candidate, key);
      if (length > minimumLength) {
        minimumLength = length;
        minimumKey = candidate;
      }
    }
    return minimumKey;
  }

  @Override
  public Object get(List<Object> key) {
    List<Object> found = findLongestPrefixKey(tuple(key));
    if (found == null) throw Py.error(PythonErrorType.KEY_ERROR, "'Key not found'");
    return cache.pop(found);
  }

  @Override
  public boolean contains(List<Object> key) {
    return findLongestPrefixKey(tuple(key)) != null;
  }

  @Override
  public void put(List<Object> key, Object value) {
    print("called");
    List<Object> converted = tuple(key);
    if (cache.contains(converted)) {
      print("delete");
      cache.delete(converted);
    }
    cache.put(converted, value);
    print("set");
    while (Py.compare(cacheSize(), Py.Op.GT, capacityBytes()) && cache.size().signum() > 0) {
      cache.delete(cache.firstKey());
    }
    print("trim");
  }

  private void print(String stage) {
    // CPython print writes the text and its terminator separately.
    stderr.accept("LlamaDiskCache.__setitem__: " + stage);
    stderr.accept("\n");
  }

  private static List<Object> tuple(List<Object> key) {
    return Py.tuple(key);
  }
}
