package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * OpenAI response conversion functions from llama-cpp-python 0.3.36. Nonstream calls are stateless;
 * each stream iterator is owned by its consumer. Malformed dictionary fields raise PythonException
 * with the upstream category and message. Inputs may contain null; keep them stable during a call.
 */
public final class PythonChatCompletions {
  private PythonChatCompletions() {}

  /**
   * Ports upstream _convert_text_completion_logprobs_to_chat with its original field and separator
   * rules.
   */
  public static Map<String, Object> convertTextCompletionLogprobsToChat(Object logprobs) {
    if (logprobs == null) return null;
    // Python evaluates all zip arguments before attempting to iterate them.
    Object rawTokens = get(logprobs, "tokens");
    Object rawProbabilities = get(logprobs, "token_logprobs");
    Object rawTops = get(logprobs, "top_logprobs");
    List<?> tokens = list(rawTokens);
    List<?> probabilities = list(rawProbabilities);
    List<?> tops = list(rawTops);
    List<Object> content = new ArrayList<>();
    int count = Math.min(tokens.size(), Math.min(probabilities.size(), tops.size()));
    for (int i = 0; i < count; i++) {
      Object top = tops.get(i);
      if (!(top instanceof Map<?, ?> entries)) {
        throw error(
            PythonErrorType.ATTRIBUTE_ERROR,
            "'" + typeName(top) + "' object has no attribute 'items'");
      }
      List<Object> topLogprobs = new ArrayList<>();
      for (var entry : entries.entrySet()) {
        topLogprobs.add(dict("token", entry.getKey(), "logprob", entry.getValue(), "bytes", null));
      }
      content.add(
          dict(
              "token",
              tokens.get(i),
              "bytes",
              null,
              "logprob",
              probabilities.get(i),
              "top_logprobs",
              topLogprobs));
    }
    return dict("content", content, "refusal", null);
  }

  /**
   * Ports upstream _convert_text_completion_to_chat with its original field and separator rules.
   */
  public static Map<String, Object> convertTextCompletionToChat(Map<String, ?> completion) {
    if (!completion.containsKey("usage")) throw error(PythonErrorType.ASSERTION_ERROR, "");
    String id = "chat" + concat(get(completion, "id"));
    Object created = get(completion, "created");
    Object model = get(completion, "model");
    Object text = get(first(get(completion, "choices")), "text");
    Object logprobs =
        convertTextCompletionLogprobsToChat(get(first(get(completion, "choices")), "logprobs"));
    Object finish = get(first(get(completion, "choices")), "finish_reason");
    return dict(
        "id",
        id,
        "object",
        "chat.completion",
        "created",
        created,
        "model",
        model,
        "choices",
        new ArrayList<>(
            List.of(
                dict(
                    "index",
                    0,
                    "message",
                    dict("role", "assistant", "content", text),
                    "logprobs",
                    logprobs,
                    "finish_reason",
                    finish))),
        "usage",
        get(completion, "usage"));
  }

  /** Lazily emits the initial role chunk, then each input chunk's content or terminal delta. */
  public static Iterator<Map<String, Object>> convertTextCompletionChunksToChat(
      Iterator<?> chunks) {
    return new Iterator<>() {
      private Object pending;
      private boolean hasPending;
      private boolean first = true;
      private boolean closed;

      @Override
      public boolean hasNext() {
        return !closed && (hasPending || chunks.hasNext());
      }

      @Override
      public Map<String, Object> next() {
        if (!hasNext()) throw new NoSuchElementException();
        try {
          if (!hasPending) {
            pending = chunks.next();
            hasPending = true;
          }
          if (first) {
            first = false;
            return chunk(pending, true);
          }
          Map<String, Object> result = chunk(pending, false);
          hasPending = false;
          pending = null;
          return result;
        } catch (RuntimeException failure) {
          closed = true;
          pending = null;
          throw failure;
        }
      }
    };
  }

  private static Map<String, Object> chunk(Object chunk, boolean role) {
    String id = "chat" + concat(get(chunk, "id"));
    Object model = get(chunk, "model");
    Object created = get(chunk, "created");
    Map<String, Object> delta;
    Object logprobs = null;
    Object finish = null;
    if (role) delta = dict("role", "assistant");
    else {
      Object condition = get(first(get(chunk, "choices")), "finish_reason");
      delta =
          condition == null ? dict("content", get(first(get(chunk, "choices")), "text")) : dict();
      logprobs = convertTextCompletionLogprobsToChat(get(first(get(chunk, "choices")), "logprobs"));
      finish = get(first(get(chunk, "choices")), "finish_reason");
    }
    return dict(
        "id",
        id,
        "model",
        model,
        "created",
        created,
        "object",
        "chat.completion.chunk",
        "choices",
        new ArrayList<>(
            List.of(
                dict("index", 0, "delta", delta, "logprobs", logprobs, "finish_reason", finish))));
  }

  /** The nonstream branch accepts a dictionary; the stream branch accepts an Iterator. */
  public static Object convertCompletionToChat(Object completionOrChunks, boolean stream) {
    if (stream) return convertTextCompletionChunksToChat((Iterator<?>) completionOrChunks);
    @SuppressWarnings("unchecked")
    Map<String, ?> completion = (Map<String, ?>) completionOrChunks;
    return convertTextCompletionToChat(completion);
  }
}
