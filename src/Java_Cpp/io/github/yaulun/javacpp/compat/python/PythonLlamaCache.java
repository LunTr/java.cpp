package io.github.yaulun.javacpp.compat.python;

import java.util.List;
import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PyItemAccess;

/** Shared BaseLlamaCache contract from the pinned Python source. */
public abstract class PythonLlamaCache implements PyItemAccess {
  private Object capacityBytes;

  protected PythonLlamaCache() {
    this(java.math.BigInteger.ONE.shiftLeft(31));
  }

  protected PythonLlamaCache(Object capacityBytes) {
    this.capacityBytes = capacityBytes;
  }

  /** Returns capacity_bytes verbatim; the original attribute accepts any object. */
  public Object capacityBytes() {
    return capacityBytes;
  }

  /** Assignment defers trimming until a subsequent write. */
  public void setCapacityBytes(Object value) {
    capacityBytes = value;
  }

  public abstract Object cacheSize();

  /** The original base method has a pass body and returns None. */
  public List<Object> findLongestPrefixKey(List<Object> key) {
    return null;
  }

  public abstract Object get(List<Object> key);

  public abstract boolean contains(List<Object> key);

  public abstract void put(List<Object> key, Object value);

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
