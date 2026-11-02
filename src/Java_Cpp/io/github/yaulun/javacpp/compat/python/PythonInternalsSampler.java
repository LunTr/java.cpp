package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonValues;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** _internals.LlamaSampler: empty chain plus ordered add_* calls and ExitStack cleanup. */
public final class PythonInternalsSampler {
  private final PythonInternalsPort port;
  private final PythonWarnings warnings;
  private final PythonExitStack exitStack = new PythonExitStack();
  private Object sampler;
  private final List<Object[]> customSamplers = new ArrayList<>();

  public PythonInternalsSampler(PythonInternalsPort port, PythonWarnings warnings) {
    this.port = Objects.requireNonNull(port, "port");
    this.warnings = Objects.requireNonNull(warnings, "warnings");
    this.sampler = port.samplerChainInit(port.samplerChainDefaultParams());
    exitStack.callback(this::freeSampler);
  }

  /** Custom samplers leave the chain in reverse before the chain itself is freed. */
  private void freeSampler() {
    if (sampler == null) {
      return;
    }
    for (int i = customSamplers.size() - 1; i >= 0; i--) {
      port.samplerChainRemove(sampler, ((Number) customSamplers.get(i)[0]).intValue());
    }
    port.samplerFree(sampler);
    sampler = null;
  }

  public Object sampler() {
    return sampler;
  }

  /** The original attribute is writable, e.g. when a model adopts this chain. */
  public void setSampler(Object sampler) {
    this.sampler = sampler;
  }

  /** Entries are (chain index, custom sampler) pairs appended by add_custom callers. */
  public List<Object[]> customSamplers() {
    return customSamplers;
  }

  public void close() {
    exitStack.close();
  }

  public void addGreedy() {
    port.samplerChainAdd(sampler, port.samplerInitGreedy());
  }

  /** The seed is an unsigned 32-bit value in the native call. */
  public void addDist(long seed) {
    port.samplerChainAdd(sampler, port.samplerInitDist(seed));
  }

  /** The original only emits a DeprecationWarning and adds nothing to the chain. */
  public void addSoftmax(PythonWarningLocation location) {
    warnings.deprecated(
        "add_softmax", "llama_sampler_init_dist now samples directly from logits", location);
  }

  public void addTopK(int k) {
    port.samplerChainAdd(sampler, port.samplerInitTopK(k));
  }

  public void addTopP(double p) {
    addTopP(p, 1);
  }

  public void addTopP(double p, long minKeep) {
    port.samplerChainAdd(sampler, port.samplerInitTopP(p, minKeep));
  }

  public void addMinP(double p) {
    addMinP(p, 1);
  }

  public void addMinP(double p, long minKeep) {
    port.samplerChainAdd(sampler, port.samplerInitMinP(p, minKeep));
  }

  public void addTemp(double temperature) {
    port.samplerChainAdd(sampler, port.samplerInitTemp(temperature));
  }

  public void addTypical(double p) {
    addTypical(p, 1);
  }

  public void addTypical(double p, long minKeep) {
    port.samplerChainAdd(sampler, port.samplerInitTypical(p, minKeep));
  }

  public void addTempExt(double t, double delta, double exponent) {
    port.samplerChainAdd(sampler, port.samplerInitTempExt(t, delta, exponent));
  }

  public void addXtc(double p, double t, long minKeep, long seed) {
    port.samplerChainAdd(sampler, port.samplerInitXtc(p, t, minKeep, seed));
  }

  public void addTopNSigma(double n) {
    port.samplerChainAdd(sampler, port.samplerInitTopNSigma(n));
  }

  public void addMirostat(int nVocab, long seed, double tau, double eta, int m) {
    port.samplerChainAdd(sampler, port.samplerInitMirostat(nVocab, seed, tau, eta, m));
  }

  public void addMirostatV2(long seed, double tau, double eta) {
    port.samplerChainAdd(sampler, port.samplerInitMirostatV2(seed, tau, eta));
  }

