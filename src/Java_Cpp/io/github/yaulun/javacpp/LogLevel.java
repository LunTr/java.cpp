package io.github.yaulun.javacpp;

/** Minimum severity for native log delivery. */
public enum LogLevel {
  DEBUG,
  INFO,
  WARN,
  ERROR;

  /** Converts a native severity; continuation records are handled by the log bridge. */
  public static LogLevel fromNative(int value) {
    return value >= 1 && value <= 4 ? values()[value - 1] : INFO;
  }
}
