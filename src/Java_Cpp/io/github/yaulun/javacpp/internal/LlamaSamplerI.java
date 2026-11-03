package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({
  "name",
  "accept",
  "apply",
  "reset",
  "clone",
  "free",
  "backend_init",
  "backend_accept",
  "backend_apply",
  "backend_set_input",
  "backend_reset",
  "copy_state"
})
public class LlamaSamplerI extends Structure {
  public Pointer name;
  public Pointer accept;
  public Pointer apply;
  public Pointer reset;
  public Pointer clone;
  public Pointer free;
  public Pointer backend_init;
  public Pointer backend_accept;
  public Pointer backend_apply;
  public Pointer backend_set_input;
  public Pointer backend_reset;
  public Pointer copy_state;
}