  /** grammar._grammar and grammar._root are UTF-8 encoded at the boundary. */
  public void addGrammar(PythonLlamaModel model, PythonGrammar grammar) {
    port.samplerChainAdd(
        sampler,
        port.samplerInitGrammar(model.vocab(), utf8(grammar.grammar()), utf8(grammar.root())));
  }

  public void addGrammarLazyPatterns(
      PythonLlamaModel model,
      PythonGrammar grammar,
      List<String> triggerPatterns,
      java.util.List<Integer> triggerTokens) {
    List<byte[]> patterns = new ArrayList<>(triggerPatterns.size());
    for (String pattern : triggerPatterns) {
      patterns.add(utf8(pattern));
    }
    port.samplerChainAdd(
        sampler,
        port.samplerInitGrammarLazyPatterns(
            model.vocab(), utf8(grammar.grammar()), utf8(grammar.root()), patterns, triggerTokens));
  }

  public void addDry(
      PythonLlamaModel model,
      double dryMultiplier,
      double dryBase,
      int dryAllowedLength,
      int dryPenaltyLastN,
      List<String> seqBreakers) {
    List<byte[]> breakers = new ArrayList<>(seqBreakers.size());
    for (String breaker : seqBreakers) {
      breakers.add(utf8(breaker));
    }
    port.samplerChainAdd(
        sampler,
        port.samplerInitDry(
            model.vocab(), dryMultiplier, dryBase, dryAllowedLength, dryPenaltyLastN, breakers));
  }

  /** Dictionary order is preserved into the bias array, as in the original. */
  public void addLogitBias(int nVocab, Map<Integer, Double> logitBias) {
    int[] tokens = new int[logitBias.size()];
    double[] biases = new double[logitBias.size()];
    int index = 0;
    for (Map.Entry<Integer, Double> entry : logitBias.entrySet()) {
      tokens[index] = entry.getKey();
      biases[index] = entry.getValue();
      index++;
    }
    port.samplerChainAdd(sampler, port.samplerInitLogitBias(nVocab, tokens, biases));
  }

  public void addInfill(PythonLlamaModel model) {
    port.samplerChainAdd(sampler, port.samplerInitInfill(model.vocab()));
  }

  /** The chain index of the appended sampler is remembered for removal before free. */
  public void addCustom(Consumer<Object> applyFunc) {
    PythonCustomSampler customSampler = new PythonCustomSampler(port, applyFunc);
    port.samplerChainAdd(sampler, customSampler.getSampler());
    customSamplers.add(new Object[] {port.samplerChainN(sampler) - 1, customSampler});
  }

  public void accept(int token) {
    port.samplerAccept(sampler, token);
  }

  public void reset() {
    port.samplerReset(sampler);
  }

  /** Custom samplers cannot be cloned; the clone registers its own free on the stack. */
  public PythonInternalsSampler clone() {
    if (!customSamplers.isEmpty()) {
      throw PythonValues.error(
          PythonErrorType.NOT_IMPLEMENTED_ERROR,
          "Cannot clone LlamaSampler that contains custom samplers");
    }
    PythonInternalsSampler cloned =
        new PythonInternalsSampler(port, warnings, port.samplerClone(sampler));
    return cloned;
  }

  private PythonInternalsSampler(PythonInternalsPort port, PythonWarnings warnings, Object cloned) {
    this.port = port;
    this.warnings = warnings;
    this.sampler = cloned;
    exitStack.callback(this::freeClonedSampler);
  }

  /** The cloned free_sampler has no custom-sampler removal loop. */
  private void freeClonedSampler() {
    if (sampler == null) {
      return;
    }
    port.samplerFree(sampler);
    sampler = null;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  public void addPenalties(
      int nVocab, int penaltyLastN, double repeat, double freq, double present) {
    port.samplerChainAdd(
        sampler, port.samplerInitPenalties(nVocab, penaltyLastN, repeat, freq, present));
  }

  /** Unsigned 32-bit seed reported by the chain. */
  public long getSeed() {
    return port.samplerGetSeed(sampler);
  }

  public int sample(PythonLlamaContext ctx) {
    return sample(ctx, -1);
  }

  public int sample(PythonLlamaContext ctx, int index) {
    return port.samplerSample(sampler, ctx.context(), index);
  }
}
