package io.github.yaulun.javacpp;

/** Receives complete UTF-8 text pieces on the generating thread; exceptions propagate to caller. */
@FunctionalInterface
public interface TokenListener {
  /** A listener that discards text. */
  TokenListener NONE = piece -> {};

  /** Receives a complete text piece; reentering context/session operations is rejected. */
  void onToken(String piece);

  /** Receives reasoning when emitted by a ChatSession; discarded by default. */
  default void onReasoning(String piece) {}
}
