package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LlamaRAMCache from Python 0.3.36: longest nonempty prefix lookup and ordered eviction. Capacity
 * and state sizes use arbitrary-precision integers. This mutable object requires caller
 * synchronization when shared between threads.
 */
public final class PythonLlamaRamCache extends PythonLlamaCache {
  private final LinkedHashMap<List<BigInteger>, PythonLlamaState> cacheState =
      new LinkedHashMap<>();

  /** Ports the default capacity_bytes=(2 &lt;&lt; 30) constructor. */
  public PythonLlamaRamCache() {
    this(BigInteger.ONE.shiftLeft(31));
  }

  /** Ports LlamaRAMCache.__init__; negative and zero capacities are retained. */
  public PythonLlamaRamCache(BigInteger capacityBytes) {
    super(capacityBytes);
  }

  /** Exposes the ordered cache_state mapping with its original state references. */
  public Map<List<BigInteger>, PythonLlamaState> cacheState() {
    return cacheState;
  }

  /** Ports cache_size by summing current fields on every query, including negative sizes. */
  public BigInteger cacheSize() {
    List<BigInteger> sizes = new ArrayList<>();
    for (PythonLlamaState state : cacheState.values()) {
      if (state == null)
        throw PythonValues.error(
            PythonErrorType.ATTRIBUTE_ERROR,
            "'NoneType' object has no attribute 'llama_state_size'");
      sizes.add(state.llamaStateSize());
    }
    // The original list comprehension reads every attribute before sum starts adding.
    BigInteger size = BigInteger.ZERO;
    for (BigInteger stateSize : sizes) {
      if (stateSize == null)
        throw PythonValues.error(
            PythonErrorType.TYPE_ERROR, "unsupported operand type(s) for +: 'int' and 'NoneType'");
      size = size.add(stateSize);
    }
    return size;
  }

  /** Ports _find_longest_prefix_key; ties retain the earliest key in current cache order. */
  public List<BigInteger> findLongestPrefixKey(List<BigInteger> key) {
    int minimumLength = 0;
    List<BigInteger> minimumKey = null;
    for (List<BigInteger> candidate : cacheState.keySet()) {
      int length = PythonTokens.longestTokenPrefix(candidate, key);
      if (length > minimumLength) {
        minimumLength = length;
        minimumKey = candidate;
      }
    }
    return minimumKey;
  }

  /** Ports __getitem__; a hit moves to the end and a miss raises KeyError('Key not found'). */
  public PythonLlamaState get(List<BigInteger> key) {
    List<BigInteger> found = findLongestPrefixKey(key);
    if (found == null) throw PythonValues.error(PythonErrorType.KEY_ERROR, "'Key not found'");
    PythonLlamaState value = cacheState.remove(found);
    cacheState.put(found, value);
    return value;
  }

  /** Ports __contains__; membership leaves the cache order unchanged. */
  public boolean contains(List<BigInteger> key) {
    return findLongestPrefixKey(key) != null;
  }

  /**
   * Ports __setitem__; replaces exact keys, then evicts from the oldest end until within capacity.
   */
  public void put(List<BigInteger> key, PythonLlamaState value) {
    List<BigInteger> tuple = java.util.Collections.unmodifiableList(new ArrayList<>(key));
    cacheState.remove(tuple);
    cacheState.put(tuple, value);
    // Python evaluates cache_size before testing len(cache_state), including for an empty cache.
    while (cacheSize().compareTo(capacityBytes()) > 0 && !cacheState.isEmpty()) {
      cacheState.remove(cacheState.keySet().iterator().next());
    }
  }
}
