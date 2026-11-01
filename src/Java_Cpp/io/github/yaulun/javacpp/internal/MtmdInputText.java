package io.github.yaulun.javacpp.internal;

import com.sun.jna.Structure;

/** Exact pinned mtmd.h layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({"text", "text_len", "add_special", "parse_special"})
public class MtmdInputText extends Structure {
  public String text;
  public long text_len;
  public byte add_special;
  public byte parse_special;
}
