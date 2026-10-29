package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LlamaRAMCache from Python 0.3.36: longest nonempty prefix lookup and ordered eviction. Keys and
 * values keep the original duck-typed semantics: keys are converted with tuple() and values are
 * read through the llama_state_size attribute. This mutable object requires caller
 * synchronization when shared between threads.
 */
public final class PythonLlamaRamCache extends PythonLlamaCache {
  private final LinkedHashMap<List<Object>, Object> cacheState = new LinkedHashMap<>();

  /** Ports the default capacity_bytes=(2 &lt;&lt; 30) constructor. */
  public PythonLlamaRamCache() {
    this(BigInteger.ONE.shiftLeft(31));
  }

  /** Ports LlamaRAMCache.__init__; negative and zero capacities are retained. */
  public PythonLlamaRamCache(Object capacityBytes) {
    super(capacityBytes);
  }

  /** Exposes the ordered cache_state mapping with its original state references. */
  public Map<List<Object>, Object> cacheState() {
    return cacheState;
  }

  /** Ports cache_size by summing current attributes on every query. */
  public Object cacheSize() {
    List<Object> sizes = new ArrayList<>();
    for (Object state : cacheState.values()) {
      // The original list comprehension reads every attribute before sum starts adding.
      sizes.add(Py.getAttr(state, "llama_state_size"));
    }
    Object size = BigInteger.ZERO;
    for (Object stateSize : sizes) size = Py.add(size, stateSize);
    return size;
  }

  /** Ports _find_longest_prefix_key; ties retain the earliest key in current cache order. */
  public List<Object> findLongestPrefixKey(List<Object> key) {
    int minimumLength = 0;
    List<Object> minimumKey = null;
    for (List<Object> candidate : cacheState.keySet()) {
      int length = PythonTokens.longestTokenPrefix(candidate, key);
      if (length > minimumLength) {
        minimumLength = length;
        minimumKey = candidate;
      }
    }
    return minimumKey;
  }

  /** Ports __getitem__; a hit moves to the end and a miss raises KeyError('Key not found'). */
  public Object get(List<Object> key) {
    List<Object> found = findLongestPrefixKey(key);
    if (found == null) throw Py.error(PythonErrorType.KEY_ERROR, "'Key not found'");
    Object value = cacheState.remove(found);
    cacheState.put(found, value);
    return value;
  }

  /** Ports __contains__; membership leaves the cache order unchanged. */
  public boolean contains(List<Object> key) {
    return findLongestPrefixKey(key) != null;
  }

  /**
   * Ports __setitem__; replaces exact keys, then evicts from the oldest end until within capacity.
   */
  public void put(List<Object> key, Object value) {
    List<Object> tuple = Py.tuple(key);
    cacheState.remove(tuple);
    cacheState.put(tuple, value);
    // Python evaluates cache_size before testing len(cache_state), including for an empty cache.
    while (Py.compare(cacheSize(), Py.Op.GT, capacityBytes()) && !cacheState.isEmpty()) {
      cacheState.remove(cacheState.keySet().iterator().next());
    }
  }
}
