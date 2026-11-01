package io.github.yaulun.javacpp.compat.python;

import java.util.Objects;

/**
 * Ports _utils.suppress_stdout_stderr: fd duplication through injectable os/sys ports. With
 * disable=true both enter and exit return immediately; otherwise fds are dup'd, streams saved,
 * ipykernel _original_stdstream_copy attributes redirected, and sys streams swapped for the devnull
 * files. Exit restores everything in the original order.
 */
public final class PythonSuppressStdoutStderr implements AutoCloseable {
  /** Minimal os surface used by the pinned body: dup, dup2, close. */
  public interface Os {
    int dup(int fileno);

    void dup2(int source, int target);

    void close(int fileno);
  }

  /** Minimal sys surface: get/set stdout and stderr stream objects. */
  public interface Sys {
    Object stdout();

    void stdout(Object stream);

    Object stderr();

    void stderr(Object stream);
  }

  /** devnull file object exposing fileno(). */
  public interface NullFile {
    int fileno();
  }

  private final boolean disable;
  private final Os os;
  private final Sys sys;
  private final NullFile outNull;
  private final NullFile errNull;

  private int oldStdoutFileno;
  private int oldStderrFileno;
  private Object oldStdout;
  private Object oldStderr;
  private Object savedStdoutCopy;
  private Object savedStderrCopy;

  public PythonSuppressStdoutStderr(
      boolean disable, Os os, Sys sys, NullFile outNull, NullFile errNull) {
    this.disable = disable;
    this.os = Objects.requireNonNull(os);
    this.sys = Objects.requireNonNull(sys);
    this.outNull = Objects.requireNonNull(outNull);
    this.errNull = Objects.requireNonNull(errNull);
  }

  /** Ports __enter__; returns this like the Python context manager. */
  public PythonSuppressStdoutStderr enter() {
    if (disable) {
      return this;
    }
    int stdoutFilenoUndup = 1;
    int stderrFilenoUndup = 2;
    oldStdoutFileno = os.dup(stdoutFilenoUndup);
    oldStderrFileno = os.dup(stderrFilenoUndup);
    oldStdout = sys.stdout();
    oldStderr = sys.stderr();
    savedStdoutCopy = stdstreamCopy(sys.stdout());
    savedStderrCopy = stdstreamCopy(sys.stderr());
    if (savedStdoutCopy != null) setStdstreamCopy(sys.stdout(), stdoutFilenoUndup);
    if (savedStderrCopy != null) setStdstreamCopy(sys.stderr(), stderrFilenoUndup);
    os.dup2(outNull.fileno(), stdoutFilenoUndup);
    os.dup2(errNull.fileno(), stderrFilenoUndup);
    sys.stdout(outNull);
    sys.stderr(errNull);
    return this;
  }

  /** Ports __exit__. */
  public void exit() {
    if (disable) {
      return;
    }
    sys.stdout(oldStdout);
    sys.stderr(oldStderr);
    os.dup2(oldStdoutFileno, 1);
    os.dup2(oldStderrFileno, 2);
    os.close(oldStdoutFileno);
    os.close(oldStderrFileno);
    if (savedStdoutCopy != null) setStdstreamCopy(sys.stdout(), savedStdoutCopy);
    if (savedStderrCopy != null) setStdstreamCopy(sys.stderr(), savedStderrCopy);
  }

  @Override
  public void close() {
    exit();
  }

  private static Object stdstreamCopy(Object stream) {
    try {
      var field = findField(stream.getClass());
      return field == null ? null : field.get(stream);
    } catch (ReflectiveOperationException failure) {
      return null;
    }
  }

  private static void setStdstreamCopy(Object stream, Object value) {
    try {
      var field = findField(stream.getClass());
      if (field != null) field.set(stream, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static java.lang.reflect.Field findField(Class<?> type) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        var field = current.getDeclaredField("_originalStdstreamCopy");
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException missing) {
      }
    }
    return null;
  }
}
