package io.github.yaulun.javacpp.exception;

import java.util.List;

/** Context capacity failure measured in tokens. */
public final class ContextOverflowException extends InferenceException {
  private final int required;
  private final int available;

  /** Creates an overflow failure retaining the original native call result. */
  public ContextOverflowException(int required, int available, NativeFailure nativeFailure) {
    super(
        ErrorCode.E302,
        "required=" + required + ", available=" + available,
        List.of(),
        null,
        nativeFailure);
    this.required = required;
    this.available = available;
  }

  /** Creates an overflow failure with the required and available token counts. */
  public ContextOverflowException(int required, int available) {
    super(ErrorCode.E302, "required=" + required + ", available=" + available, List.of());
    this.required = required;
    this.available = available;
  }

  /** Returns the number of token positions required by the attempted operation. */
  public int required() {
    return required;
  }

  /** Returns the token capacity available to the attempted operation. */
  public int available() {
    return available;
  }
}
