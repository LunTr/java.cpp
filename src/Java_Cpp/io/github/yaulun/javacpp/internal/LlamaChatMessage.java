package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({"role", "content"})
public class LlamaChatMessage extends Structure {
  public Pointer role;
  public Pointer content;

  /** Exposes field offsets for ABI verification only. */
  public int offsetOf(String name) {
    return fieldOffset(name);
  }

  /** C struct passed by value. */
  public static final class ByValue extends LlamaChatMessage implements Structure.ByValue {}
}
