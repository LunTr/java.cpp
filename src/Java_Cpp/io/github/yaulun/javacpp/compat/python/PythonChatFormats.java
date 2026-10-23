package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Static chat functions from llama-cpp-python 0.3.36. Calls are independent and thread safe when
 * callers keep input collections stable during each call. Message dictionaries use role/content
 * keys; malformed message fields raise PythonException with the original category and message.
 */
public final class PythonChatFormats {
  private PythonChatFormats() {}

  /**
   * Ports upstream guess_chat_format_from_gguf_metadata with its original field and separator
   * rules.
   */
  public static String guessChatFormatFromGgufMetadata(Map<String, ?> metadata) {
    Object template = metadata.get("tokenizer.chat_template");
    if (PythonChatTemplateConstants.CHATML_CHAT_TEMPLATE.equals(template)) return "chatml";
    if (PythonChatTemplateConstants.MISTRAL_INSTRUCT_CHAT_TEMPLATE.equals(template)
        || PythonChatTemplateConstants.MIXTRAL_INSTRUCT_CHAT_TEMPLATE.equals(template))
      return "mistral-instruct";
    if (PythonChatTemplateConstants.LLAMA3_INSTRUCT_CHAT_TEMPLATE.equals(template))
      return "llama-3";
    return null;
  }

  /** Mapped role and nullable string content, corresponding to an upstream two-element tuple. */
  public record RoleMessage(String role, String content) {}

  /** Ports upstream _get_system_message with its original field and separator rules. */
  public static Object getSystemMessage(List<?> messages) {
    for (Object message : messages) {
      if ("system".equals(get(message, "role"))) {
        Object content = get(message, "content");
        return truth(content) ? content : "";
      }
    }
    return "";
  }

  /** Ports upstream _map_roles with its original field and separator rules. */
  public static List<RoleMessage> mapRoles(List<?> messages, Map<String, String> roleMap) {
    List<RoleMessage> output = new ArrayList<>();
    for (Object message : messages) {
      Object role = get(message, "role");
      if (role instanceof List<?> || role instanceof Map<?, ?>) {
        throw error(PythonErrorType.TYPE_ERROR, "unhashable type: '" + typeName(role) + "'");
      }
      if (role instanceof String && roleMap.containsKey(role)) {
        Object content = get(message, "content");
        output.add(new RoleMessage(roleMap.get(role), content instanceof String s ? s : null));
      }
    }
    return output;
  }

  /** Ports upstream _format_llama2 with its original field and separator rules. */
  public static String formatLlama2Prompt(
      String system, List<RoleMessage> messages, String sep, String sep2) {
    StringBuilder result = new StringBuilder(system).append(sep);
    for (int i = 0; i < messages.size(); i++) {
      RoleMessage item = messages.get(i);
      String end = i % 2 == 0 ? sep : sep2;
      if (!system.isEmpty() && i == 0)
        result.append(truth(item.content()) ? item.content() : "").append(end);
      else if (truth(item.content()))
        result.append(item.role()).append(item.content()).append(' ').append(end);
      else result.append(item.role()).append(' ');
    }
    return result.toString();
  }

  /** Ports upstream _format_add_colon_single with its original field and separator rules. */
  public static String formatAddColonSingle(String system, List<RoleMessage> messages, String sep) {
    return colon(system, messages, sep, sep, false);
  }

  /** Ports upstream _format_add_colon_two with its original field and separator rules. */
  public static String formatAddColonTwo(
      String system, List<RoleMessage> messages, String sep, String sep2) {
    return colon(system, messages, sep, sep2, false);
  }

  /** Ports upstream _format_add_colon_space_single with its original field and separator rules. */
  public static String formatAddColonSpaceSingle(
      String system, List<RoleMessage> messages, String sep) {
    return colon(system, messages, sep, sep, true);
  }

