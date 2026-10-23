package io.github.yaulun.javacpp;

import java.util.Objects;

/** Separates reasoning tags even when a tag spans multiple native token pieces. */
final class ThinkingFilter implements TokenListener {
  private enum State {
    START,
    REASONING,
    AFTER_CLOSE,
    CONTENT
  }

  private static final String OPEN = "<think>";
  private static final String CLOSE = "</think>";
  private final ThinkingMode mode;
  private final TokenListener downstream;
  private final StringBuilder pending = new StringBuilder();
  private final StringBuilder content = new StringBuilder();
  private final StringBuilder reasoning = new StringBuilder();
  private State state = State.START;

  ThinkingFilter(ThinkingMode mode, TokenListener downstream) {
    this.mode = Objects.requireNonNull(mode);
    this.downstream = Objects.requireNonNull(downstream);
  }

  @Override
  public void onToken(String piece) {
    pending.append(piece);
    drain(false);
  }

  void finish() {
    drain(true);
  }

  String content() {
    return content.toString();
  }

  String reasoning() {
    return reasoning.toString();
  }

  private void drain(boolean finishing) {
    while (!pending.isEmpty()) {
      if (state == State.START || state == State.AFTER_CLOSE) {
        while (!pending.isEmpty() && Character.isWhitespace(pending.charAt(0))) {
          pending.deleteCharAt(0);
        }
        if (pending.isEmpty()) {
          return;
        }
        if (state == State.AFTER_CLOSE) {
          state = State.CONTENT;
          continue;
        }
        String value = pending.toString();
        if (!finishing && OPEN.startsWith(value)) {
          return;
        }
        if (value.startsWith(OPEN)) {
          pending.delete(0, OPEN.length());
          state = State.REASONING;
        } else {
          state = mode == ThinkingMode.ON ? State.REASONING : State.CONTENT;
        }
      } else if (state == State.REASONING) {
        int end = pending.indexOf(CLOSE);
        if (end >= 0) {
          emitReasoning(pending.substring(0, end));
          pending.delete(0, end + CLOSE.length());
          state = State.AFTER_CLOSE;
        } else {
          int keep = 0;
          if (!finishing) {
            for (int length = 1; length < CLOSE.length() && length <= pending.length(); length++) {
              if (CLOSE.startsWith(pending.substring(pending.length() - length))) {
                keep = length;
              }
            }
          }
          int endIndex = pending.length() - keep;
          emitReasoning(pending.substring(0, endIndex));
          pending.delete(0, endIndex);
          return;
        }
      } else {
        String value = pending.toString();
        pending.setLength(0);
        content.append(value);
        downstream.onToken(value);
      }
    }
  }

  private void emitReasoning(String piece) {
    if (!piece.isEmpty()) {
      reasoning.append(piece);
      downstream.onReasoning(piece);
    }
  }
}
