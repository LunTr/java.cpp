package io.github.yaulun.demo;

import io.github.yaulun.javacpp.ContextParams;
import io.github.yaulun.javacpp.GenerationConfig;
import io.github.yaulun.javacpp.LlamaContext;
import io.github.yaulun.javacpp.LlamaModel;
import io.github.yaulun.javacpp.ModelParams;
import io.github.yaulun.javacpp.Sampler;
import io.github.yaulun.javacpp.TokenListener;
import io.github.yaulun.javacpp.exception.ContextOverflowException;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.JavaCppException;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class RuntimeSelfTest {
  private RuntimeSelfTest() {}

  static String run(LlamaModel model) throws Exception {
    List<String> completed = new ArrayList<>();
    Path directory = Files.createTempDirectory("javacpp-self-test-");
    try {
      expect(
          ModelLoadException.class,
          ErrorCode.E201,
          () -> LlamaModel.load(directory.resolve("missing.gguf"), ModelParams.defaults()));
      completed.add("missing model -> E201");
      Path html = directory.resolve("page.gguf");
      Files.writeString(html, "<html>download page</html>");
      expect(
          ModelLoadException.class,
          ErrorCode.E203,
          () -> LlamaModel.load(html, ModelParams.defaults()));
      completed.add("HTML model -> E203");
      expect(IllegalArgumentException.class, null, () -> Sampler.builder().temperature(-1));
      completed.add("negative temperature -> IllegalArgumentException");
      expect(IllegalArgumentException.class, null, () -> model.vocab().tokenToBytes(-5, true));
      completed.add("invalid token -> IllegalArgumentException");
      try (LlamaContext small =
          model.newContext(ContextParams.builder().contextLength(64).batchSize(32).build())) {
        int[] overflow = new int[small.contextLength() + 1];
        Arrays.fill(overflow, model.vocab().tokenize("x", false, false)[0]);
        expect(ContextOverflowException.class, ErrorCode.E302, () -> small.decode(overflow));
        completed.add("actual context overflow -> E302");
      }
      LlamaContext closed = model.newContext(ContextParams.builder().contextLength(64).build());
      closed.close();
      closed.close();
      expect(IllegalStateException.class, null, closed::usedTokens);
      completed.add("closed context -> IllegalStateException; repeated close succeeded");
      testConcurrentAccess(model);
      completed.add("concurrent use, callback reentry and close from callback rejected");
      return String.join("\n", completed);
    } finally {
      Files.deleteIfExists(directory.resolve("page.gguf"));
      Files.deleteIfExists(directory);
    }
  }

  static void expect(Class<? extends Throwable> type, ErrorCode code, Runnable action) {
    try {
      action.run();
    } catch (Throwable failure) {
      if (!type.isInstance(failure)) {
        throw new AssertionError("Expected " + type + ", got " + failure, failure);
      }
      if (code != null
          && (!(failure instanceof JavaCppException nativeFailure)
              || nativeFailure.code() != code)) {
        throw new AssertionError("Expected " + code + ", got " + failure, failure);
      }
      return;
    }
    throw new AssertionError("Expected " + type.getSimpleName());
  }

  private static void testConcurrentAccess(LlamaModel model) throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (LlamaContext context =
            model.newContext(ContextParams.builder().contextLength(256).build());
        Sampler sampler = Sampler.greedy(model.vocab())) {
      Future<?> generation =
          worker.submit(
              () ->
                  context.generate(
                      model.vocab().tokenize("Write a short story:", true, false),
                      sampler,
                      new GenerationConfig(8, null),
                      piece -> {
                        expect(IllegalStateException.class, null, context::usedTokens);
                        expect(IllegalStateException.class, null, context::close);
                        expect(IllegalStateException.class, null, model::close);
                        expect(IllegalStateException.class, null, sampler::close);
                        entered.countDown();
                        try {
                          if (!release.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Self-test release timed out");
                          }
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                      }));
      try {
        if (!entered.await(45, TimeUnit.SECONDS)) {
          generation.get(1, TimeUnit.SECONDS);
          throw new AssertionError("No generated text reached concurrency callback");
        }
        expect(IllegalStateException.class, null, context::usedTokens);
        expect(
            IllegalStateException.class,
            null,
            () ->
                context.generate(
                    new int[] {0}, sampler, GenerationConfig.defaults(), TokenListener.NONE));
      } finally {
        release.countDown();
      }
      generation.get(45, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }
}
