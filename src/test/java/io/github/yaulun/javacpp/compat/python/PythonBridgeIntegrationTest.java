package io.github.yaulun.javacpp.compat.python;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.yaulun.javacpp.ContextParams;
import io.github.yaulun.javacpp.LlamaContext;
import io.github.yaulun.javacpp.LlamaModel;
import io.github.yaulun.javacpp.LlamaRuntime;
import io.github.yaulun.javacpp.ModelParams;
import io.github.yaulun.javacpp.NativeConfig;
import io.github.yaulun.javacpp.Sampler;
import io.github.yaulun.javacpp.TokenListener;
import io.github.yaulun.javacpp.exception.ContextOverflowException;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("native")
@Timeout(60)
class PythonBridgeIntegrationTest {
  private static Path modelFile;

  @BeforeAll
  static void initialize() {
    String nativeDirectory =
        System.getProperty("javacpp.natives", System.getenv("JAVACPP_NATIVES"));
    String selectedModel = System.getProperty("javacpp.model");
    if (selectedModel == null && System.getenv("JAVACPP_MODELS") != null) {
      selectedModel = Path.of(System.getenv("JAVACPP_MODELS"), "Qwen3.5-0.8B-Q8_0.gguf").toString();
    }
    assumeTrue(nativeDirectory != null && selectedModel != null);
    modelFile = Path.of(selectedModel);
    LlamaRuntime.initialize(NativeConfig.builder().nativesDir(Path.of(nativeDirectory)).build());
  }

  @Test
  void translatesActualNativeCancellationAndRetainsNativeCause() {
    PythonException failure =
        assertThrows(
            PythonException.class,
            () ->
                PythonErrorBridge.loadModel(
                    modelFile,
                    ModelParams.builder()
                        .gpuLayers(0)
                        .progressListener(progress -> false)
                        .build()));
    assertEquals(PythonErrorType.VALUE_ERROR, failure.type());
    assertEquals("Failed to load model from file: " + modelFile, failure.getMessage());
    ModelLoadException original = (ModelLoadException) failure.getCause();
    assertEquals(ErrorCode.E206, original.code());
    assertEquals("llama_model_load_from_file", original.nativeFailure().orElseThrow().function());
    assertEquals(0, original.nativeFailure().orElseThrow().returnValue());
  }

  @Test
  void tokenizesRawBytesAndRunsCompletionWithMappedPreconditions() {
    try (LlamaModel model =
            PythonErrorBridge.loadModel(modelFile, ModelParams.builder().gpuLayers(0).build());
        LlamaContext context =
            PythonErrorBridge.createContext(
                model, ContextParams.builder().contextLength(256).build());
        Sampler sampler = Sampler.greedy(model.vocab())) {
      PythonException empty =
          assertThrows(PythonException.class, () -> PythonErrorBridge.sample(context, sampler));
      assertEquals(PythonErrorType.ASSERTION_ERROR, empty.type());
      assertEquals("", empty.getMessage());
      byte[] input = "你好\0🙂".getBytes(StandardCharsets.UTF_8);
      int[] tokens = PythonErrorBridge.tokenize(model.vocab(), input, false, false);
      assertArrayEquals(tokens, model.vocab().tokenizeBytes(input, false, false));
      assertEquals("你好\0🙂", model.vocab().detokenize(tokens, false, false));
      assertThrows(
          IllegalArgumentException.class, () -> model.vocab().tokenize("你好\0🙂", false, false));
      int[] prompt =
          PythonErrorBridge.tokenize(
              model.vocab(), "Write a long story:".getBytes(StandardCharsets.UTF_8));
      var configuration =
          PythonErrorBridge.completionConfig(8, prompt.length, context.contextLength());
      var result = context.generate(prompt, sampler, configuration, TokenListener.NONE);
      assertTrue(result.generatedTokens() > 0);
      assertTrue(result.generatedTokens() <= 8);
      context.clear();
      assertThrows(
          ContextOverflowException.class,
          () -> PythonErrorBridge.decode(context, new int[context.contextLength() + 1]));
    }
  }
}
