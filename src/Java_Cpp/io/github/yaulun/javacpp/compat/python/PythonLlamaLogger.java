package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Ports _logger.py: the module logger level, the sticky GGML-to-logging level map, the
 * llama_log_callback body and set_verbose. Output goes through an explicit stderr port, matching
 * print(..., end="", flush=True, file=sys.stderr).
 */
public final class PythonLlamaLogger {
  // Python logging numeric levels used by GGML_LOG_LEVEL_TO_LOGGING_LEVEL.
  public static final int CRITICAL = 50;
  public static final int ERROR = 40;
  public static final int WARNING = 30;
  public static final int INFO = 20;
  public static final int DEBUG = 10;

  private static int levelMapping(int level) {
    return switch (level) {
      case 0 -> CRITICAL;
      case 1 -> INFO;
      case 2 -> WARNING;
      case 3 -> ERROR;
      case 4, 5 -> DEBUG;
      default -> throw Py.error(PythonErrorType.KEY_ERROR, Integer.toString(level));
    };
  }

  private final Consumer<String> stderr;
  private int loggerLevel;
  private int lastLogLevel = levelMapping(0);

  /** The module logger starts at NOTSET so the callback prints every level until configured. */
  public PythonLlamaLogger(Consumer<String> stderr) {
    this.stderr = Objects.requireNonNull(stderr, "stderr");
  }

  /** Current logger.level attribute; NOTSET is reported as 0 like Python. */
  public int level() {
    return loggerLevel;
  }

  /** Direct logger.setLevel(value); set_verbose only ever writes DEBUG or ERROR. */
  public void setLevel(int level) {
    loggerLevel = level;
  }

  /** Module-level _last_log_level state, exposed for contract inspection. */
  public int lastLogLevel() {
    return lastLogLevel;
  }

  /** Ports the pinned llama_log_callback body, including the continue-previous-log rule. */
  public void logCallback(int level, byte[] text, Object userData) {
    int mapped = levelMapping(level);
    int logLevel = level != 5 ? mapped : lastLogLevel;
    if (loggerLevel <= mapped) {
      // print writes the text and the empty end= terminator as separate writes.
      stderr.accept(decode(text));
      stderr.accept("");
    }
    lastLogLevel = logLevel;
  }

  /** Ports set_verbose: DEBUG when verbose else ERROR. */
  public void setVerbose(boolean verbose) {
    loggerLevel = verbose ? DEBUG : ERROR;
  }

  /** text.decode("utf-8") with a strict error handler and the CPython message shape. */
  private static String decode(byte[] text) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(text))
          .toString();
    } catch (CharacterCodingException failure) {
      throw Py.error(PythonErrorType.UNICODE_DECODE_ERROR, decodeErrorMessage(text));
    }
  }

  private static String decodeErrorMessage(byte[] text) {
    int position = 0;
    while (position < text.length) {
      int lead = text[position] & 0xFF;
      int extra;
      if (lead < 0x80) {
        position++;
        continue;
      } else if (lead >= 0xC2 && lead <= 0xDF) extra = 1;
      else if (lead >= 0xE0 && lead <= 0xEF) extra = 2;
      else if (lead >= 0xF0 && lead <= 0xF4) extra = 3;
      else return message(lead, position, "invalid start byte");
      int available = Math.min(extra, text.length - position - 1);
      for (int i = 1; i <= available; i++) {
        if ((text[position + i] & 0xC0) != 0x80) {
          return message(lead, position, "invalid continuation byte");
        }
      }
      if (available < extra) {
        // CPython switches to the plural byte-range form once continuations were consumed.
        if (available > 0) {
          return "'utf-8' codec can't decode bytes in position "
              + position
              + "-"
              + (position + available)
              + ": unexpected end of data";
        }
        return message(lead, position, "unexpected end of data");
      }
      position += extra + 1;
    }
    return message(
        text.length == 0 ? 0 : text[text.length - 1] & 0xFF, text.length - 1, "invalid start byte");
  }

  private static String message(int lead, int position, String reason) {
    return "'utf-8' codec can't decode byte 0x"
        + String.format("%02x", lead)
        + " in position "
        + position
        + ": "
        + reason;
  }
}
