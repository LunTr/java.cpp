package io.github.yaulun.javacpp.compat.python;

import java.util.Objects;
import io.github.yaulun.javacpp.python.PythonErrorType;

/** Immutable Python warning category, exact message and explicit origin. */
public record PythonWarning(
    PythonErrorType category, String message, PythonWarningLocation location) {
  /** Requires a supported warning category, a message and its origin. */
  public PythonWarning {
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(location, "location");
    if (!category.isWarning()) {
      throw new IllegalArgumentException("category must be a Python warning");
    }
  }

  /** Formats warnings.formatwarning output for the provided location, with LF line endings. */
  public String format() {
    String formatted =
        location.fileName()
            + ":"
            + location.lineNumber()
            + ": "
            + category.pythonName()
            + ": "
            + message
            + "\n";
    if (location.sourceLine() != null && !location.sourceLine().isEmpty()) {
      formatted += "  " + stripPythonWhitespace(location.sourceLine()) + "\n";
    }
    return formatted;
  }

  private static String stripPythonWhitespace(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && isPythonWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && isPythonWhitespace(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(start, end);
  }

  private static boolean isPythonWhitespace(char value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == 0x85;
  }
}
