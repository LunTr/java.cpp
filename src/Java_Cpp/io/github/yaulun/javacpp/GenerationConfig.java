package io.github.yaulun.javacpp;

import java.util.function.BooleanSupplier;

/** Generation limit and cooperative cancellation checked before prefill and between tokens. */
public record GenerationConfig(int maxTokens, BooleanSupplier cancel) {
  /** Requires maxTokens at least one; a null cancellation supplier means never cancel. */
  public GenerationConfig {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be >= 1");
    }
    if (cancel == null) {
      cancel = () -> false;
    }
  }

  /** Returns a 512-token generation limit. */
  public static GenerationConfig defaults() {
    return new GenerationConfig(512, null);
  }

  /** Returns a new configuration retaining this cancellation supplier. */
  public GenerationConfig withMaxTokens(int maxTokens) {
    return new GenerationConfig(maxTokens, cancel);
  }
}
