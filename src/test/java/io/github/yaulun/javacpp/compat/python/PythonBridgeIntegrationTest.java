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
  void evaluatesSavesRestoresAndGeneratesThroughPythonState() {
    try (LlamaModel model =
            PythonErrorBridge.loadModel(modelFile, ModelParams.builder().gpuLayers(0).build());
        LlamaContext context =
            model.newContext(ContextParams.builder().contextLength(64).batchSize(32).build());
        Sampler sampler = Sampler.greedy(model.vocab())) {
      PythonLlama state =
          new PythonLlama(
              new int[context.contextLength()],
              new float[context.contextLength()][model.vocab().size()],
              0,
              context.contextLength(),
              true,
              false,
              model.info().hybrid(),
              () -> context::clear);
      state.attachNative(model, context);
      state.setSamplerFactory(
          () ->
              relativeIndex -> {
                assertEquals(-1, relativeIndex);
                return context.sample(sampler);
              });
      assertEquals(context.contextLength(), state.nCtx());
      assertEquals(model.info().embedding(), state.nEmbd());
      assertEquals(model.vocab().size(), state.nVocab());
      assertEquals(model.vocab().bos(), state.tokenBos());
      assertEquals(model.vocab().eos(), state.tokenEos());
      assertEquals(model.vocab().newline(), state.tokenNl());
      assertEquals(context.poolingType(), state.poolingType());
      // Reuse the ignored Python-generated input bytes from the previous tokenizer batch.
      byte[] bytes;
      try (var input =
          java.util.Objects.requireNonNull(
              getClass().getResourceAsStream("/python-0.3.36-tokenizer-contracts.tsv"))) {
        String line =
            new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                .lines()
                .skip(32)
                .findFirst()
                .orElseThrow();
        var arg =
            (java.util.Map<?, ?>)
                PythonContractData.read(
                    new java.io.DataInputStream(
                        new java.io.ByteArrayInputStream(
                            java.util.Base64.getDecoder().decode(line.split("\t")[0]))));
        bytes = java.util.HexFormat.of().parseHex((String) arg.get("bytes"));
      } catch (java.io.IOException failure) {
        throw new AssertionError(failure);
      }
      int[] prompt = state.tokenize(bytes, true, false);
      state.eval(prompt);
      assertEquals(prompt.length, state.nTokens());
      assertEquals(prompt.length, context.usedTokens());
      PythonLlamaState saved = state.saveState();
      int token = state.sample();
      state.eval(new int[] {token});
      assertEquals(prompt.length + 1, state.nTokens());
      state.loadState(saved);
      assertEquals(saved.nTokens(), context.usedTokens());
      assertTrue(state.requiresEval());
      assertArrayEquals(saved.inputIds(), state.inputIds().array());
      state.reset();
      try (var generator = state.generate(prompt)) {
        assertEquals(token, generator.next());
        assertTrue(generator.next() >= 0);
      }
    }
  }

  @Test
  void nativeTokenizerRoundTripsOriginalEncodedTextAndHonorsModelLifetime() throws Exception {
    LlamaModel model =
        PythonErrorBridge.loadModel(modelFile, ModelParams.builder().gpuLayers(0).build());
    PythonLlamaTokenizer tokenizer = PythonLlamaTokenizer.fromVocab(model.vocab());
    try (model;
        var input =
            java.util.Objects.requireNonNull(
                getClass().getResourceAsStream("/python-0.3.36-tokenizer-contracts.tsv"))) {
      var lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
      for (String line : lines) {
        String[] parts = line.split("\t");
        var expected =
            (java.util.Map<?, ?>)
                PythonContractData.read(
                    new java.io.DataInputStream(
                        new java.io.ByteArrayInputStream(
                            java.util.Base64.getDecoder().decode(parts[1]))));
        var trace = (java.util.List<?>) expected.get("trace");
        String encodedHex = (String) ((java.util.List<?>) trace.get(3)).get(1);
        byte[] bytes = java.util.HexFormat.of().parseHex(encodedHex);
        int[] tokens = tokenizer.tokenize(bytes, false, false);
        assertArrayEquals(bytes, tokenizer.detokenize(tokens));
        assertArrayEquals(
            tokenizer.detokenize(tokens), tokenizer.detokenize(tokens, tokens, false));
      }
      PythonLlama state =
          new PythonLlama(new int[0], new float[0][], 0, 8, false, false, false, () -> null);
      state.setTokenizerModel(PythonLlamaTokenizer.modelFromVocab(model.vocab()));
      assertArrayEquals(
          state.tokenize(new byte[0]), state.tokenizer().tokenize(new byte[0], true, false));
    }
    assertThrows(IllegalStateException.class, () -> tokenizer.tokenize(new byte[0]));
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
