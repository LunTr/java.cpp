package io.github.yaulun.javacpp.internal;

import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({
  "t_start_ms",
  "t_load_ms",
  "t_p_eval_ms",
  "t_eval_ms",
  "n_p_eval",
  "n_eval",
  "n_reused"
})
public class LlamaPerfContextData extends Structure {
  public double t_start_ms;
  public double t_load_ms;
  public double t_p_eval_ms;
  public double t_eval_ms;
  public int n_p_eval;
  public int n_eval;
  public int n_reused;

  /** C struct returned by value. */
  public static final class ByValue extends LlamaPerfContextData
      implements Structure.ByValue {}
}
