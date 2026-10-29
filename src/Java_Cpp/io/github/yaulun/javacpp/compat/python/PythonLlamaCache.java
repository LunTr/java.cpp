package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.util.List;

/** Shared BaseLlamaCache contract from the pinned Python source. */
public abstract class PythonLlamaCache {
  private BigInteger capacityBytes;

  protected PythonLlamaCache() {
    this(BigInteger.ONE.shiftLeft(31));
  }

  protected PythonLlamaCache(BigInteger capacityBytes) {
    this.capacityBytes = capacityBytes;
  }

  public BigInteger capacityBytes() {
    return capacityBytes;
  }

  /** Assignment defers trimming until a subsequent write. */
  public void setCapacityBytes(BigInteger value) {
    capacityBytes = value;
  }

  public abstract BigInteger cacheSize();

  /** The original base method has a pass body and returns None. */
  public List<BigInteger> findLongestPrefixKey(List<BigInteger> key) {
    return null;
  }

  public abstract PythonLlamaState get(List<BigInteger> key);

  public abstract boolean contains(List<BigInteger> key);

  public abstract void put(List<BigInteger> key, PythonLlamaState value);
}
