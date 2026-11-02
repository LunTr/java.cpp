package io.github.yaulun.javacpp.compat.python;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * _internals.CustomSampler: builds the llama_sampler_i/llama_sampler pair through the port so the
 * ctypes call sequence is replayable. The optional slots receive the wrapped literal 0, matching
 * llama_sampler_i_name(0) and friends.
 */
public final class PythonCustomSampler {
  private final PythonInternalsPort port;
  private final Consumer<Object> applyFunc;
  private final Object applyWrapperRef;
  private final Object sampler;

  /** apply_wrapper as a named callback; __name__ is observable in the ctypes trace. */
  private record NamedApply(Consumer<Object> target) implements Consumer<Object> {
    @Override
    public void accept(Object curP) {
      target.accept(curP);
    }

    @Override
    public String toString() {
      return "apply_wrapper";
    }
  }

  public PythonCustomSampler(PythonInternalsPort port, Consumer<Object> applyFunc) {
    this.port = Objects.requireNonNull(port, "port");
    this.applyFunc = Objects.requireNonNull(applyFunc, "applyFunc");

    Consumer<Object> applyWrapper = new NamedApply(curP -> this.applyFunc.accept(curP));

    Object samplerI = port.newSamplerInterface();
    port.samplerIAssign(samplerI, "apply", port.samplerIFunction("apply", applyWrapper));
    this.applyWrapperRef = applyWrapper;

    port.samplerIAssign(samplerI, "name", port.samplerIFunction("name", 0));
    port.samplerIAssign(samplerI, "accept", port.samplerIFunction("accept", 0));
    port.samplerIAssign(samplerI, "reset", port.samplerIFunction("reset", 0));
    port.samplerIAssign(samplerI, "clone", port.samplerIFunction("clone", 0));
    port.samplerIAssign(samplerI, "free", port.samplerIFunction("free", 0));

    this.sampler = port.newLlamaSampler();
    port.samplerAssign(sampler, "iface", port.pointerOf(samplerI));
    port.samplerAssign(sampler, "ctx", null);
  }

  public Consumer<Object> applyFunc() {
    return applyFunc;
  }

  public Object applyWrapperRef() {
    return applyWrapperRef;
  }

  /** ctypes.pointer(self.sampler): a pointer handle over the sampler struct. */
  public Object getSampler() {
    return port.pointerOf(sampler);
  }
}
