package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import io.github.yaulun.javacpp.LoadProgressListener;
import io.github.yaulun.javacpp.LogLevel;
import io.github.yaulun.javacpp.NativeLogListener;
import java.util.ArrayDeque;
import java.util.List;

/** Process-lifetime log callback with a bounded diagnostic ring and callback reentry guard. */
public final class NativeLogBridge implements LlamaLibrary.LogCallback {
  private static final NativeLogBridge instance = new NativeLogBridge();
  private static final ThreadLocal<Boolean> inCallback = ThreadLocal.withInitial(() -> false);
  private final ArrayDeque<String> recent = new ArrayDeque<>();
  private final StringBuilder pending = new StringBuilder();
  private LogLevel minimum = LogLevel.WARN;
  private LogLevel previous = LogLevel.INFO;
  private NativeLogListener listener;

  private NativeLogBridge() {}

  public static NativeLogBridge instance() {
    return instance;
  }

  /** Rejects native facade access from native callbacks. */
  public static void ensureOutsideCallback() {
    if (inCallback.get()) {
      throw new IllegalStateException("Native callback cannot reenter facade");
    }
  }

  /** Executes a progress callback without allowing reentry to native calls. */
  public static boolean progress(LoadProgressListener target, float value) {
    boolean previousValue = inCallback.get();
    inCallback.set(true);
    try {
      return target.onProgress(value);
    } finally {
      inCallback.set(previousValue);
    }
  }

  public synchronized void configure(LogLevel minimum, NativeLogListener listener) {
    this.minimum = minimum;
    this.listener = listener;
  }

  @Override
  public synchronized void invoke(int level, Pointer text, Pointer userData) {
    boolean previousValue = inCallback.get();
    inCallback.set(true);
    try {
      if (level != 5) {
        previous = LogLevel.fromNative(level);
      }
      String piece = NativeStrings.fromCString(text);
      if (piece == null) {
        return;
      }
      pending.append(piece);
      int end;
      while ((end = pending.indexOf("\n")) >= 0) {
        String line = pending.substring(0, end).stripTrailing();
        pending.delete(0, end + 1);
        if (previous.ordinal() >= LogLevel.WARN.ordinal()) {
          recordWarning("[" + previous + "] " + line);
        }
        if (listener != null && previous.ordinal() >= minimum.ordinal()) {
          try {
            listener.onLog(previous, line);
          } catch (Throwable failure) {
            recordWarning("Log listener failed: " + failure);
          }
        }
      }
      if (pending.length() > 65536) {
        recordWarning("Native log line exceeded 64 KiB; tail retained");
        pending.delete(0, pending.length() - 4096);
      }
    } catch (Throwable failure) {
      recordWarning("Log callback failed: " + failure);
    } finally {
      inCallback.set(previousValue);
    }
  }

  public synchronized void recordWarning(String line) {
    if (recent.size() == 40) {
      recent.removeFirst();
    }
    recent.addLast(line);
  }

  public synchronized List<String> recentProblems() {
    return List.copyOf(recent);
  }

  /** Clears previous diagnostic lines at the start of an explicit model load. */
  public synchronized void clearRecent() {
    recent.clear();
  }
}
