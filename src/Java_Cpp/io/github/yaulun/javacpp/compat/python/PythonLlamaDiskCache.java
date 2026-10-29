package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pinned LlamaDiskCache control flow over an explicit diskcache storage port. The port owns key
 * ordering, serialized values, physical volume and storage errors.
 */
public final class PythonLlamaDiskCache extends PythonLlamaCache {
  /** Operations used by the original diskcache.Cache calls, in their original evaluation order. */
  public interface Store {
    BigInteger volume();

    Iterable<List<BigInteger>> iterKeys();

    PythonLlamaState pop(List<BigInteger> key);

    boolean contains(List<BigInteger> key);

    void delete(List<BigInteger> key);

    void put(List<BigInteger> key, PythonLlamaState value);

    BigInteger size();

    List<BigInteger> firstKey();
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
      String directory, BigInteger capacityBytes, Factory factory, Consumer<String> stderr) {
    super(capacityBytes);
    cache = factory.open(directory);
    this.stderr = stderr;
  }

  public Store cache() {
    return cache;
  }

  @Override
  public BigInteger cacheSize() {
    return cache.volume();
  }

  @Override
  public List<BigInteger> findLongestPrefixKey(List<BigInteger> key) {
    int minimumLength = 0;
    List<BigInteger> minimumKey = null;
    for (List<BigInteger> candidate : cache.iterKeys()) {
      int length = PythonTokens.longestTokenPrefix(candidate, key);
      if (length > minimumLength) {
        minimumLength = length;
        minimumKey = candidate;
      }
    }
    return minimumKey;
  }

  @Override
  public PythonLlamaState get(List<BigInteger> key) {
    List<BigInteger> found = findLongestPrefixKey(tuple(key));
    if (found == null) throw PythonValues.error(PythonErrorType.KEY_ERROR, "'Key not found'");
    return cache.pop(found);
  }

  @Override
  public boolean contains(List<BigInteger> key) {
    return findLongestPrefixKey(tuple(key)) != null;
  }

  @Override
  public void put(List<BigInteger> key, PythonLlamaState value) {
    print("called");
    List<BigInteger> converted = tuple(key);
    if (cache.contains(converted)) {
      print("delete");
      cache.delete(converted);
    }
    cache.put(converted, value);
    print("set");
    while (cacheSize().compareTo(capacityBytes()) > 0 && cache.size().signum() > 0) {
      cache.delete(cache.firstKey());
    }
    print("trim");
  }

  private void print(String stage) {
    // CPython print writes the text and its terminator separately.
    stderr.accept("LlamaDiskCache.__setitem__: " + stage);
    stderr.accept("\n");
  }

  private static List<BigInteger> tuple(List<BigInteger> key) {
    return Collections.unmodifiableList(new ArrayList<>(key));
  }
}