  private static String colon(
      String system, List<RoleMessage> messages, String sep, String sep2, boolean space) {
    StringBuilder result = new StringBuilder(system).append(sep);
    for (int i = 0; i < messages.size(); i++) {
      RoleMessage item = messages.get(i);
      result.append(item.role()).append(':');
      if (truth(item.content()))
        result.append(' ').append(item.content()).append(i % 2 == 0 ? sep : sep2);
      else if (space) result.append(' ');
    }
    return result.toString();
  }

  /** Ports upstream _format_no_colon_single with its original field and separator rules. */
  public static String formatNoColonSingle(String system, List<RoleMessage> messages, String sep) {
    StringBuilder result = new StringBuilder(system);
    for (RoleMessage item : messages) {
      result.append(item.role());
      if (truth(item.content())) result.append(item.content()).append(sep);
    }
    return result.toString();
  }

  /** Ports upstream _format_chatml with its original field and separator rules. */
  public static String formatChatmlPrompt(String system, List<RoleMessage> messages, String sep) {
    StringBuilder result = new StringBuilder();
    if (!system.isEmpty()) result.append(system).append(sep).append('\n');
    for (RoleMessage item : messages) {
      result.append(item.role()).append('\n');
      if (truth(item.content())) result.append(item.content()).append(sep).append('\n');
    }
    return result.toString();
  }

  /** Ports upstream _format_chatglm3 with its original field and separator rules. */
  public static String formatChatglm3Prompt(String system, List<RoleMessage> messages, String sep) {
    StringBuilder result = new StringBuilder(system);
    for (RoleMessage item : messages) {
      result.append(item.role());
      if (truth(item.content())) result.append("\n ").append(item.content());
    }
    return result.toString();
  }

  private static List<RoleMessage> mapped(
      List<?> messages, String user, String assistant, boolean append) {
    List<RoleMessage> result = mapRoles(messages, Map.of("user", user, "assistant", assistant));
    if (append) result.add(new RoleMessage(assistant, null));
    return result;
  }

  private static PythonChatFormatterResponse response(String prompt) {
    return new PythonChatFormatterResponse(prompt, null);
  }

  /** Ports upstream format_llama2 with its original field and separator rules. */
  public static PythonChatFormatterResponse formatLlama2(List<?> messages) {
    List<RoleMessage> mapped = mapped(messages, "<s>[INST]", "[/INST]", false);
    Object system = getSystemMessage(messages);
    String text = truth(system) ? "[INST] <<SYS>>\n" + string(system) + "\n<</SYS>>" : "";
    return response(formatLlama2Prompt(text, mapped, " ", "</s>") + "[/INST]");
  }

  /** Ports upstream format_llama3 with its original field and separator rules. */
  public static PythonChatFormatterResponse formatLlama3(List<?> messages) {
    List<RoleMessage> mapped =
        mapRoles(
            messages,
            Map.of(
                "system", "<|start_header_id|>system<|end_header_id|>\n\n",
                "user", "<|start_header_id|>user<|end_header_id|>\n\n",
                "assistant", "<|start_header_id|>assistant<|end_header_id|>\n\n"));
    mapped.add(new RoleMessage("<|start_header_id|>assistant<|end_header_id|>\n\n", null));
    return new PythonChatFormatterResponse(
        formatNoColonSingle("", mapped, "<|eot_id|>"), "<|eot_id|>");
  }

  /** Ports upstream format_alpaca with its original field and separator rules. */
  public static PythonChatFormatterResponse formatAlpaca(List<?> messages) {
    Object system = getSystemMessage(messages);
    List<RoleMessage> mapped = mapped(messages, "### Instruction", "### Response", false);
    return response(formatAddColonTwo(leftString(system), mapped, "\n\n", "</s>"));
  }

