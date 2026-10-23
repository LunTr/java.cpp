package io.github.yaulun.javacpp;

import io.github.yaulun.javacpp.exception.ContextOverflowException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Multi-turn facade with transactional history and complete-prompt prefill each turn.
 *
 * <p>The caller owns context and sampler. Concurrent operations and callback reentry are rejected.
 * Failed turns leave the complete previous history intact, including turns tentatively trimmed.
 */
public final class ChatSession {
  public static final String THINK_OFF_SUFFIX = "<think>\n\n</think>\n\n";
  public static final String THINK_ON_SUFFIX = "<think>\n";
  private final LlamaContext context;
  private final Sampler sampler;
  private final ChatTemplate template;
  private final ReentrantLock lock = new ReentrantLock();
  private List<ChatMessage> history = List.of();
  private String systemPrompt;
  private ThinkingMode mode = ThinkingMode.OFF;
  private GenerationConfig config = GenerationConfig.defaults();

  /** Creates a session borrowing the supplied context, sampler and template. */
  public ChatSession(LlamaContext context, Sampler sampler, ChatTemplate template) {
    this.context = Objects.requireNonNull(context);
    this.sampler = Objects.requireNonNull(sampler);
    this.template = Objects.requireNonNull(template);
  }

  /** Sets a zero-free system prompt; null removes it. */
  public ChatSession systemPrompt(String text) {
    return guarded(
        () -> {
          systemPrompt = text == null ? null : Checks.text(text, "text");
          return this;
        });
  }

  /** Selects reasoning; ON requires an explicitly supported template switch. */
  public ChatSession thinking(ThinkingMode mode) {
    Objects.requireNonNull(mode);
    return guarded(
        () -> {
          if (mode == ThinkingMode.ON && !template.supportsThinkingSwitch()) {
            throw new IllegalStateException("Template does not advertise enable_thinking");
          }
          this.mode = mode;
          return this;
        });
  }

  /** Sets generation limits and cancellation. */
  public ChatSession generationConfig(GenerationConfig config) {
    Objects.requireNonNull(config);
    return guarded(
        () -> {
          this.config = config;
          return this;
        });
  }

  /** Sends one nonblank user turn; trims complete old turns while reserving output capacity. */
  public ChatReply send(String userText, TokenListener listener) {
    Checks.text(userText, "userText");
    Objects.requireNonNull(listener, "listener");
    if (userText.isBlank()) {
      throw new IllegalArgumentException("userText must not be blank");
    }
    return guarded(
        () -> {
          List<ChatMessage> candidate = new ArrayList<>(history);
          candidate.add(ChatMessage.user(userText));
          int capacity = context.contextLength();
          int reserve = Math.min(config.maxTokens(), capacity / 2);
          int dropped = 0;
          int[] tokens;
          while (true) {
            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null) {
              messages.add(ChatMessage.system(systemPrompt));
            }
            messages.addAll(candidate);
            String prompt = template.apply(messages, true);
            if (template.supportsThinkingSwitch()) {
              // Some native renderers already emit an open reasoning tag.
              if (prompt.endsWith(THINK_ON_SUFFIX)) {
                if (mode == ThinkingMode.OFF) {
                  prompt += "\n</think>\n\n";
                }
              } else {
                prompt += mode == ThinkingMode.ON ? THINK_ON_SUFFIX : THINK_OFF_SUFFIX;
              }
            }
            tokens = context.vocab().tokenize(prompt, true, true);
            if (tokens.length <= capacity - reserve) {
              break;
            }
            if (candidate.size() <= 1) {
              throw new ContextOverflowException(tokens.length, capacity - reserve);
            }
            candidate.subList(0, 2).clear();
            dropped++;
          }
          context.clear();
          sampler.reset();
          ThinkingFilter filter = new ThinkingFilter(mode, listener);
          GenerationResult result = context.generate(tokens, sampler, config, filter);
          filter.finish();
          String content = filter.content().strip();
          if (result.stopReason() != StopReason.CANCELLED) {
            candidate.add(ChatMessage.assistant(content));
            history = List.copyOf(candidate);
          }
          return new ChatReply(content, filter.reasoning().strip(), result, dropped);
        });
  }

  /** Returns an immutable snapshot without the system prompt or reasoning. */
  public List<ChatMessage> history() {
    return guarded(() -> history);
  }

  /** Clears context and sampler before committing an empty history. */
  public void reset() {
    guarded(
        () -> {
          context.clear();
          sampler.reset();
          history = List.of();
          return null;
        });
  }

  private <T> T guarded(Supplier<T> action) {
    if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
      throw new IllegalStateException("ChatSession already in use");
    }
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }
}
