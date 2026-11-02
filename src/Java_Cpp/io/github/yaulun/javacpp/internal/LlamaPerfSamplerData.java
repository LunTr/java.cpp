package io.github.yaulun.javacpp.internal;

import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({"t_sample_ms", "n_sample"})
public class LlamaPerfSamplerData extends Structure {
  public double t_sample_ms;
  public int n_sample;

  /** C struct returned by value. */
  public static final class ByValue extends LlamaPerfSamplerData
      implements Structure.ByValue {}
}
