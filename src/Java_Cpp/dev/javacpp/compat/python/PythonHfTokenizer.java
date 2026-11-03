package dev.javacpp.compat.python;

import dev.javacpp.python.Py;
import dev.javacpp.python.PythonErrorType;
import dev.javacpp.python.PythonException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Ports LlamaHFTokenizer: delegates to a HF-style tokenizer port. tokenize decodes the byte text
 * with errors="ignore" before encode; detokenize supports the prev_tokens offset slice by
 * re-encoding decoded strings and subtracting the previous prefix length.
 */
public final class PythonHfTokenizer extends PythonBaseLlamaTokenizer {
  /** HF AutoTokenizer surface used by the pinned bodies. */
  public interface HfTokenizer {
    int[] encode(String text, boolean addSpecialTokens);

    String decode(int[] tokens, boolean skipSpecialTokens);
  }

  private final HfTokenizer hfTokenizer;

  public PythonHfTokenizer(HfTokenizer hfTokenizer) {
    this.hfTokenizer = Objects.requireNonNull(hfTokenizer);
  }

  @Override
  protected String pythonTypeName() {
    return "LlamaHFTokenizer";
  }

  /** Retained for parity with the public hf_tokenizer attribute. */
  public HfTokenizer hfTokenizer() {
    return hfTokenizer;
  }

  @Override
  public int[] tokenize(byte[] text, boolean addBos, boolean special) {
    return hfTokenizer.encode(decodeIgnore(text), special);
  }

  @Override
  public byte[] detokenize(int[] tokens, int[] previousTokens, boolean special) {
    boolean skipSpecial = !special;
    if (previousTokens != null) {
      int[] combined = Arrays.copyOf(previousTokens, previousTokens.length + tokens.length);
      System.arraycopy(tokens, 0, combined, previousTokens.length, tokens.length);
      byte[] text = encodeIgnore(hfTokenizer.decode(combined, skipSpecial));
      byte[] previousText = encodeIgnore(hfTokenizer.decode(previousTokens, skipSpecial));
      return Arrays.copyOfRange(text, previousText.length, text.length);
    }
    return encodeIgnore(hfTokenizer.decode(tokens, skipSpecial));
  }

  /**
   * Ports from_pretrained: the transformers import is an injectable factory so ImportError raises
   * the original message and success builds cls(hf_tokenizer).
   */
  public static PythonHfTokenizer fromPretrained(
      String pretrainedModelNameOrPath, Function<String, HfTokenizer> autoTokenizerFromPretrained) {
    HfTokenizer hfTokenizer;
    try {
      hfTokenizer = autoTokenizerFromPretrained.apply(pretrainedModelNameOrPath);
    } catch (PythonException failure) {
      if (failure.type() == PythonErrorType.IMPORT_ERROR) {
        throw Py.error(
            PythonErrorType.IMPORT_ERROR,
            "The `transformers` library is required to use the `HFTokenizer`."
                + "You can install it with `pip install transformers`.");
      }
      throw failure;
    }
    return new PythonHfTokenizer(hfTokenizer);
  }

  private static String decodeIgnore(byte[] text) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.IGNORE)
          .onUnmappableCharacter(CodingErrorAction.IGNORE)
          .decode(ByteBuffer.wrap(text))
          .toString();
    } catch (java.nio.charset.CharacterCodingException failure) {
      throw new AssertionError(failure);
    }
  }

  private static byte[] encodeIgnore(String text) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.IGNORE)
              .onUnmappableCharacter(CodingErrorAction.IGNORE)
              .encode(CharBuffer.wrap(text));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (java.nio.charset.CharacterCodingException failure) {
      throw new AssertionError(failure);
    }
  }
}
