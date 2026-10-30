package io.github.yaulun.javacpp.compat.python;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