  /** Ports upstream format_qwen with its original field and separator rules. */
  public static PythonChatFormatterResponse formatQwen(List<?> messages) {
    Object system = getSystemMessage(messages);
    String text =
        "<|im_start|>system\n" + (truth(system) ? string(system) : "You are a helpful assistant.");
    return new PythonChatFormatterResponse(
        formatChatmlPrompt(
            text,
            mapped(messages, "<|im_start|>user", "<|im_start|>assistant", true),
            "<|im_end|>"),
        "<|endoftext|>");
  }

  /** Ports upstream format with its original field and separator rules. */
  public static PythonChatFormatterResponse formatVicuna(List<?> messages) {
    String system =
        "A chat between a curious user and an artificial intelligence assistant. The assistant gives helpful, detailed, and polite answers to the user's questions.";
    return response(
        formatAddColonTwo(system, mapped(messages, "USER", "ASSISTANT", true), " ", "</s>"));
  }

  /** Ports upstream format_oasst_llama with its original field and separator rules. */
  public static PythonChatFormatterResponse formatOasstLlama(List<?> messages) {
    String system = "[INST] <<SYS>>\n" + string(getSystemMessage(messages)) + "\n<</SYS>>\n\n";
    return response(
        formatNoColonSingle(
            system, mapped(messages, "<|prompter|>", "<|assistant|>", true), "</s>"));
  }

  /** Ports upstream format_baichuan2 with its original field and separator rules. */
  public static PythonChatFormatterResponse formatBaichuan2(List<?> messages) {
    String system = string(getSystemMessage(messages));
    return response(
        formatNoColonSingle(
            system, mapped(messages, "<reserved_106>", "<reserved_107>", true), ""));
  }

  /** Ports upstream format_baichuan with its original field and separator rules. */
  public static PythonChatFormatterResponse formatBaichuan(List<?> messages) {
    String system = string(getSystemMessage(messages));
    return response(
        formatNoColonSingle(
            system, mapped(messages, "<reserved_102>", "<reserved_103>", true), ""));
  }

  /** Ports upstream format_openbuddy with its original field and separator rules. */
  public static PythonChatFormatterResponse formatOpenbuddy(List<?> messages) {
    String system =
        "You are a helpful, respectful and honest INTP-T AI Assistant named Buddy. You are talking to a human User.\n"
            + "Always answer as helpfully and logically as possible, while being safe. Your answers should not include any harmful, political, religious, unethical, racist, sexist, toxic, dangerous, or illegal content. Please ensure that your responses are socially unbiased and positive in nature.\n"
            + "If a question does not make any sense, or is not factually coherent, explain why instead of answering something not correct. If you don't know the answer to a question, please don't share false information.\n"
            + "You can speak fluently in many languages, for example: English, Chinese.\n"
            + "You cannot access the internet, but you have vast knowledge, cutoff: 2021-09.\n"
            + "You are trained by OpenBuddy team, (https://openbuddy.ai, https://github.com/OpenBuddy/OpenBuddy), you are based on LLaMA and Falcon transformers model, not related to GPT or OpenAI.\n\n";
    return response(
        formatAddColonSingle(system, mapped(messages, "User", "Assistant", true), "\n"));
  }

  /** Ports upstream format_redpajama_incite with its original field and separator rules. */
  public static PythonChatFormatterResponse formatRedpajamaIncite(List<?> messages) {
    Object system = getSystemMessage(messages);
    List<RoleMessage> mapped = mapped(messages, "<human>", "<bot>", true);
    return new PythonChatFormatterResponse(
        formatAddColonSingle(leftString(system), mapped, "\n"), "<human>");
  }

  /** Ports upstream format_snoozy with its original field and separator rules. */
  public static PythonChatFormatterResponse formatSnoozy(List<?> messages) {
    Object system = getSystemMessage(messages);
    if ("".equals(system))
      system =
          "The prompt below is a question to answer, a task to complete, or a conversation to respond to; decide which and write an appropriate response.";
    List<RoleMessage> mapped = mapped(messages, "### Prompt", "### Response", true);
    return new PythonChatFormatterResponse(
        formatAddColonSingle(leftString(system), mapped, "\n"), "###");
  }

