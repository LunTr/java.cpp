package io.github.yaulun.javacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.Memory;
import io.github.yaulun.javacpp.internal.LlamaChatMessage;
import io.github.yaulun.javacpp.internal.LlamaContextParams;
import io.github.yaulun.javacpp.internal.LlamaModelParams;
import io.github.yaulun.javacpp.internal.LlamaSamplerChainParams;
import io.github.yaulun.javacpp.internal.NativeLogBridge;
import io.github.yaulun.javacpp.internal.NativeStrings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeBoundaryTest {
  @Test
  void validatesStringEncodingAndNativeOwnership() {
    try (Memory string = NativeStrings.toNativeMemory("中文🙂")) {
      assertEquals("中文🙂", NativeStrings.fromCString(string));
      assertEquals(0, string.getByte(string.size() - 1));
    }
    assertNull(NativeStrings.fromCString(null));
    assertThrows(IllegalArgumentException.class, () -> NativeStrings.toCString("bad\0text"));
    assertEquals("你好", NativeStrings.fromBytes("你好tail".getBytes(StandardCharsets.UTF_8), 6));
  }

  @Test
  void verifiesX64StructLayoutWithoutLoadingLlama() {
    assertEquals(80, new LlamaModelParams().size());
    assertEquals(168, new LlamaContextParams().size());
    assertEquals(1, new LlamaSamplerChainParams().size());
    assertEquals(16, new LlamaChatMessage().size());
    assertEquals(48, new LlamaModelParams().offsetOf("progress_callback"));
    assertEquals(160, new LlamaContextParams().offsetOf("ctx_other"));
  }

  @Test
  void isolatesCallbackFailuresAndRejectsNativeReentryBeforeLocks() {
    NativeLogBridge bridge = NativeLogBridge.instance();
    List<String> lines = new ArrayList<>();
    bridge.configure(
        LogLevel.WARN,
        (level, line) -> {
          lines.add(line);
          LlamaRuntime.get();
        });
    try (Memory first = NativeStrings.toNativeMemory("first");
        Memory continuation = NativeStrings.toNativeMemory(" line\nsecond\n")) {
      bridge.invoke(3, first, null);
      bridge.invoke(5, continuation, null);
    } finally {
      bridge.configure(LogLevel.WARN, null);
    }
    assertEquals(List.of("first line", "second"), lines);
    assertTrue(
        bridge.recentProblems().stream().anyMatch(line -> line.contains("Log listener failed")));
    for (int index = 0; index < 50; index++) {
      bridge.recordWarning("bounded log " + index);
    }
    assertEquals(40, bridge.recentProblems().size());
    assertThrows(
        IllegalStateException.class,
        () ->
            NativeLogBridge.progress(
                progress -> {
                  LlamaRuntime.current();
                  return true;
                },
                0.5f));
  }
}
