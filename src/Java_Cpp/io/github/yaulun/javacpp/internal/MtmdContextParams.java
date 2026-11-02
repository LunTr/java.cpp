package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Exact pinned mtmd.h layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({
  "use_gpu",
  "device",
  "print_timings",
  "n_threads",
  "image_marker",
  "media_marker",
  "flash_attn_type",
  "warmup",
  "image_min_tokens",
  "image_max_tokens",
  "cb_eval",
  "cb_eval_user_data",
  "batch_max_tokens",
  "progress_callback",
  "progress_callback_user_data"
})
public class MtmdContextParams extends Structure {
  /** Native C bool is one byte. */
  public byte use_gpu;

  public Pointer device;
  public byte print_timings;
  public int n_threads;
  public String image_marker;
  public String media_marker;
  public int flash_attn_type;
  public byte warmup;
  public int image_min_tokens;
  public int image_max_tokens;
  public MtmdLibrary.SchedEvalCallback cb_eval;
  public Pointer cb_eval_user_data;
  public int batch_max_tokens;
  public MtmdLibrary.ProgressCallback progress_callback;
  public Pointer progress_callback_user_data;

  /** By-value view for mtmd_context_params_default / mtmd_init_from_file. */
  public static class ByValue extends MtmdContextParams implements Structure.ByValue {}
}