  /** Ports upstream format_phind with its original field and separator rules. */
  public static PythonChatFormatterResponse formatPhind(List<?> messages) {
    return response(
        formatAddColonSingle(
            "### System Prompt\nYou are an intelligent programming assistant.",
            mapped(messages, "### User Message", "### Assistant", true),
            "\n\n"));
  }

  /** Ports upstream format_intel with its original field and separator rules. */
  public static PythonChatFormatterResponse formatIntel(List<?> messages) {
    return response(
        formatAddColonSingle(
            "### System:\n{system_message}",
            mapped(messages, "### User:", "### Assistant:", true),
            "\n"));
  }

  /** Ports upstream format_open_orca with its original field and separator rules. */
  public static PythonChatFormatterResponse formatOpenOrca(List<?> messages) {
    String system =
        "You are a helpful assistant. Please answer truthfully and write out your "
            + "thinking step by step to be sure you get the right answer. If you make a mistake or encounter "
            + "an error in your thinking, say so out loud and attempt to correct it. If you don't know or "
            + "aren't sure about something, say so clearly. You will act as a professional logician, mathematician, "
            + "and physicist. You will also act as the most appropriate type of expert to answer any particular "
            + "question or solve the relevant problem; state which expert type your are, if so. Also think of "
            + "any particular named expert that would be ideal to answer the relevant question or solve the "
            + "relevant problem; name and act as them, if appropriate.";
    List<RoleMessage> mapped = mapRoles(messages, Map.of("User", "User", "Assistant", "Assistant"));
    mapped.add(new RoleMessage("Assistant", null));
    return new PythonChatFormatterResponse(
        formatAddColonSpaceSingle(system, mapped, "<|end_of_turn|>\n"), "User");
  }

  /** Ports upstream format_mistrallite with its original field and separator rules. */
  public static PythonChatFormatterResponse formatMistrallite(List<?> messages) {
    String system = "<|system|>" + string(getSystemMessage(messages)) + "</s>";
    return response(
        formatNoColonSingle(
            system, mapped(messages, "<|prompter|>", "</s>\n<|assistant|>", true), " "));
  }

  private static PythonChatFormatterResponse chatml(
      List<?> messages, String prefix, String user, String assistant, String sep) {
    String system = prefix + string(getSystemMessage(messages));
    return new PythonChatFormatterResponse(
        formatChatmlPrompt(system, mapped(messages, user, assistant, true), sep), sep);
  }

  /** Ports upstream format_zephyr with its original field and separator rules. */
  public static PythonChatFormatterResponse formatZephyr(List<?> messages) {
    return chatml(messages, "<|system|>\n", "<|user|>\n", "<|assistant|>\n", "</s>");
  }

  /** Ports upstream format_pygmalion with its original field and separator rules. */
  public static PythonChatFormatterResponse formatPygmalion(List<?> messages) {
    return chatml(messages, "<|system|>", "<|user|>", "<|model|>", "\n");
  }

  /** Ports upstream format_chatml with its original field and separator rules. */
  public static PythonChatFormatterResponse formatChatml(List<?> messages) {
    return chatml(
        messages,
        "<|im_start|>system\n",
        "<|im_start|>user",
        "<|im_start|>assistant",
        "<|im_end|>");
  }

  /** Ports upstream format_mistral_instruct with its original field and separator rules. */
  public static PythonChatFormatterResponse formatMistralInstruct(List<?> messages) {
    StringBuilder prompt = new StringBuilder();
    for (Object message : messages) {
      Object role = get(message, "role");
      if ("user".equals(role)) {
        Object content = get(message, "content");
        if (content instanceof String text) prompt.append("[INST] ").append(text);
      } else if ("assistant".equals(role)) {
        Object content = get(message, "content");
        if (content != null) prompt.append(" [/INST]").append(concat(content)).append("</s>");
      }
    }
    return new PythonChatFormatterResponse(prompt.append(" [/INST]").toString(), "</s>");
  }

