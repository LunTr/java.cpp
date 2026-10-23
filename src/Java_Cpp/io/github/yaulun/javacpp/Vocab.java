package io.github.yaulun.javacpp;

import com.sun.jna.Pointer;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.NativeFailure;
import io.github.yaulun.javacpp.exception.TokenizationException;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import io.github.yaulun.javacpp.internal.NativeLogBridge;
import io.github.yaulun.javacpp.internal.NativeStrings;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Model-owned vocabulary; every operation pins the model against concurrent close. */
public final class Vocab {
  private final LlamaModel model;
  private final Pointer handle;
  private final LlamaLibrary library;
  private final int size;

  Vocab(LlamaModel model, Pointer handle) {
    this.model = model;
    this.handle = handle;
    library = model.library();
    size = library.llama_vocab_n_tokens(handle);
    if (size <= 0) {
      throw failure("Empty vocabulary");
    }
  }

  /** Returns the vocabulary token count; use after model close raises IllegalStateException. */
  public int size() {
    return model.withOpen(() -> size);
  }

  /** Returns the beginning-of-sequence token, or -1 when absent. */
  public int bos() {
    return model.withOpen(() -> library.llama_vocab_bos(handle));
  }

  /** Returns the end-of-sequence token, or -1 when absent. */
  public int eos() {
    return model.withOpen(() -> library.llama_vocab_eos(handle));
  }

  /** Tests an existing vocabulary token for end-of-generation. */
  public boolean isEog(int token) {
    return model.withOpen(
        () -> {
          checkToken(token);
          return library.llama_vocab_is_eog(handle, token) != 0;
        });
  }

  /** Tokenizes UTF-8 text with explicit special-token policy; NUL characters are rejected. */
  public int[] tokenize(String text, boolean addSpecial, boolean parseSpecial) {
    Checks.text(text, "text");
    return tokenizeBytes(text.getBytes(StandardCharsets.UTF_8), addSpecial, parseSpecial);
  }

  /** Tokenizes an exact copied byte sequence, including NUL bytes, using its explicit C length. */
  public int[] tokenizeBytes(byte[] text, boolean addSpecial, boolean parseSpecial) {
    byte[] bytes = Objects.requireNonNull(text, "text").clone();
    return model.withOpen(
        () -> {
          int[] buffer = new int[Math.addExact(bytes.length, 8)];
          int count =
              library.llama_tokenize(
                  handle,
                  bytes,
                  bytes.length,
                  buffer,
                  buffer.length,
                  NativeStrings.cBool(addSpecial),
                  NativeStrings.cBool(parseSpecial));
          if (count < 0) {
            buffer = new int[required(count)];
            count =
                library.llama_tokenize(
                    handle,
                    bytes,
                    bytes.length,
                    buffer,
                    buffer.length,
                    NativeStrings.cBool(addSpecial),
                    NativeStrings.cBool(parseSpecial));
          }
          if (count < 0 || count > buffer.length) {
            throw new TokenizationException(
                ErrorCode.E401,
                "Token count=" + count,
                NativeLogBridge.instance().recentProblems(),
                null,
                new NativeFailure("llama_tokenize", count));
          }
          return Arrays.copyOf(buffer, count);
        });
  }

  /** Returns raw token bytes; concatenate through Utf8StreamDecoder for text output. */
  public byte[] tokenToBytes(int token, boolean special) {
    return model.withOpen(
        () -> {
          checkToken(token);
          byte[] buffer = new byte[32];
          int count =
              library.llama_token_to_piece(
                  handle, token, buffer, buffer.length, 0, NativeStrings.cBool(special));
          if (count < 0) {
            buffer = new byte[required(count)];
            count =
                library.llama_token_to_piece(
                    handle, token, buffer, buffer.length, 0, NativeStrings.cBool(special));
          }
          if (count < 0 || count > buffer.length) {
            throw failure("Piece size=" + count);
          }
          return Arrays.copyOf(buffer, count);
        });
  }

  /** Decodes a single piece for debugging; the piece may represent part of a code point. */
  public String tokenToText(int token) {
    return new String(tokenToBytes(token, true), StandardCharsets.UTF_8);
  }

  /** Converts validated tokens into text, resizing the native buffer when required. */
  public String detokenize(int[] tokens, boolean removeSpecial, boolean unparseSpecial) {
    int[] copy = Objects.requireNonNull(tokens, "tokens").clone();
    return model.withOpen(
        () -> {
          for (int token : copy) {
            checkToken(token);
          }
          if (copy.length == 0) {
            return "";
          }
          byte[] buffer = new byte[Math.addExact(Math.multiplyExact(copy.length, 4), 8)];
          int count =
              library.llama_detokenize(
                  handle,
                  copy,
                  copy.length,
                  buffer,
                  buffer.length,
                  NativeStrings.cBool(removeSpecial),
                  NativeStrings.cBool(unparseSpecial));
          if (count < 0) {
            buffer = new byte[required(count)];
            count =
                library.llama_detokenize(
                    handle,
                    copy,
                    copy.length,
                    buffer,
                    buffer.length,
                    NativeStrings.cBool(removeSpecial),
                    NativeStrings.cBool(unparseSpecial));
          }
          if (count < 0 || count > buffer.length) {
            throw failure("Detokenized size=" + count);
          }
          return NativeStrings.fromBytes(buffer, count);
        });
  }

  void checkToken(int token) {
    if (token < 0 || token >= size) {
      throw new IllegalArgumentException("token must be in [0, " + size + ")");
    }
  }

  LlamaModel model() {
    return model;
  }

  private static int required(int negative) {
    if (negative == Integer.MIN_VALUE) {
      throw failure("Result exceeds int32 capacity");
    }
    return -negative;
  }

  private static TokenizationException failure(String detail) {
    return new TokenizationException(
        ErrorCode.E401, detail, NativeLogBridge.instance().recentProblems());
  }
}
