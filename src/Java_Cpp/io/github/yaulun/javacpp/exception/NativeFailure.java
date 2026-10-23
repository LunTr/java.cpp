package io.github.yaulun.javacpp.exception;

import java.util.Objects;

/** Original C function and return value retained for operation-specific error translation. */
public record NativeFailure(String function, long returnValue) {
  /** Requires a function name; zero denotes NULL for pointer-returning functions. */
  public NativeFailure {
    Objects.requireNonNull(function, "function");
    if (function.isBlank()) {
      throw new IllegalArgumentException("function must not be blank");
    }
  }
}
