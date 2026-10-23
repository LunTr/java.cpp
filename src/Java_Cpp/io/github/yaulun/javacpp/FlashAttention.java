package io.github.yaulun.javacpp;

/** Flash attention selection passed to llama.cpp. */
public enum FlashAttention {
  AUTO(-1),
  DISABLED(0),
  ENABLED(1);
  private final int nativeValue;

  FlashAttention(int nativeValue) {
    this.nativeValue = nativeValue;
  }

  /** Returns the enum value defined by the pinned native ABI. */
  public int nativeValue() {
    return nativeValue;
  }
}
