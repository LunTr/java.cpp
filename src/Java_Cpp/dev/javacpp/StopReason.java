package dev.javacpp;

/** Reason the generation loop stopped. */
public enum StopReason {
  END_OF_GENERATION,
  MAX_TOKENS,
  CONTEXT_FULL,
  CANCELLED
}
