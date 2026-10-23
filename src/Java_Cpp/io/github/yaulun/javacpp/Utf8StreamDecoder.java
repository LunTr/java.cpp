package io.github.yaulun.javacpp;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Stateful incremental UTF-8 decoder. Use one instance per generated stream on one thread. */
public final class Utf8StreamDecoder {
  private final CharsetDecoder decoder =
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE);
  private byte[] pending = new byte[0];
  private boolean finished;

  /** Consumes bytes and returns only complete Unicode code points. */
  public String decode(byte[] piece) {
    Objects.requireNonNull(piece, "piece");
    if (finished) {
      throw new IllegalStateException("Decoder finished");
    }
    ByteBuffer input = ByteBuffer.allocate(Math.addExact(pending.length, piece.length));
    input.put(pending).put(piece).flip();
    CharBuffer output = CharBuffer.allocate(input.remaining() + 2);
    decoder.decode(input, output, false);
    pending = new byte[input.remaining()];
    input.get(pending);
    return output.flip().toString();
  }

  /**
   * Completes decoding, replacing any trailing incomplete sequence; subsequent calls return empty.
   */
  public String finish() {
    if (finished) {
      return "";
    }
    finished = true;
    CharBuffer output = CharBuffer.allocate(pending.length + 2);
    decoder.decode(ByteBuffer.wrap(pending), output, true);
    decoder.flush(output);
    pending = new byte[0];
    return output.flip().toString();
  }

  /** Returns the number of bytes awaiting a complete UTF-8 code point. */
  public int pendingBytes() {
    return pending.length;
  }
}
