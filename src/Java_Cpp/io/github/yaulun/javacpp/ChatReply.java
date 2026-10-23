package io.github.yaulun.javacpp;

/** Completed turn including separated reasoning, timings and the count of discarded old turns. */
public record ChatReply(
    String content, String reasoning, GenerationResult stats, int droppedTurns) {}
