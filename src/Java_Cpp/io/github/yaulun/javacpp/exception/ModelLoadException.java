package io.github.yaulun.javacpp.exception;

import java.util.List;

/** Typed ModelLoad failure. */
public final class ModelLoadException extends JavaCppException {
  /** Creates a diagnostic with structured native failure evidence. */
  public ModelLoadException(
      ErrorCode code,
      String detail,
      List<String> nativeLog,
      Throwable cause,
      NativeFailure nativeFailure) {
    super(code, detail, nativeLog, cause, nativeFailure);
  }

  /** Creates a diagnostic with its original cause and native log snapshot. */
  public ModelLoadException(
      ErrorCode code, String detail, List<String> nativeLog, Throwable cause) {
    super(code, detail, nativeLog, cause);
  }

  /** Creates a diagnostic without an underlying Java exception. */
  public ModelLoadException(ErrorCode code, String detail, List<String> nativeLog) {
    this(code, detail, nativeLog, null);
  }
}
