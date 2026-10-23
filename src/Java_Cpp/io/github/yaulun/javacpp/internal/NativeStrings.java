package io.github.yaulun.javacpp.internal;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** UTF-8 conversion helpers for C-owned and Java-owned strings. */
public final class NativeStrings {
  private NativeStrings() {}

  /** Encodes a zero-terminated C string; rejects embedded zero characters. */
  public static byte[] toCString(String text) {
    Objects.requireNonNull(text, "text");
    if (text.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("text contains NUL");
    }
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return Arrays.copyOf(bytes, Math.addExact(bytes.length, 1));
  }

  /** Allocates a C string; the caller owns and must close the memory. */
  public static Memory toNativeMemory(String text) {
    byte[] bytes = toCString(text);
    Memory memory = new Memory(bytes.length);
    memory.write(0, bytes, 0, bytes.length);
    return memory;
  }

  /** Copies a C string using UTF-8; null maps to null. */
  public static String fromCString(Pointer pointer) {
    return pointer == null ? null : pointer.getString(0, "UTF-8");
  }

  /** Decodes exactly length bytes, including any embedded zero byte. */
  public static String fromBytes(byte[] bytes, int length) {
    Objects.checkFromIndexSize(0, length, bytes.length);
    return new String(bytes, 0, length, StandardCharsets.UTF_8);
  }

  public static byte cBool(boolean value) {
    return (byte) (value ? 1 : 0);
  }
}
