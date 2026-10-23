package io.github.yaulun.javacpp;

import java.util.Objects;

final class Checks {
  private Checks() {}

  static String text(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " contains NUL");
    }
    return value;
  }

  static int range(int value, int min, int max, String name) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]");
    }
    return value;
  }

  static float finite(float value, String name) {
    if (!Float.isFinite(value)) {
      throw new IllegalArgumentException(name + " must be finite");
    }
    return value;
  }
}
