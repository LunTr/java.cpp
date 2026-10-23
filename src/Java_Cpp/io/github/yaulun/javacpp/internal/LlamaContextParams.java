package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Exact b11669 C layout; public fields are required by JNA reflection. */
@Structure.FieldOrder({
  "n_ctx",
  "n_batch",
  "n_ubatch",
  "n_seq_max",
  "n_rs_seq",
  "n_outputs_max",
  "n_outputs_max_per_seq",
  "n_threads",
  "n_threads_batch",
  "ctx_type",
  "rope_scaling_type",
  "pooling_type",
  "attention_type",
  "flash_attn_type",
  "rope_freq_base",
  "rope_freq_scale",
  "yarn_ext_factor",
  "yarn_attn_factor",
  "yarn_beta_fast",
  "yarn_beta_slow",
  "yarn_orig_ctx",
  "defrag_thold",
  "cb_eval",
  "cb_eval_user_data",
  "type_k",
  "type_v",
  "moe_cache_size",
  "abort_callback",
  "abort_callback_data",
  "embeddings",
  "offload_kqv",
  "no_perf",
  "op_offload",
  "swa_full",
  "kv_unified",
  "samplers",
  "n_samplers",
  "ctx_other"
})
public class LlamaContextParams extends Structure {
  public int n_ctx;
  public int n_batch;
  public int n_ubatch;
  public int n_seq_max;
  public int n_rs_seq;
  public int n_outputs_max;
  public int n_outputs_max_per_seq;
  public int n_threads;
  public int n_threads_batch;
  public int ctx_type;
  public int rope_scaling_type;
  public int pooling_type;
  public int attention_type;
  public int flash_attn_type;
  public float rope_freq_base;
  public float rope_freq_scale;
  public float yarn_ext_factor;
  public float yarn_attn_factor;
  public float yarn_beta_fast;
  public float yarn_beta_slow;
  public int yarn_orig_ctx;
  public float defrag_thold;
  public Pointer cb_eval;
  public Pointer cb_eval_user_data;
  public int type_k;
  public int type_v;
  public long moe_cache_size;
  public Pointer abort_callback;
  public Pointer abort_callback_data;
  public byte embeddings;
  public byte offload_kqv;
  public byte no_perf;
  public byte op_offload;
  public byte swa_full;
  public byte kv_unified;
  public Pointer samplers;
  public long n_samplers;
  public Pointer ctx_other;

  /** Exposes field offsets for ABI verification only. */
  public int offsetOf(String name) {
    return fieldOffset(name);
  }

  /** C struct passed by value. */
  public static final class ByValue extends LlamaContextParams implements Structure.ByValue {}
}
