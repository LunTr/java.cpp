package io.github.yaulun.javacpp.python;

import java.util.Objects;

/** Python category and exact message, retaining the original Java failure as its cause. */
public final class PythonException extends RuntimeException {
  private final PythonErrorType type;

  public PythonException(PythonErrorType type, String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.type = Objects.requireNonNull(type, "type");
  }

  public PythonErrorType type() {
    return type;
  }

  /** Formats the final Python-style exception line without Java class or diagnostic prefixes. */
  @Override
  public String toString() {
    return getMessage().isEmpty() ? type.pythonName() : type.pythonName() + ": " + getMessage();
  }
}