  /** Ports upstream format_chatglm3 with its original field and separator rules. */
  public static PythonChatFormatterResponse formatChatglm3(List<?> messages) {
    String system = "<|system|>\n" + string(getSystemMessage(messages));
    return new PythonChatFormatterResponse(
        formatChatglm3Prompt(system, mapped(messages, "<|user|>", "<|assistant|>", true), "</s>"),
        "</s>");
  }

  /** Ports upstream format_openchat with its original field and separator rules. */
  public static PythonChatFormatterResponse formatOpenchat(List<?> messages) {
    String system = string(getSystemMessage(messages)) + "<|end_of_turn|>";
    return new PythonChatFormatterResponse(
        formatChatmlPrompt(
            system,
            mapped(
                messages, "GPT4 Correct User: ", "<|end_of_turn|>GPT4 Correct Assistant: ", true),
            "<|end_of_turn|>"),
        "<|end_of_turn|>");
  }

  /** Ports upstream format_saiga with its original field and separator rules. */
  public static PythonChatFormatterResponse formatSaiga(List<?> messages) {
    List<RoleMessage> mapped =
        mapRoles(messages, Map.of("user", "user", "bot", "bot", "system", "system"));
    StringBuilder prompt = new StringBuilder();
    for (RoleMessage item : mapped) {
      prompt.append("<s>").append(item.role()).append('\n');
      if (truth(item.content())) prompt.append(item.content()).append("</s>");
    }
    return response(prompt.append("<s>bot").toString());
  }

  /** Ports format_gemma with debug output disabled; use the overload to receive debug messages. */
  public static PythonChatFormatterResponse formatGemma(List<?> messages) {
    return formatGemma(messages, message -> {});
  }

  /** Receives the original logger.debug message when a nonempty system message is supplied. */
  public static PythonChatFormatterResponse formatGemma(
      List<?> messages, Consumer<String> debugLog) {
    if (!"".equals(getSystemMessage(messages))) {
      debugLog.accept("`role='system'` messages are not allowed on Google's Gemma models.");
    }
    String sep = "<end_of_turn>\n";
    return new PythonChatFormatterResponse(
        formatNoColonSingle(
            "", mapped(messages, "<start_of_turn>user\n", "<start_of_turn>model\n", true), sep),
        sep);
  }

  /** Dispatches the 23 static format names; unknown names raise Python ValueError. */
  public static PythonChatFormatterResponse format(String name, List<?> messages) {
    return switch (name) {
      case "llama-2" -> formatLlama2(messages);
      case "llama-3" -> formatLlama3(messages);
      case "alpaca" -> formatAlpaca(messages);
      case "qwen" -> formatQwen(messages);
      case "vicuna" -> formatVicuna(messages);
      case "oasst_llama" -> formatOasstLlama(messages);
      case "baichuan-2" -> formatBaichuan2(messages);
      case "baichuan" -> formatBaichuan(messages);
      case "openbuddy" -> formatOpenbuddy(messages);
      case "redpajama-incite" -> formatRedpajamaIncite(messages);
      case "snoozy" -> formatSnoozy(messages);
      case "phind" -> formatPhind(messages);
      case "intel" -> formatIntel(messages);
      case "open-orca" -> formatOpenOrca(messages);
      case "mistrallite" -> formatMistrallite(messages);
      case "zephyr" -> formatZephyr(messages);
      case "pygmalion" -> formatPygmalion(messages);
      case "chatml" -> formatChatml(messages);
      case "mistral-instruct" -> formatMistralInstruct(messages);
      case "chatglm3" -> formatChatglm3(messages);
      case "openchat" -> formatOpenchat(messages);
      case "saiga" -> formatSaiga(messages);
      case "gemma" -> formatGemma(messages);
      default -> throw error(PythonErrorType.VALUE_ERROR, "Unknown static chat format: " + name);
    };
  }
}
