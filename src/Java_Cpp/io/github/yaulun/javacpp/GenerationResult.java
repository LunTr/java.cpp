package io.github.yaulun.javacpp;

import java.util.Locale;

/** Generated text, counts and separate prefill/generation durations in nanoseconds. */
public record GenerationResult(
    String text,
    int promptTokens,
    int generatedTokens,
    StopReason stopReason,
    long promptNanos,
    long generationNanos) {
  /** Returns prefill throughput, or zero when no prefill duration was measured. */
  public double promptTokensPerSecond() {
    return promptNanos <= 0 ? 0 : promptTokens * 1_000_000_000.0 / promptNanos;
  }

  /** Returns generation throughput, or zero when no generation duration was measured. */
  public double tokensPerSecond() {
    return generationNanos <= 0 ? 0 : generatedTokens * 1_000_000_000.0 / generationNanos;
  }

  /** Returns a locale-independent timing summary. */
  public String summary() {
    return String.format(
        Locale.ROOT,
        "prompt %d tok (%.1f tok/s) | generated %d tok (%.1f tok/s) | %s",
        promptTokens,
        promptTokensPerSecond(),
        generatedTokens,
        tokensPerSecond(),
        stopReason);
  }
}
