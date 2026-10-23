package io.github.yaulun.javacpp.internal;

import java.util.ArrayList;
import java.util.List;

/** Checks sizes and critical offsets before model loading. */
public final class StructSelfTest {
  private StructSelfTest() {}

  /** Returns all mismatches with the b11669 x64 ABI and selected default values. */
  public static List<String> verify(LlamaLibrary llama) {
    List<String> problems = new ArrayList<>();
    LlamaModelParams modelLayout = new LlamaModelParams();
    LlamaContextParams contextLayout = new LlamaContextParams();
    check(problems, "model size", modelLayout.size(), 80);
    check(problems, "context size", contextLayout.size(), 168);
    check(problems, "sampler size", new LlamaSamplerChainParams().size(), 1);
    check(problems, "message size", new LlamaChatMessage().size(), 16);
    check(problems, "progress_callback offset", modelLayout.offsetOf("progress_callback"), 48);
    check(problems, "vocab_only offset", modelLayout.offsetOf("vocab_only"), 72);
    check(problems, "flash_attn_type offset", contextLayout.offsetOf("flash_attn_type"), 52);
    check(problems, "type_k offset", contextLayout.offsetOf("type_k"), 104);
    check(problems, "embeddings offset", contextLayout.offsetOf("embeddings"), 136);
    check(problems, "samplers offset", contextLayout.offsetOf("samplers"), 144);
    check(problems, "ctx_other offset", contextLayout.offsetOf("ctx_other"), 160);
    if (!problems.isEmpty()) {
      return List.copyOf(problems);
    }
    LlamaModelParams model = llama.llama_model_default_params();
    check(problems, "model.n_gpu_layers", model.n_gpu_layers, -1);
    check(problems, "model.split_mode", model.split_mode, 1);
    check(problems, "model.load_mode", model.load_mode, -1);
    check(problems, "model.lazy_mode", model.lazy_mode, 1);
    check(problems, "model.vocab_only", model.vocab_only, 0);
    check(problems, "model.check_tensors", model.check_tensors, 0);
    check(problems, "model.use_extra_bufts", model.use_extra_bufts, 1);
    LlamaContextParams context = llama.llama_context_default_params();
    check(problems, "context.n_ctx", context.n_ctx, 512);
    check(problems, "context.n_batch", context.n_batch, 2048);
    check(problems, "context.n_ubatch", context.n_ubatch, 512);
    check(problems, "context.n_seq_max", context.n_seq_max, 1);
    check(problems, "context.n_threads", context.n_threads, 4);
    check(problems, "context.flash_attn_type", context.flash_attn_type, -1);
    check(problems, "context.type_k", context.type_k, 1);
    check(problems, "context.type_v", context.type_v, 1);
    check(problems, "context.offload_kqv", context.offload_kqv, 1);
    check(problems, "context.no_perf", context.no_perf, 1);
    check(problems, "context.op_offload", context.op_offload, 1);
    check(problems, "context.swa_full", context.swa_full, 1);
    check(problems, "context.kv_unified", context.kv_unified, 0);
    if (context.yarn_ext_factor != -1.0f
        || context.samplers != null
        || context.n_samplers != 0
        || context.ctx_other != null) {
      problems.add("Unexpected context default values");
    }
    check(problems, "sampler.no_perf", llama.llama_sampler_chain_default_params().no_perf, 1);
    return List.copyOf(problems);
  }

  private static void check(List<String> problems, String name, int actual, int expected) {
    if (actual != expected) {
      problems.add(name + "=" + actual + ", expected=" + expected);
    }
  }
}
