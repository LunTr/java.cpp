package io.github.yaulun.javacpp.compat.python;

import java.util.ArrayDeque;
import java.util.Deque;

/** Minimal contextlib.ExitStack port: LIFO callbacks run once on close. */
public final class PythonExitStack {
  private final Deque<Runnable> callbacks = new ArrayDeque<>();
  private boolean closed;

  public void callback(Runnable action) {
    callbacks.push(action);
  }

  /** Runs every registered callback in reverse order; a failure still runs the rest. */
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    RuntimeException first = null;
    while (!callbacks.isEmpty()) {
      try {
        callbacks.pop().run();
      } catch (RuntimeException failure) {
        if (first == null) {
          first = failure;
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }
}
