package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({
  "nthread",
  "ftype",
  "output_tensor_type",
  "token_embedding_type",
  "allow_requantize",
  "quantize_output_tensor",
  "only_copy",
  "pure",
  "keep_split",
  "dry_run",
  "imatrix",
  "kv_overrides",
  "tt_overrides",
  "prune_layers",
  "max_buf_size"
})
public class LlamaModelQuantizeParams extends Structure {
  public int nthread;
  public int ftype;
  public int output_tensor_type;
  public int token_embedding_type;
  public byte allow_requantize;
  public byte quantize_output_tensor;
  public byte only_copy;
  public byte pure;
  public byte keep_split;
  public byte dry_run;
  public Pointer imatrix;
  public Pointer kv_overrides;
  public Pointer tt_overrides;
  public Pointer prune_layers;
  public long max_buf_size;

  /** Exposes field offsets for ABI verification only. */
  public int offsetOf(String name) {
    return fieldOffset(name);
  }

  /** C struct passed by value. */
  public static final class ByValue extends LlamaModelQuantizeParams implements Structure.ByValue {}
}
