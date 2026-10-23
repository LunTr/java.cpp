package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({
  "devices",
  "tensor_buft_overrides",
  "n_gpu_layers",
  "split_mode",
  "load_mode",
  "lazy_mode",
  "main_gpu",
  "tensor_split",
  "progress_callback",
  "progress_callback_user_data",
  "kv_overrides",
  "vocab_only",
  "check_tensors",
  "use_extra_bufts",
  "no_host",
  "no_alloc",
  "load_mtp"
})
public class LlamaModelParams extends Structure {
  public Pointer devices;
  public Pointer tensor_buft_overrides;
  public int n_gpu_layers;
  public int split_mode;
  public int load_mode;
  public int lazy_mode;
  public int main_gpu;
  public Pointer tensor_split;
  public LlamaLibrary.ProgressCallback progress_callback;
  public Pointer progress_callback_user_data;
  public Pointer kv_overrides;
  public byte vocab_only;
  public byte check_tensors;
  public byte use_extra_bufts;
  public byte no_host;
  public byte no_alloc;
  public byte load_mtp;

  /** Exposes field offsets for ABI verification only. */
  public int offsetOf(String name) {
    return fieldOffset(name);
  }

  /** C struct passed by value. */
  public static final class ByValue extends LlamaModelParams implements Structure.ByValue {}
}
