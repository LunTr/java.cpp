package io.github.yaulun.javacpp.exception;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Base failure carrying an error code, repair hint, cause and immutable native log snapshot. */
public sealed class JavaCppException extends RuntimeException
    permits NativeLibraryException,
        ModelLoadException,
        InferenceException,
        TokenizationException,
        TemplateException {
  private final ErrorCode code;
  private final String detail;
  private final List<String> nativeLog;
  private final NativeFailure nativeFailure;

  protected JavaCppException(
      ErrorCode code, String detail, List<String> nativeLog, Throwable cause) {
    this(code, detail, nativeLog, cause, null);
  }

  /** Creates a diagnostic retaining a native return value independently of formatted messages. */
  protected JavaCppException(
      ErrorCode code,
      String detail,
      List<String> nativeLog,
      Throwable cause,
      NativeFailure nativeFailure) {
    super(detail, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.detail = Objects.requireNonNull(detail, "detail");
    this.nativeLog = List.copyOf(nativeLog);
    this.nativeFailure = nativeFailure;
  }

  /** Returns the stable diagnostic code. */
  public ErrorCode code() {
    return code;
  }

  /** Returns the failure detail captured at the Java/native boundary. */
  public String detail() {
    return detail;
  }

  /** Returns an immutable native diagnostic snapshot associated with this failure. */
  public List<String> nativeLog() {
    return nativeLog;
  }

  /** Returns structured native failure evidence when a C call supplied it. */
  public Optional<NativeFailure> nativeFailure() {
    return Optional.ofNullable(nativeFailure);
  }

  @Override
  public String getMessage() {
    String message = "[" + code + "] " + code.title() + ": " + detail + "\n  建议: " + code.hint();
    if (!nativeLog.isEmpty()) {
      message += "\n  Native log:\n    " + String.join("\n    ", nativeLog);
    }
    return message;
  }
}
