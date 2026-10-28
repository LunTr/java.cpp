package io.github.yaulun.javacpp;

import com.sun.jna.Pointer;
import io.github.yaulun.javacpp.compat.python.*;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import java.lang.ref.Cleaner;
import java.math.BigInteger;
import java.util.function.Consumer;

/** Native execution of the original Python sampling policy without Builder range restrictions. */
public final class PythonNativeSampling {
  private static final Cleaner cleaner = Cleaner.create();

  private PythonNativeSampling() {}

  public static PythonSamplerBuilder builder(LlamaModel model, LlamaContext context) {
    return model.withOpen(() -> new Chain(model, context));
  }

  private static final class Chain implements PythonSamplerBuilder, PythonLlamaSampler {
    private final LlamaModel model;
    private final LlamaContext context;
    private final LlamaLibrary library;
    private final Pointer chain;
    private final Cleaner.Cleanable cleanup;
    private Consumer<float[]> processor;

    private Chain(LlamaModel model, LlamaContext context) {
      this.model = model;
      this.context = context;
      library = model.library();
      chain = library.llama_sampler_chain_init(library.llama_sampler_chain_default_params());
      LlamaLibrary retainedLibrary = library;
      Pointer retainedChain = chain;
      cleanup = cleaner.register(this, () -> retainedLibrary.llama_sampler_free(retainedChain));
    }

    private static float f(Object value) {
      return ((Number) value).floatValue();
    }

    private static int i(Object value) {
      return ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    public void add(String operation, Object... a) {
      if (operation.equals("custom")) {
        processor = (Consumer<float[]>) a[0];
        return;
      }
      model.withOpen(
          () -> {
            Pointer child =
                switch (operation) {
                  case "penalties" ->
                      library.llama_sampler_init_penalties(
                          i(a[0]), i(a[1]), f(a[2]), f(a[3]), f(a[4]));
                  case "grammar" -> {
                    var grammar = (PythonGrammar) a[0];
                    yield library.llama_sampler_init_grammar(
                        library.llama_model_get_vocab(model.handle()),
                        grammar.grammar(),
                        grammar.root());
                  }
                  case "dist" -> library.llama_sampler_init_dist(((BigInteger) a[0]).intValue());
                  case "greedy" -> library.llama_sampler_init_greedy();
                  case "mirostat" ->
                      library.llama_sampler_init_mirostat(
                          i(a[0]), ((BigInteger) a[1]).intValue(), f(a[2]), f(a[3]), i(a[4]));
                  case "mirostat_v2" ->
                      library.llama_sampler_init_mirostat_v2(
                          ((BigInteger) a[0]).intValue(), f(a[1]), f(a[2]));
                  case "top_k" -> library.llama_sampler_init_top_k(i(a[0]));
                  case "typical" ->
                      library.llama_sampler_init_typical(f(a[0]), ((Number) a[1]).longValue());
                  case "top_p" ->
                      library.llama_sampler_init_top_p(f(a[0]), ((Number) a[1]).longValue());
                  case "min_p" ->
                      library.llama_sampler_init_min_p(f(a[0]), ((Number) a[1]).longValue());
                  case "temp" -> library.llama_sampler_init_temp(f(a[0]));
                  default -> throw new IllegalArgumentException(operation);
                };
            if (child == null) {
              cleanup.clean();
              throw new IllegalStateException("Native sampler allocation failed");
            }
            library.llama_sampler_chain_add(chain, child);
            return null;
          });
    }

    public PythonLlamaSampler build() {
      return this;
    }

    public synchronized int sample(int index) {
      return context.samplePolicy(chain, model, index, processor);
    }
  }
}
