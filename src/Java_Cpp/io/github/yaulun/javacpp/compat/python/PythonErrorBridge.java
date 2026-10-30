package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.ContextParams;
import io.github.yaulun.javacpp.GenerationConfig;
import io.github.yaulun.javacpp.LlamaContext;
import io.github.yaulun.javacpp.LlamaModel;
import io.github.yaulun.javacpp.ModelParams;
import io.github.yaulun.javacpp.Sampler;
import io.github.yaulun.javacpp.Vocab;
import io.github.yaulun.javacpp.exception.ContextOverflowException;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.InferenceException;
import io.github.yaulun.javacpp.exception.JavaCppException;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import io.github.yaulun.javacpp.exception.NativeFailure;
import io.github.yaulun.javacpp.exception.TokenizationException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import io.github.yaulun.javacpp.python.PythonBytes;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import io.github.yaulun.javacpp.python.PythonValues;

/** Operation-specific exception/message bridge pinned to llama-cpp-python 0.3.36. */
public final class PythonErrorBridge {
  public static final String PYTHON_VERSION = "0.3.36";

  private PythonErrorBridge() {}

  public static PythonException error(PythonErrorType type, String message) {
    return PythonValues.error(type, message);
  }

  public static String pythonString(Object value) {
    return PythonValues.string(value);
  }

  /** Returns a supported translation; unknown Java failures remain explicitly unmapped. */
  public static Optional<PythonException> translate(Throwable failure, PythonCall call) {
    Objects.requireNonNull(failure, "failure");
    Objects.requireNonNull(call, "call");
    if (failure instanceof PythonException translated) {
      return Optional.of(translated);
    }
    if (!(failure instanceof JavaCppException original)) {
      return Optional.empty();
    }
    return switch (call.operation()) {
      case MODEL_LOAD -> modelFailure(original, call);
      case CONTEXT_CREATE ->
          original instanceof InferenceException
                  && nativeResult(original, "llama_init_from_model", 0)
                  && original.code() == ErrorCode.E301
              ? valueError("Failed to create llama_context", original)
              : Optional.empty();
      case TOKENIZE -> tokenFailure(original, call.text());
      case DECODE -> decodeFailure(original);
      case COMPLETION ->
          original instanceof ContextOverflowException
                  && call.promptTokens() >= call.contextLength()
              ? valueError(overflowMessage(call.promptTokens(), call.contextLength()), original)
              : Optional.empty();
    };
  }

