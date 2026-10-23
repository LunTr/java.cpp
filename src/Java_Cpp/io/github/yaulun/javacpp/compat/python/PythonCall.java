package io.github.yaulun.javacpp.compat.python;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable original Python call arguments needed to reproduce context-dependent messages. */
public final class PythonCall {
  enum Operation {
    MODEL_LOAD,
    CONTEXT_CREATE,
    TOKENIZE,
    DECODE,
    COMPLETION
  }

  private final Operation operation;
  private final String modelPath;
  private final boolean modelPathExists;
  private final byte[] text;
  private final int promptTokens;
  private final int contextLength;

  private PythonCall(
      Operation operation, String modelPath, byte[] text, int promptTokens, int contextLength) {
    this.operation = operation;
    this.modelPath = modelPath;
    this.modelPathExists = exists(modelPath);
    this.text = text == null ? null : text.clone();
    this.promptTokens = promptTokens;
    this.contextLength = contextLength;
  }

  /** Retains the path exactly as supplied to the Python-facing model constructor. */
  public static PythonCall modelLoad(String modelPath) {
    return new PythonCall(Operation.MODEL_LOAD, Objects.requireNonNull(modelPath), null, 0, 0);
  }

  /** Retains the supplied path without converting it to an absolute path. */
  public static PythonCall modelLoad(Path modelPath) {
    return modelLoad(Objects.requireNonNull(modelPath).toString());
  }

  /** Describes the native context-creation call. */
  public static PythonCall contextCreation() {
    return new PythonCall(Operation.CONTEXT_CREATE, null, null, 0, 0);
  }

  /** Copies the original byte input, including any zero or non-ASCII bytes. */
  public static PythonCall tokenize(byte[] text) {
    return new PythonCall(Operation.TOKENIZE, null, Objects.requireNonNull(text), 0, 0);
  }

  /** Describes a decode operation whose original native return code will be retained. */
  public static PythonCall decode() {
    return new PythonCall(Operation.DECODE, null, null, 0, 0);
  }

  /** Records actual token counts for the completion context-window check. */
  public static PythonCall completion(int promptTokens, int contextLength) {
    if (promptTokens < 0 || contextLength < 1) {
      throw new IllegalArgumentException("promptTokens must be >= 0 and contextLength >= 1");
    }
    return new PythonCall(Operation.COMPLETION, null, null, promptTokens, contextLength);
  }

  Operation operation() {
    return operation;
  }

  String modelPath() {
    return modelPath;
  }

  boolean modelPathExists() {
    return modelPathExists;
  }

  byte[] text() {
    return text.clone();
  }

  int promptTokens() {
    return promptTokens;
  }

  int contextLength() {
    return contextLength;
  }

  private static boolean exists(String path) {
    if (path == null) {
      return false;
    }
    try {
      return Files.exists(Path.of(path));
    } catch (InvalidPathException invalidPath) {
      // Python os.path.exists returns false for an embedded NUL or an invalid path.
      return false;
    }
  }
}
