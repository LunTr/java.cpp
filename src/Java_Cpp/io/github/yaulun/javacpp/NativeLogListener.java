package io.github.yaulun.javacpp;

/** Receives complete native log lines; callbacks must avoid calling the native facade. */
@FunctionalInterface
public interface NativeLogListener {
  /** Receives a severity and a line without its trailing newline, possibly on a native thread. */
  void onLog(LogLevel level, String line);
}