  /**
   * Executes an operation; supported failures are translated and all others propagate unchanged.
   */
  public static <T> T call(PythonCall call, Supplier<T> operation) {
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(operation, "operation");
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      throw translate(failure, call).<RuntimeException>map(value -> value).orElse(failure);
    }
  }

  /** Executes a void operation with the same mapping policy as call. */
  public static void run(PythonCall call, Runnable operation) {
    Objects.requireNonNull(operation, "operation");
    call(
        call,
        () -> {
          operation.run();
          return null;
        });
  }

  /** Loads a local model while retaining the original caller path for Python messages. */
  public static LlamaModel loadModel(Path path, ModelParams params) {
    return call(PythonCall.modelLoad(path), () -> LlamaModel.load(path, params));
  }

  /** Creates a context; a NULL native context becomes Python ValueError. */
  public static LlamaContext createContext(LlamaModel model, ContextParams params) {
    Objects.requireNonNull(model, "model");
    return call(PythonCall.contextCreation(), () -> model.newContext(params));
  }

  /** Tokenizes raw bytes with the high-level Llama defaults add_bos=True and special=False. */
  public static int[] tokenize(Vocab vocab, byte[] text) {
    return tokenize(vocab, text, true, false);
  }

  /** Tokenizes raw bytes and preserves the original Python bytes representation on failure. */
  public static int[] tokenize(Vocab vocab, byte[] text, boolean addBos, boolean special) {
    Objects.requireNonNull(vocab, "vocab");
    byte[] copy = Objects.requireNonNull(text, "text").clone();
    return call(PythonCall.tokenize(copy), () -> vocab.tokenizeBytes(copy, addBos, special));
  }

  /** Decodes tokens; native process failures retain their numeric status in the Python message. */
  public static void decode(LlamaContext context, int[] tokens) {
    Objects.requireNonNull(context, "context");
    run(PythonCall.decode(), () -> context.decode(tokens));
  }

  /** Reproduces the high-level Python sample assertion when no token has been evaluated. */
  public static int sample(LlamaContext context, Sampler sampler) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(sampler, "sampler");
    requireEvaluatedTokens(context.usedTokens());
    return context.sample(sampler);
  }

  static void requireEvaluatedTokens(int count) {
    if (count <= 0) {
      throw new PythonException(PythonErrorType.ASSERTION_ERROR, "", null);
    }
  }

  /**
   * Applies Python completion max_tokens rules: null/nonpositive means remaining context, and an
   * oversized positive limit is clamped. Counts must describe a fresh complete prompt.
   */
  public static GenerationConfig completionConfig(
      Integer maxTokens, int promptTokens, int contextLength) {
    PythonCall call = PythonCall.completion(promptTokens, contextLength);
    if (promptTokens >= contextLength) {
      throw new PythonException(
          PythonErrorType.VALUE_ERROR,
          overflowMessage(call.promptTokens(), call.contextLength()),
          null);
    }
    int remaining = contextLength - promptTokens;
    return new GenerationConfig(
        maxTokens == null || maxTokens <= 0 ? remaining : Math.min(maxTokens, remaining), null);
  }

  private static Optional<PythonException> modelFailure(JavaCppException failure, PythonCall call) {
    if (!(failure instanceof ModelLoadException)) {
      return Optional.empty();
    }
    if (failure.code() == ErrorCode.E201) {
      return valueError(
          (call.modelPathExists()
                  ? "Failed to load model from file: "
                  : "Model path does not exist: ")
              + call.modelPath(),
          failure);
    }
    if (failure.code() == ErrorCode.E203
        || failure.code() == ErrorCode.E204
        || (nativeResult(failure, "llama_model_load_from_file", 0) && failure.getCause() == null)) {
      return valueError("Failed to load model from file: " + call.modelPath(), failure);
    }
    if (nativeResult(failure, "llama_model_get_vocab", 0)) {
      return valueError("Failed to get vocab from model: " + call.modelPath(), failure);
    }
    return Optional.empty();
  }

  private static Optional<PythonException> tokenFailure(JavaCppException failure, byte[] text) {
    if (!(failure instanceof TokenizationException)) {
      return Optional.empty();
    }
    return failure
        .nativeFailure()
        .filter(
            value ->
                value.function().equals("llama_tokenize")
                    && value.returnValue() < 0
                    && value.returnValue() > Integer.MIN_VALUE)
        .map(
            value ->
                new PythonException(
                    PythonErrorType.RUNTIME_ERROR,
                    "Failed to tokenize: text=\""
                        + PythonBytes.repr(text)
                        + "\" n_tokens="
                        + value.returnValue(),
                    failure));
  }

  private static Optional<PythonException> decodeFailure(JavaCppException failure) {
    if (!(failure instanceof InferenceException)) {
      return Optional.empty();
    }
    return failure
        .nativeFailure()
        .filter(
            value ->
                value.function().equals("llama_process")
                    && value.returnValue() != 0
                    && value.returnValue() >= Integer.MIN_VALUE
                    && value.returnValue() <= Integer.MAX_VALUE)
        .map(
            value ->
                new PythonException(
                    PythonErrorType.RUNTIME_ERROR,
                    "llama_decode returned " + value.returnValue(),
                    failure));
  }

  private static boolean nativeResult(JavaCppException failure, String function, long result) {
    return failure
        .nativeFailure()
        .filter(value -> value.equals(new NativeFailure(function, result)))
        .isPresent();
  }

  private static Optional<PythonException> valueError(String message, Throwable cause) {
    return Optional.of(new PythonException(PythonErrorType.VALUE_ERROR, message, cause));
  }

  private static String overflowMessage(int requested, int available) {
    return "Requested tokens (" + requested + ") exceed context window of " + available;
  }
}
