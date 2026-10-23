package io.github.yaulun.javacpp.internal;

import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({"no_perf"})
public class LlamaSamplerChainParams extends Structure {
  public byte no_perf;

  /** Exposes field offsets for ABI verification only. */
  public int offsetOf(String name) {
    return fieldOffset(name);
  }

  /** C struct passed by value. */
  public static final class ByValue extends LlamaSamplerChainParams implements Structure.ByValue {}
}
