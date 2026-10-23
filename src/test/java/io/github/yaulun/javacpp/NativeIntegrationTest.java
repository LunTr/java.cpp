package io.github.yaulun.javacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.yaulun.javacpp.exception.ContextOverflowException;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("native")
@Timeout(120)
class NativeIntegrationTest {
  private static Path modelFile;
  private static LlamaModel model;
  private static LlamaRuntime runtime;

  @BeforeAll
  static void loadModel() {
    String natives = System.getProperty("javacpp.natives", System.getenv("JAVACPP_NATIVES"));
    String modelPath = System.getProperty("javacpp.model");
    if (modelPath == null && System.getenv("JAVACPP_MODELS") != null) {
      modelPath = Path.of(System.getenv("JAVACPP_MODELS"), "Qwen3.5-0.8B-Q8_0.gguf").toString();
    }
    assumeTrue(
        natives != null && modelPath != null,
        "Set native directory and local model for integration tests");
    modelFile = Path.of(modelPath);
    runtime = LlamaRuntime.initialize(NativeConfig.builder().nativesDir(Path.of(natives)).build());
    model = LlamaModel.load(modelFile, ModelParams.builder().gpuLayers(0).build());
  }

  @AfterAll
  static void closeModel() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void validatesRuntimeIdentityAndDevices() {
    assertTrue(runtime.versions().matchesExpected());
    assertFalse(runtime.devices().isEmpty());
    assertSame(
        runtime,
        LlamaRuntime.initialize(NativeConfig.builder().nativesDir(runtime.nativesDir()).build()));
    assertThrows(
        IllegalStateException.class,
        () ->
            LlamaRuntime.initialize(
                NativeConfig.builder()
                    .nativesDir(runtime.nativesDir().resolve("different"))
                    .build()));
    assertEquals("qwen35", model.info().architecture());
    assertEquals(24, model.info().layers());
    assertEquals(262144, model.info().contextTrain());
    assertEquals(248320, model.vocab().size());
    assertEquals("qwen35", model.metadata("general.architecture").orElseThrow());
    assertTrue(model.metadata("missing.metadata").isEmpty());
    assertFalse(model.metadata().isEmpty());
  }

