package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.Vocab;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Five LlamaTokenizer methods from Python 0.3.36, with explicit model codec injection. */
public final class PythonLlamaTokenizer implements PythonTokenizer {
  private final PythonTokenizerModel model;

  /** Retains the model codec reference, as the original constructor retains llama._model. */
  public PythonLlamaTokenizer(PythonTokenizerModel model) {
    this.model = Objects.requireNonNull(model, "model");
  }

  /** Attaches an existing model-owned vocabulary. Closing its model invalidates this adapter. */
  public static PythonLlamaTokenizer fromVocab(Vocab vocab) {
    return new PythonLlamaTokenizer(modelFromVocab(vocab));
  }

  /** Model codec for attaching a native vocabulary to PythonLlama. */
  public static PythonTokenizerModel modelFromVocab(Vocab vocab) {
    Objects.requireNonNull(vocab, "vocab");
    return new PythonTokenizerModel() {
      public int[] tokenize(byte[] text, boolean addBos, boolean special) {
        return PythonErrorBridge.tokenize(vocab, text, addBos, special);
      }

      public byte[] detokenize(int[] tokens, boolean special) {
        return vocab.pythonDetokenizeBytes(tokens, special);
      }
    };
  }

  /** Tokenizer-level defaults: add_bos=true, special=true. */
  public int[] tokenize(byte[] text) {
    return tokenize(text, true, true);
  }

  @Override
  public int[] tokenize(byte[] text, boolean addBos, boolean special) {
    return model.tokenize(text, addBos, special);
  }

  /** Default detokenization hides special pieces. */
  public byte[] detokenize(int[] tokens) {
    return detokenize(tokens, null, false);
  }

  /** The original LlamaTokenizer accepts previous tokens and delegates only tokens and special. */
  @Override
  public byte[] detokenize(int[] tokens, int[] previousTokens, boolean special) {
    return model.detokenize(tokens, special);
  }

  public int[] encode(String text) {
    return encode(text, true, true);
  }

  /** Encodes UTF-8 with errors=ignore before calling tokenize. */
  public int[] encode(String text, boolean addBos, boolean special) {
    try {
      ByteBuffer bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.IGNORE)
              .onUnmappableCharacter(CodingErrorAction.IGNORE)
              .encode(CharBuffer.wrap(text));
      byte[] value = new byte[bytes.remaining()];
      bytes.get(value);
      return tokenize(value, addBos, special);
    } catch (CharacterCodingException failure) {
      throw new AssertionError(failure);
    }
  }

  /** Decodes detokenized bytes as UTF-8 with errors=ignore. */
  public String decode(int[] tokens) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.IGNORE)
          .onUnmappableCharacter(CodingErrorAction.IGNORE)
          .decode(ByteBuffer.wrap(detokenize(tokens)))
          .toString();
    } catch (CharacterCodingException failure) {
      throw new AssertionError(failure);
    }
  }
}
