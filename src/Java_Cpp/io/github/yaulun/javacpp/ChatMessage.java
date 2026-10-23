package io.github.yaulun.javacpp;

import java.util.Objects;

/** Immutable chat message with a validated, zero-free content string. */
public record ChatMessage(Role role, String content) {
  /** Rejects null values and embedded C string terminators. */
  public ChatMessage {
    Objects.requireNonNull(role, "role");
    Checks.text(content, "content");
  }

  /** Creates a system message. */
  public static ChatMessage system(String content) {
    return new ChatMessage(Role.SYSTEM, content);
  }

  /** Creates a user message. */
  public static ChatMessage user(String content) {
    return new ChatMessage(Role.USER, content);
  }

  /** Creates an assistant message. */
  public static ChatMessage assistant(String content) {
    return new ChatMessage(Role.ASSISTANT, content);
  }
}
