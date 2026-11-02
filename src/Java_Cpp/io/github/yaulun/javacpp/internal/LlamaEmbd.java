package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({"data", "n_rows", "n_embd"})
public class LlamaEmbd extends Structure {
  public Pointer data;
  public long n_rows;
  public long n_embd;

  /** C struct passed by value. */
  public static final class ByValue extends LlamaEmbd implements Structure.ByValue {}
}
