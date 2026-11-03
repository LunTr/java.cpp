package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PyItemAccess;
import io.github.yaulun.javacpp.python.PythonErrorType;
import java.util.List;

/**
 * Shared BaseLlamaCache contract from the pinned Python source. The class stays concrete so the
 * original NotImplementedError bodies remain callable; the constructor reproduces the ABCMeta
 * instantiation rule by checking which of the four abstract methods a subclass still inherits.
 */
public class PythonLlamaCache implements PyItemAccess {
  private Object capacityBytes;

  protected PythonLlamaCache() {
    this(java.math.BigInteger.ONE.shiftLeft(31));
  }

  protected PythonLlamaCache(Object capacityBytes) {
    PythonAbstract.checkInstance(
        this,
        PythonLlamaCache.class,
        pythonTypeName(),
        PythonAbstract.method("cacheSize", "cache_size"),
        PythonAbstract.method("get", "__getitem__", List.class),
        PythonAbstract.method("contains", "__contains__", List.class),
        PythonAbstract.method("put", "__setitem__", List.class, Object.class));
    this.capacityBytes = capacityBytes;
  }

  /** Python-facing class name used in the ABCMeta instantiation message. */
  protected String pythonTypeName() {
    return "BaseLlamaCache";
  }

  /**
   * Shared _find_longest_prefix_key loop: longest nonempty token prefix wins, ties keep the
   * earliest candidate in iteration order.
   */
  protected static List<Object> longestPrefixKey(
      Iterable<List<Object>> candidates, List<Object> key) {
    int minimumLength = 0;
    List<Object> minimumKey = null;
    for (List<Object> candidate : candidates) {
      int length = PythonTokens.longestTokenPrefix(candidate, key);
      if (length > minimumLength) {
        minimumLength = length;
        minimumKey = candidate;
      }
    }
    return minimumKey;
  }

  /** Returns capacity_bytes verbatim; the original attribute accepts any object. */
  public Object capacityBytes() {
    return capacityBytes;
  }

  /** Assignment defers trimming until a subsequent write. */
  public void setCapacityBytes(Object value) {
    capacityBytes = value;
  }

  /** Original abstract property body: raise NotImplementedError. */
  public Object cacheSize() {
    throw Py.error(PythonErrorType.NOT_IMPLEMENTED_ERROR, "");
  }

  /** The original base method has a pass body and returns None. */
  public List<Object> findLongestPrefixKey(List<Object> key) {
    return null;
  }

  /** Original abstract method body: raise NotImplementedError. */
  public Object get(List<Object> key) {
    throw Py.error(PythonErrorType.NOT_IMPLEMENTED_ERROR, "");
  }

  /** Original abstract method body: raise NotImplementedError. */
  public boolean contains(List<Object> key) {
    throw Py.error(PythonErrorType.NOT_IMPLEMENTED_ERROR, "");
  }

  /** Original abstract method body: raise NotImplementedError. */
  public void put(List<Object> key, Object value) {
    throw Py.error(PythonErrorType.NOT_IMPLEMENTED_ERROR, "");
  }

  @Override
  public Object pyGet(Object key) {
    return get(Py.tuple(key));
  }

  @Override
  public void pySet(Object key, Object value) {
    put(Py.tuple(key), value);
  }

  @Override
  public boolean pyContains(Object key) {
    return contains(Py.tuple(key));
  }
}
