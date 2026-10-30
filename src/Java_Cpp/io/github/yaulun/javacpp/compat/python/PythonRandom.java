package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonNumbers;
import java.math.BigInteger;

/** CPython integer-seeded MT19937 draw used by completion seed selection. */
final class PythonRandom {
  private final int[] state = new int[624];
  private int index = 624;

  PythonRandom(Object value) {
    this(seedInteger(value));
  }

  private static BigInteger seedInteger(Object value) {
    if (value == null) return new BigInteger(19968, new java.security.SecureRandom());
    if (value instanceof String || value instanceof byte[]) {
      byte[] bytes =
          value instanceof String ? PythonLlamaServices.strictUtf8(value) : (byte[]) value;
      try {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-512").digest(bytes);
        byte[] joined = java.util.Arrays.copyOf(bytes, bytes.length + hash.length);
        System.arraycopy(hash, 0, joined, bytes.length, hash.length);
        return new BigInteger(1, joined);
      } catch (java.security.NoSuchAlgorithmException failure) {
        throw new AssertionError(failure);
      }
    }
    if (value instanceof Float || value instanceof Double) {
      double x = ((Number) value).doubleValue();
      long hash;
      if (Double.isNaN(x)) hash = System.identityHashCode(value);
      else if (Double.isInfinite(x)) hash = x > 0 ? 314159 : -314159;
      else {
        long bits = Double.doubleToRawLongBits(x);
        int exponent = (int) ((bits >>> 52) & 2047);
        long mantissa = bits & 0xfffffffffffffL;
        if (exponent != 0) mantissa |= 1L << 52;
        int shift = exponent == 0 ? -1074 : exponent - 1075;
        BigInteger modulus = BigInteger.ONE.shiftLeft(61).subtract(BigInteger.ONE),
            power = BigInteger.TWO.modPow(BigInteger.valueOf(Math.abs((long) shift)), modulus);
        BigInteger result =
            BigInteger.valueOf(mantissa)
                .multiply(shift < 0 ? power.modInverse(modulus) : power)
                .mod(modulus);
        hash = (bits < 0 ? -1 : 1) * result.longValue();
        if (hash == -1) hash = -2;
      }
      return hash < 0
          ? BigInteger.valueOf(hash).add(BigInteger.ONE.shiftLeft(64))
          : BigInteger.valueOf(hash);
    }
    if (value instanceof Number || value instanceof Boolean) return PythonNumbers.integer(value);
    throw PythonErrorBridge.error(
        PythonErrorType.TYPE_ERROR,
        "The only supported seed types are: None,\nint, float, str, bytes, and bytearray.");
  }

  PythonRandom(BigInteger seed) {
    seed = seed.abs();
    int[] key = new int[Math.max(1, (seed.bitLength() + 31) / 32)];
    for (int i = 0; i < key.length; i++) key[i] = seed.shiftRight(32 * i).intValue();
    state[0] = 19650218;
    for (int i = 1; i < 624; i++)
      state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
    int i = 1, j = 0;
    for (int k = Math.max(624, key.length); k > 0; k--) {
      state[i] = (state[i] ^ ((state[i - 1] ^ (state[i - 1] >>> 30)) * 1664525)) + key[j] + j;
      if (++i >= 624) {
        state[0] = state[623];
        i = 1;
      }
      if (++j >= key.length) j = 0;
    }
    for (int k = 623; k > 0; k--) {
      state[i] = (state[i] ^ ((state[i - 1] ^ (state[i - 1] >>> 30)) * 1566083941)) - i;
      if (++i >= 624) {
        state[0] = state[623];
        i = 1;
      }
    }
    state[0] = 0x80000000;
  }

  private long word() {
    if (index >= 624) {
      for (int i = 0; i < 624; i++) {
        int y = (state[i] & 0x80000000) | (state[(i + 1) % 624] & 0x7fffffff);
        state[i] = state[(i + 397) % 624] ^ (y >>> 1) ^ ((y & 1) == 0 ? 0 : 0x9908b0df);
      }
      index = 0;
    }
    int y = state[index++];
    y ^= y >>> 11;
    y ^= (y << 7) & 0x9d2c5680;
    y ^= (y << 15) & 0xefc60000;
    y ^= y >>> 18;
    return Integer.toUnsignedLong(y);
  }

  long completionSeed() {
    long result;
    do {
      result = word() | ((word() >>> 31) << 32);
    } while (result > 4294967296L);
    return result;
  }
}
