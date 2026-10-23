package io.github.yaulun.javacpp.compat.python;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class PythonChatContractTest {
  @TestFactory
  Stream<DynamicTest> matchesWholeUpstreamFunctions() throws Exception {
    List<String> lines;
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    getClass().getResourceAsStream("/python-0.3.36-chat-contracts.tsv")),
                StandardCharsets.US_ASCII))) {
      lines = reader.lines().toList();
    }
    return java.util.stream.IntStream.range(0, lines.size())
        .mapToObj(
            i -> {
              String[] parts = lines.get(i).split("\t");
              return DynamicTest.dynamicTest(
                  (i + 1) + ":" + parts[0],
                  () -> {
                    Object argument =
                        read(
                            new DataInputStream(
                                new ByteArrayInputStream(Base64.getDecoder().decode(parts[1]))));
                    List<Object> yielded = new ArrayList<>();
                    List<String> logs = new ArrayList<>();
                    Map<String, Object> result;
                    try {
                      Object value = execute(parts[0], argument, logs);
                      if (value instanceof Iterator<?> iterator) {
                        while (iterator.hasNext()) yielded.add(iterator.next());
                        value = yielded;
                      }
                      if (value instanceof PythonChatFormatterResponse response) {
                        value =
                            PythonValues.dict(
                                "prompt",
                                response.prompt(),
                                "stop",
                                response.stop(),
                                "stopping_criteria",
                                response.stoppingCriteria(),
                                "added_special",
                                response.addedSpecial());
                      }
                      result = PythonValues.dict("value", value, "logs", logs);
                    } catch (PythonException error) {
                      result =
                          PythonValues.dict(
                              "error",
                              error.type().pythonName(),
                              "message",
                              error.getMessage(),
                              "yielded",
                              yielded,
                              "logs",
                              logs);
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    write(new DataOutputStream(bytes), result);
                    assertEquals(
                        parts[2],
                        Base64.getEncoder().encodeToString(bytes.toByteArray()),
                        "operation=" + parts[0] + ", input=" + argument + ", result=" + result);
                  });
            });
  }

  @SuppressWarnings("unchecked")
  private static Object execute(String kind, Object argument, List<String> logs) {
    if (kind.equals("pythonRepr")) return PythonValues.repr(argument);
    if (kind.startsWith("format:")) {
      String name = kind.substring(7);
      if (name.equals("gemma")) return PythonChatFormats.formatGemma((List<?>) argument, logs::add);
      return PythonChatFormats.format(name, (List<?>) argument);
    }
    if (kind.equals("guess_chat_format_from_gguf_metadata")) {
      return PythonChatFormats.guessChatFormatFromGgufMetadata((Map<String, ?>) argument);
    }
    if (kind.equals("_get_system_message"))
      return PythonChatFormats.getSystemMessage((List<?>) argument);
    if (kind.equals("_map_roles")) {
      List<?> args = (List<?>) argument;
      return PythonChatFormats.mapRoles((List<?>) args.get(0), (Map<String, String>) args.get(1))
          .stream()
          .map(item -> java.util.Arrays.asList(item.role(), item.content()))
          .toList();
    }
    if (kind.equals("_convert_text_completion_logprobs_to_chat")) {
      return PythonChatCompletions.convertTextCompletionLogprobsToChat(argument);
    }
    if (kind.equals("_convert_text_completion_to_chat")) {
      return PythonChatCompletions.convertTextCompletionToChat((Map<String, ?>) argument);
    }
    if (kind.equals("_convert_text_completion_chunks_to_chat")) {
      return PythonChatCompletions.convertTextCompletionChunksToChat(
          ((List<?>) argument).iterator());
    }
    if (kind.startsWith("_convert_completion_to_chat:")) {
      boolean stream = kind.endsWith(":true");
      return PythonChatCompletions.convertCompletionToChat(
          stream ? ((List<?>) argument).iterator() : argument, stream);
    }
    List<?> args = (List<?>) argument;
    String system = (String) args.get(0);
    List<PythonChatFormats.RoleMessage> messages =
        ((List<?>) args.get(1))
            .stream()
                .map(
                    value -> {
                      List<?> pair = (List<?>) value;
                      return new PythonChatFormats.RoleMessage(
                          (String) pair.get(0), (String) pair.get(1));
                    })
                .toList();
    String sep = (String) args.get(2);
    return switch (kind) {
      case "_format_llama2" ->
          PythonChatFormats.formatLlama2Prompt(system, messages, sep, (String) args.get(3));
      case "_format_add_colon_single" ->
          PythonChatFormats.formatAddColonSingle(system, messages, sep);
      case "_format_add_colon_two" ->
          PythonChatFormats.formatAddColonTwo(system, messages, sep, (String) args.get(3));
      case "_format_no_colon_single" ->
          PythonChatFormats.formatNoColonSingle(system, messages, sep);
      case "_format_add_colon_space_single" ->
          PythonChatFormats.formatAddColonSpaceSingle(system, messages, sep);
      case "_format_chatml" -> PythonChatFormats.formatChatmlPrompt(system, messages, sep);
      case "_format_chatglm3" -> PythonChatFormats.formatChatglm3Prompt(system, messages, sep);
      default -> throw new AssertionError(kind);
    };
  }

  private static Object read(DataInputStream input) throws IOException {
    int tag = input.readUnsignedByte();
    return switch (tag) {
      case 'n' -> null;
      case 'f' -> false;
      case 't' -> true;
      case 'i' -> {
        long number = input.readLong();
        yield number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
            ? (Object) (int) number
            : number;
      }
      case 'd' -> input.readDouble();
      case 's' -> {
        char[] chars = new char[input.readInt()];
        for (int i = 0; i < chars.length; i++) chars[i] = input.readChar();
        yield new String(chars);
      }
      case 'l' -> {
        int count = input.readInt();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) values.add(read(input));
        yield values;
      }
      case 'm' -> {
        int count = input.readInt();
        Map<Object, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) values.put(read(input), read(input));
        yield values;
      }
      default -> throw new IOException("Unknown fixture tag " + tag);
    };
  }

  private static void write(DataOutputStream output, Object value) throws IOException {
    if (value == null) output.writeByte('n');
    else if (value instanceof Boolean flag) output.writeByte(flag ? 't' : 'f');
    else if (value instanceof String text) {
      output.writeByte('s');
      output.writeInt(text.length());
      output.writeChars(text);
    } else if (value instanceof Double || value instanceof Float) {
      output.writeByte('d');
      output.writeDouble(((Number) value).doubleValue());
    } else if (value instanceof Number number) {
      output.writeByte('i');
      output.writeLong(number.longValue());
    } else if (value instanceof List<?> values) {
      output.writeByte('l');
      output.writeInt(values.size());
      for (Object item : values) write(output, item);
    } else if (value instanceof Map<?, ?> values) {
      output.writeByte('m');
      output.writeInt(values.size());
      for (var entry : values.entrySet()) {
        write(output, entry.getKey());
        write(output, entry.getValue());
      }
    } else throw new IOException("Unsupported fixture value " + value);
  }

  @Test
  void streamingDefersChoiceAccessAndObservesMutationsAfterRoleYield() {
    Map<String, Object> chunk = PythonValues.dict("id", "x", "model", "model", "created", 1);
    int[] calls = {0};
    Iterator<Object> source =
        new Iterator<>() {
          @Override
          public boolean hasNext() {
            return calls[0] == 0;
          }

          @Override
          public Object next() {
            calls[0]++;
            return chunk;
          }
        };
    var stream = PythonChatCompletions.convertTextCompletionChunksToChat(source);
    assertTrue(stream.hasNext());
    assertEquals(0, calls[0]);
    assertEquals("chatx", stream.next().get("id"));
    chunk.put("id", "changed");
    chunk.put(
        "choices",
        List.of(PythonValues.dict("finish_reason", null, "text", "new", "logprobs", null)));
    assertEquals("chatchanged", stream.next().get("id"));
    assertFalse(stream.hasNext());
  }

  @Test
  void streamingClosesAfterPythonErrorAndCompletionKeepsUsageIdentity() {
    var stream =
        PythonChatCompletions.convertTextCompletionChunksToChat(List.of(Map.of()).iterator());
    assertEquals(
        PythonErrorType.KEY_ERROR, assertThrows(PythonException.class, stream::next).type());
    assertFalse(stream.hasNext());
    Map<String, Object> usage = new LinkedHashMap<>();
    Map<String, Object> completion =
        PythonValues.dict(
            "id",
            "x",
            "created",
            1,
            "model",
            "model",
            "choices",
            List.of(PythonValues.dict("text", "text", "logprobs", null, "finish_reason", "stop")),
            "usage",
            usage);
    var response = PythonChatCompletions.convertTextCompletionToChat(completion);
    assertSame(usage, response.get("usage"));
    assertEquals(
        "text",
        ((Map<?, ?>) ((Map<?, ?>) ((List<?>) response.get("choices")).getFirst()).get("message"))
            .get("content"));
  }
}
