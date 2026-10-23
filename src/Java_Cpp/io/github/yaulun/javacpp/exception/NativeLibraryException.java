package io.github.yaulun.javacpp.exception;

import java.util.List;

/** Typed NativeLibrary failure. */
public final class NativeLibraryException extends JavaCppException {
  /** Creates a diagnostic with its original cause and native log snapshot. */
  public NativeLibraryException(
      ErrorCode code, String detail, List<String> nativeLog, Throwable cause) {
    super(code, detail, nativeLog, cause);
  }

  /** Creates a diagnostic without an underlying Java exception. */
  public NativeLibraryException(ErrorCode code, String detail, List<String> nativeLog) {
    this(code, detail, nativeLog, null);
  }
}
