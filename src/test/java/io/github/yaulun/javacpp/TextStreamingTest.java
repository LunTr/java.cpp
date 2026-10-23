package io.github.yaulun.javacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TextStreamingTest {
  @Test
  void preservesEveryUtf8SplitIncludingEmoji() {
    String text = "你好，世界！Hello 🙂𠮷\n";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    for (int split = 0; split <= bytes.length; split++) {
      Utf8StreamDecoder decoder = new Utf8StreamDecoder();
      String actual =
          decoder.decode(Arrays.copyOfRange(bytes, 0, split))
              + decoder.decode(Arrays.copyOfRange(bytes, split, bytes.length))
              + decoder.finish();
      assertEquals(text, actual, "split=" + split);
      assertEquals(0, decoder.pendingBytes());
    }
    Utf8StreamDecoder decoder = new Utf8StreamDecoder();
    StringBuilder result = new StringBuilder();
    for (byte value : bytes) {
      result.append(decoder.decode(new byte[] {value}));
    }
    result.append(decoder.finish());
    assertEquals(text, result.toString());
  }

  @Test
  void replacesInvalidAndIncompleteSequencesAndFinishesOnce() {
    Utf8StreamDecoder decoder = new Utf8StreamDecoder();
    assertEquals("", decoder.decode(new byte[] {(byte) 0xe4}));
    assertEquals(1, decoder.pendingBytes());
    assertEquals("�", decoder.finish());
    assertEquals("", decoder.finish());
    assertThrows(IllegalStateException.class, () -> decoder.decode(new byte[0]));
    assertEquals("�", new Utf8StreamDecoder().decode(new byte[] {(byte) 0xff}));
  }

  @Test
  void splitsReasoningTagsAtEveryCharacter() {
    String text = "  <think>想一想🙂</think>\n\n你好";
    for (ThinkingMode mode : ThinkingMode.values()) {
      for (int split = 0; split <= text.length(); split++) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ThinkingFilter filter =
            new ThinkingFilter(
                mode,
                new TokenListener() {
                  @Override
                  public void onToken(String piece) {
                    content.append(piece);
                  }

                  @Override
                  public void onReasoning(String piece) {
                    reasoning.append(piece);
                  }
                });
        filter.onToken(text.substring(0, split));
        filter.onToken(text.substring(split));
        filter.finish();
        assertEquals("你好", content.toString());
        assertEquals("想一想🙂", reasoning.toString());
      }
    }
  }

  @Test
  void supportsImplicitOpenAndReasoningOnlyResponses() {
    ThinkingFilter filter = new ThinkingFilter(ThinkingMode.ON, TokenListener.NONE);
    filter.onToken("reason</thi");
    filter.onToken("nk>  answer");
    filter.finish();
    assertEquals("reason", filter.reasoning());
    assertEquals("answer", filter.content());
    ThinkingFilter plain = new ThinkingFilter(ThinkingMode.OFF, TokenListener.NONE);
    plain.onToken(" hello");
    plain.finish();
    assertEquals("hello", plain.content());
    ThinkingFilter only = new ThinkingFilter(ThinkingMode.ON, TokenListener.NONE);
    only.onToken("reason</thi");
    only.finish();
    assertEquals("reason</thi", only.reasoning());
  }
}
