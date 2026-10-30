package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** b11669 llama_batch input container returned and consumed by value. */
@Structure.FieldOrder({"n_tokens", "token", "embd", "pos", "n_seq_id", "seq_id", "logits"})
public class LlamaBatch extends Structure {
  public int n_tokens;
  public Pointer token;
  public Pointer embd;
  public Pointer pos;
  public Pointer n_seq_id;
  public Pointer seq_id;
  public Pointer logits;

  public static class ByValue extends LlamaBatch implements Structure.ByValue {}
}
