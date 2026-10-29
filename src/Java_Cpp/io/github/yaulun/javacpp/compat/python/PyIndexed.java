package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;

/** Index protocol (the __index__ slot) for objects usable as integers in index positions. */
public interface PyIndexed {
  BigInteger pyIndex();
}