  @Test
  void roundTripsChineseEmojiAndSpecialTokens() {
    Vocab vocab = model.vocab();
    String text = "你好，世界！Hello 🙂";
    int[] tokens = vocab.tokenize(text, false, false);
    assertEquals(text, vocab.detokenize(tokens, false, false));
    Utf8StreamDecoder decoder = new Utf8StreamDecoder();
    StringBuilder result = new StringBuilder();
    for (int token : tokens) {
      result.append(decoder.decode(vocab.tokenToBytes(token, false)));
    }
    result.append(decoder.finish());
    assertEquals(text, result.toString());
    assertEquals(1, vocab.tokenize("<|im_start|>", false, true).length);
    assertThrows(IllegalArgumentException.class, () -> vocab.tokenToBytes(-5, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> vocab.detokenize(new int[] {vocab.size()}, false, false));
  }

  @Test
  void rendersChatmlExactlyAndDetectsThinkingSwitch() {
    String prompt =
        ChatTemplate.named("chatml")
            .apply(List.of(ChatMessage.system("system"), ChatMessage.user("hello")), true);
    assertEquals(
        "<|im_start|>system\nsystem<|im_end|>\n<|im_start|>user\nhello<|im_end|>\n<|im_start|>assistant\n",
        prompt);
    assertTrue(ChatTemplate.fromModel(model).supportsThinkingSwitch());
  }

  @Test
  void greedyAndSeededSamplingRepeatAndCancellationReturnsNormally() {
    try (LlamaContext context =
            model.newContext(ContextParams.builder().contextLength(512).batchSize(16).build());
        Sampler greedy = Sampler.greedy(model.vocab());
        Sampler seeded = Sampler.builder().seed(42).build(model.vocab())) {
      int[] prompt = model.vocab().tokenize("Write a long story about a little cat:", true, false);
      GenerationConfig limit = new GenerationConfig(8, null);
      GenerationResult first = context.generate(prompt, greedy, limit, TokenListener.NONE);
      assertEquals(StopReason.MAX_TOKENS, first.stopReason());
      assertEquals(8, first.generatedTokens());
      context.clear();
      greedy.reset();
      assertEquals(
          first.text(), context.generate(prompt, greedy, limit, TokenListener.NONE).text());
      context.clear();
      String randomFirst = context.generate(prompt, seeded, limit, TokenListener.NONE).text();
      context.clear();
      seeded.reset();
      assertEquals(randomFirst, context.generate(prompt, seeded, limit, TokenListener.NONE).text());
      context.clear();
      greedy.reset();
      AtomicInteger emitted = new AtomicInteger();
      GenerationResult cancelled =
          context.generate(
              prompt,
              greedy,
              new GenerationConfig(64, () -> emitted.get() >= 3),
              text -> emitted.incrementAndGet());
      assertEquals(StopReason.CANCELLED, cancelled.stopReason());
      assertTrue(cancelled.generatedTokens() >= 3);
      context.clear();
      GenerationResult immediate =
          context.generate(prompt, greedy, new GenerationConfig(3, () -> true), TokenListener.NONE);
      assertEquals(0, immediate.promptTokens());
      assertEquals(0, context.usedTokens());
    }
  }

  @Test
  void handlesFullContextAndValidatesBeforeNativeCall() {
    try (LlamaContext context =
            model.newContext(ContextParams.builder().contextLength(64).batchSize(16).build());
        Sampler sampler = Sampler.greedy(model.vocab())) {
      int[] tokens = new int[context.contextLength() - 1];
      Arrays.fill(tokens, model.vocab().tokenize("x", false, false)[0]);
      GenerationResult result =
          context.generate(tokens, sampler, new GenerationConfig(8, null), TokenListener.NONE);
      assertEquals(StopReason.CONTEXT_FULL, result.stopReason());
      context.clear();
      assertThrows(
          ContextOverflowException.class,
          () -> context.decode(new int[context.contextLength() + 1]));
      assertThrows(IllegalStateException.class, () -> context.sample(sampler));
    }
  }

  @Test
  void preservesHistoryOnListenerFailureAfterTrimmingAndCancelledTurns() {
    try (LlamaContext context =
            model.newContext(ContextParams.builder().contextLength(256).batchSize(16).build());
        Sampler sampler = Sampler.greedy(model.vocab())) {
      ChatSession session =
          new ChatSession(context, sampler, ChatTemplate.fromModel(model))
              .generationConfig(new GenerationConfig(24, null));
      session.send("我叫小明，请记住我的名字。", TokenListener.NONE);
      List<ChatMessage> before = session.history();
      RuntimeException listenerFailure = new RuntimeException("listener test");
      RuntimeException actual =
          assertThrows(
              RuntimeException.class,
              () ->
                  session.send(
                      "请介绍 Java 的类和对象。".repeat(12),
                      piece -> {
                        throw listenerFailure;
                      }));
      assertSame(listenerFailure, actual);
      assertEquals(before, session.history());
      session.generationConfig(new GenerationConfig(16, () -> true));
      session.send("hello", TokenListener.NONE);
      assertEquals(before, session.history());
      session.generationConfig(new GenerationConfig(64, null));
      ChatReply answer = session.send("我叫什么名字？只回答名字。", TokenListener.NONE);
      assertTrue(answer.content().contains("小明"), answer.content());
      assertEquals(StopReason.END_OF_GENERATION, answer.stats().stopReason());
      session.reset();
      assertTrue(session.history().isEmpty());
      session.generationConfig(new GenerationConfig(16, null));
      int dropped = 0;
      for (int turn = 0; turn < 10; turn++) {
        dropped +=
            session
                .send("记住本轮编号 " + turn + "，请介绍 Java 中的封装和继承。", TokenListener.NONE)
                .droppedTurns();
      }
      assertTrue(dropped > 0, "Old turns should be trimmed when the context fills");
      assertEquals(0, session.history().size() % 2);
    }
  }

  @Test
  void rejectsReentryAndRecoversFromListenerFailure() {
    try (LlamaContext context =
            model.newContext(ContextParams.builder().contextLength(512).build());
        Sampler sampler = Sampler.greedy(model.vocab())) {
      int[] prompt = model.vocab().tokenize("Write a long story:", true, false);
      assertThrows(
          IllegalStateException.class,
          () ->
              context.generate(
                  prompt, sampler, new GenerationConfig(8, null), piece -> context.close()));
      assertThrows(IllegalStateException.class, () -> context.decode(prompt));
      context.clear();
      sampler.reset();
      assertNotNull(
          context.generate(prompt, sampler, new GenerationConfig(2, null), TokenListener.NONE));
    }
  }

  @Test
  void cancelsModelLoadAndPreservesCallbackCause() {
    RuntimeException marker = new RuntimeException("progress test");
    ModelLoadException failure =
        assertThrows(
            ModelLoadException.class,
            () ->
                LlamaModel.load(
                    modelFile,
                    ModelParams.builder()
                        .gpuLayers(0)
                        .progressListener(
                            progress -> {
                              throw marker;
                            })
                        .build()));
    assertEquals(ErrorCode.E206, failure.code());
    assertSame(marker, failure.getCause());
  }

  @Test
  void parentCloseInvalidatesVocabAndClosesChildren() {
    LlamaModel owned = LlamaModel.load(modelFile, ModelParams.builder().gpuLayers(0).build());
    Vocab vocab = owned.vocab();
    LlamaContext child = owned.newContext(ContextParams.builder().contextLength(64).build());
    owned.close();
    owned.close();
    child.close();
    assertTrue(child.isClosed());
    assertThrows(IllegalStateException.class, child::usedTokens);
    assertThrows(IllegalStateException.class, vocab::size);
    try (LlamaModel vocabularyOnly =
        LlamaModel.load(modelFile, ModelParams.builder().vocabOnly(true).build())) {
      assertEquals(248320, vocabularyOnly.vocab().size());
      assertThrows(
          IllegalStateException.class, () -> vocabularyOnly.newContext(ContextParams.defaults()));
    }
  }
}
