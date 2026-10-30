package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PyItemAccess;
import io.github.yaulun.javacpp.python.PythonErrorType;
import java.util.List;
import java.util.Map;

/**
 * Shared BaseLlamaCache contract from the pinned Python source. The class stays concrete so the
 * original NotImplementedError bodies remain callable; the constructor reproduces the ABCMeta
 * instantiation rule by checking which of the four abstract methods a subclass still inherits.
 */
public class PythonLlamaCache implements PyItemAccess {
  private static final Map<String, String> ABSTRACT =
      Map.of(
          "cacheSize", "cache_size",
          "contains", "__contains__",
          "get", "__getitem__",
          "put", "__setitem__");
  private Object capacityBytes;

  protected PythonLlamaCache() {
    this(java.math.BigInteger.ONE.shiftLeft(31));
  }

  protected PythonLlamaCache(Object capacityBytes) {
    List<String> missing =
        ABSTRACT.entrySet().stream()
            .filter(entry -> inherited(entry.getKey()))
            .map(Map.Entry::getValue)
            .sorted()
            .toList();
    if (!missing.isEmpty()) {
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          "Can't instantiate abstract class "
              + pythonTypeName()
              + " without an implementation for abstract method"
              + (missing.size() == 1 ? " " : "s ")
              + missing.stream()
                  .map(name -> "'" + name + "'")
                  .reduce((a, b) -> a + ", " + b)
                  .orElseThrow());
    }
    this.capacityBytes = capacityBytes;
  }

  /** Python-facing class name used in the ABCMeta instantiation message. */
  protected String pythonTypeName() {
    return "BaseLlamaCache";
  }

  private boolean inherited(String method) {
    try {
      return getClass().getMethod(method, parameterTypes(method)).getDeclaringClass()
          == PythonLlamaCache.class;
    } catch (NoSuchMethodException failure) {
      throw new AssertionError(failure);
    }
  }

  private static Class<?>[] parameterTypes(String method) {
    return switch (method) {
      case "cacheSize" -> new Class<?>[0];
      case "get", "contains" -> new Class<?>[] {List.class};
      default -> new Class<?>[] {List.class, Object.class};
    };
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
