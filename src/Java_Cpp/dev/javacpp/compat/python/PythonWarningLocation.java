package dev.javacpp.compat.python;

import dev.javacpp.python.Py;
import dev.javacpp.python.PythonErrorType;
import java.util.Objects;

/**
 * Python warning origin; callers supply installation-specific filename and optional source text.
 */
public record PythonWarningLocation(
    String fileName, int lineNumber, String module, String sourceLine) {
  /** Requires a filename/module and a nonnegative line number; sourceLine may be null. */
  public PythonWarningLocation {
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(module, "module");
    if (lineNumber < 0) {
      throw Py.error(PythonErrorType.VALUE_ERROR, "lineNumber must be >= 0");
    }
  }
}
