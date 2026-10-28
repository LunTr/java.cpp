package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** b11669 CPU sampling candidate descriptor. */
@Structure.FieldOrder({"data", "size", "selected", "sorted"})
public final class LlamaTokenDataArray extends Structure {
  public Pointer data;
  public long size;
  public long selected;
  public byte sorted;
}
