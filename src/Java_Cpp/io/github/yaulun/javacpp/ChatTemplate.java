package io.github.yaulun.javacpp;

import com.sun.jna.Memory;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.TemplateException;
import io.github.yaulun.javacpp.internal.LlamaChatMessage;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import io.github.yaulun.javacpp.internal.NativeLogBridge;
import io.github.yaulun.javacpp.internal.NativeStrings;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Native built-in chat template renderer; C pointers never escape this class. */
public final class ChatTemplate {
  private final String source;
  private final LlamaLibrary library;

  private ChatTemplate(String source) {
    this.source = source;
    library = LlamaRuntime.get().library();
  }

  /** Copies the model template; missing templates raise E402. */
  public static ChatTemplate fromModel(LlamaModel model) {
    Objects.requireNonNull(model, "model");
    return new ChatTemplate(
        model
            .chatTemplate()
            .orElseThrow(
                () ->
                    new TemplateException(
                        ErrorCode.E402, model.info().file().toString(), List.of())));
  }

  /** Selects a native built-in template name; support is checked by apply. */
  public static ChatTemplate named(String builtinName) {
    Checks.text(builtinName, "builtinName");
    if (builtinName.isBlank()) {
      throw new IllegalArgumentException("builtinName is blank");
    }
    return new ChatTemplate(builtinName);
  }

  /** Returns the copied template source or built-in template name. */
  public String source() {
    return source;
  }

  /**
   * Renders a nonempty immutable snapshot and optionally appends the assistant generation prefix.
   */
  public String apply(List<ChatMessage> messages, boolean addAssistant) {
    NativeLogBridge.ensureOutsideCallback();
    List<ChatMessage> copy = List.copyOf(messages);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("messages must not be empty");
    }
    LlamaChatMessage[] nativeMessages =
        (LlamaChatMessage[]) new LlamaChatMessage().toArray(copy.size());
    List<Memory> strings = new ArrayList<>();
    try {
      for (int index = 0; index < copy.size(); index++) {
        Memory role = NativeStrings.toNativeMemory(copy.get(index).role().wireName());
        strings.add(role);
        Memory content = NativeStrings.toNativeMemory(copy.get(index).content());
        strings.add(content);
        nativeMessages[index].role = role;
        nativeMessages[index].content = content;
        nativeMessages[index].write();
      }
      byte[] buffer = new byte[512];
      int count =
          library.llama_chat_apply_template(
              source,
              nativeMessages,
              copy.size(),
              NativeStrings.cBool(addAssistant),
              buffer,
              buffer.length);
      if (count > buffer.length) {
        buffer = new byte[count];
        count =
            library.llama_chat_apply_template(
                source,
                nativeMessages,
                copy.size(),
                NativeStrings.cBool(addAssistant),
                buffer,
                buffer.length);
      }
      if (count < 0 || count > buffer.length) {
        throw new TemplateException(
            ErrorCode.E403,
            "Template render result=" + count,
            NativeLogBridge.instance().recentProblems());
      }
      return NativeStrings.fromBytes(buffer, count);
    } finally {
      Reference.reachabilityFence(strings);
      for (Memory memory : strings) {
        memory.close();
      }
    }
  }

  /** Returns whether the source advertises the supported Qwen-style reasoning switch. */
  public boolean supportsThinkingSwitch() {
    return source.contains("enable_thinking") && source.contains("<think>");
  }
}
