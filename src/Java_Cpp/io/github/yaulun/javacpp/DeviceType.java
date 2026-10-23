package io.github.yaulun.javacpp;

/** Backend device categories from the pinned ggml ABI. */
public enum DeviceType {
  CPU,
  GPU,
  IGPU,
  ACCEL,
  META,
  UNKNOWN;

  /** Converts a native device category; future values map to UNKNOWN. */
  public static DeviceType fromNative(int value) {
    return value >= 0 && value < 5 ? values()[value] : UNKNOWN;
  }
}
