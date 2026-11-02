package io.github.yaulun.javacpp.internal;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;

/** b11669 C entry points; snake_case identifiers deliberately match the audited header. */
public interface LlamaLibrary extends Library {
  /** Strongly retained native log callback. */
  interface LogCallback extends Callback {
    void invoke(int level, Pointer text, Pointer userData);
  }

  /** Native C bool is one byte. */
  interface ProgressCallback extends Callback {
    byte invoke(float progress, Pointer userData);
  }

  /** User tensor-data callback for llama_model_init_from_user. */
  interface SetTensorDataCallback extends Callback {
    void invoke(Pointer tensor, Pointer userData);
  }

  /** Native C bool return; nonzero asks llama.cpp to abort evaluation. */
  interface AbortCallback extends Callback {
    byte invoke(Pointer userData);
  }

  String llama_version();

  String llama_print_system_info();

  void llama_log_set(LogCallback callback, Pointer data);

  void llama_backend_init();

  void llama_numa_init(int strategy);

  long llama_max_devices();

  long llama_max_parallel_sequences();

  byte llama_model_is_recurrent(Pointer model);

  Pointer llama_adapter_lora_init(Pointer model, String path);

  void llama_adapter_lora_free(Pointer adapter);

  int llama_set_adapters_lora(Pointer context, Pointer[] adapters, long count, float[] scales);

  byte llama_supports_gpu_offload();

  LlamaModelParams.ByValue llama_model_default_params();

  LlamaContextParams.ByValue llama_context_default_params();

  LlamaSamplerChainParams.ByValue llama_sampler_chain_default_params();

  Pointer llama_model_load_from_file(String file, LlamaModelParams.ByValue params);

  void llama_model_free(Pointer model);

  Pointer llama_model_get_vocab(Pointer model);

  int llama_model_n_layer(Pointer model);

  int llama_model_n_ctx_train(Pointer model);

  int llama_model_n_embd(Pointer model);

  int llama_model_desc(Pointer model, byte[] buffer, long size);

  long llama_model_size(Pointer model);

  long llama_model_n_params(Pointer model);

  byte llama_model_is_hybrid(Pointer model);

  int llama_model_meta_val_str(Pointer model, String key, byte[] buffer, long size);

  int llama_model_meta_count(Pointer model);

  int llama_model_meta_key_by_index(Pointer model, int index, byte[] buffer, long size);

  int llama_model_meta_val_str_by_index(Pointer model, int index, byte[] buffer, long size);

  String llama_model_chat_template(Pointer model, String name);

  int llama_vocab_n_tokens(Pointer vocab);

  int llama_vocab_bos(Pointer vocab);

  int llama_vocab_eos(Pointer vocab);

  int llama_vocab_nl(Pointer vocab);

  int llama_vocab_cls(Pointer vocab);

  int llama_vocab_sep(Pointer vocab);

  byte llama_vocab_get_add_bos(Pointer vocab);

  byte llama_vocab_get_add_eos(Pointer vocab);

  String llama_vocab_get_text(Pointer vocab, int token);

  byte llama_vocab_is_eog(Pointer vocab, int token);

  int llama_vocab_type(Pointer vocab);

  float llama_model_rope_freq_scale_train(Pointer model);

  float llama_vocab_get_score(Pointer vocab, int token);

  int llama_vocab_get_attr(Pointer vocab, int token);

  int llama_vocab_eot(Pointer vocab);

  int llama_vocab_fim_pre(Pointer vocab);

  int llama_vocab_fim_suf(Pointer vocab);

  int llama_vocab_fim_mid(Pointer vocab);

  int llama_tokenize(
      Pointer vocab,
      byte[] text,
      int textLength,
      int[] tokens,
      int capacity,
      byte addSpecial,
      byte parseSpecial);

  int llama_token_to_piece(
      Pointer vocab, int token, byte[] buffer, int capacity, int lstrip, byte special);

  int llama_detokenize(
      Pointer vocab,
      int[] tokens,
      int count,
      byte[] text,
      int capacity,
      byte removeSpecial,
      byte unparseSpecial);

  Pointer llama_init_from_model(Pointer model, LlamaContextParams.ByValue params);

  void llama_free(Pointer context);

  int llama_n_ctx(Pointer context);

  int llama_n_batch(Pointer context);

  Pointer llama_get_memory(Pointer context);

  byte llama_memory_seq_rm(Pointer memory, int sequence, int start, int end);

  Pointer llama_get_logits_ith(Pointer context, int index);

  long llama_state_get_size(Pointer context);

  long llama_state_get_data(Pointer context, byte[] destination, long size);

  long llama_state_set_data(Pointer context, byte[] source, long size);

  int llama_pooling_type(Pointer context);

  Pointer llama_get_embeddings(Pointer context);

  Pointer llama_get_embeddings_seq(Pointer context, int sequence);

  void llama_perf_context_reset(Pointer context);

  void llama_perf_context_print(Pointer context);

  void llama_memory_clear(Pointer memory, byte data);

  void llama_memory_seq_cp(Pointer memory, int src, int dst, int start, int end);

  void llama_memory_seq_keep(Pointer memory, int sequence);

  void llama_memory_seq_add(Pointer memory, int sequence, int start, int end, int shift);

  int llama_encode(Pointer context, LlamaBatch.ByValue batch);

  void llama_set_n_threads(Pointer context, int nThreads, int nThreadsBatch);

  Pointer llama_get_logits(Pointer context);

  Pointer llama_get_embeddings_ith(Pointer context, int index);

  int llama_memory_seq_pos_max(Pointer memory, int sequence);

  Pointer llama_batch_ext_init(Pointer context);

  void llama_batch_ext_free(Pointer batch);

  void llama_batch_ext_clear(Pointer batch);

  int llama_batch_ext_add_token(Pointer batch, int sequence, int token);

  byte llama_batch_ext_set_pos(Pointer batch, int index, int[] position);

  byte llama_batch_ext_set_output_logits(Pointer batch, int index, byte value);

  int llama_process(Pointer context, int type, Pointer batch);

  LlamaBatch llama_batch_init(int nTokens, int embd, int nSeqMax);

  void llama_batch_free(LlamaBatch.ByValue batch);

  int llama_decode(Pointer context, LlamaBatch.ByValue batch);

  Pointer llama_sampler_chain_init(LlamaSamplerChainParams.ByValue params);

  void llama_sampler_chain_add(Pointer chain, Pointer sampler);

  int llama_sampler_chain_n(Pointer chain);

  Pointer llama_sampler_chain_remove(Pointer chain, int index);

  Pointer llama_sampler_init_penalties(
      int vocabSize, int lastN, float repeat, float frequency, float presence);

  Pointer llama_sampler_init_top_k(int topK);

  Pointer llama_sampler_init_typical(float probability, long minKeep);

  Pointer llama_sampler_init_mirostat(int vocabSize, int seed, float tau, float eta, int m);

  Pointer llama_sampler_init_mirostat_v2(int seed, float tau, float eta);

  Pointer llama_sampler_init_grammar(Pointer vocab, String grammar, String root);

  void llama_sampler_apply(Pointer sampler, LlamaTokenDataArray candidates);

  void llama_sampler_accept(Pointer sampler, int token);

  Pointer llama_sampler_init_top_p(float topP, long minKeep);

  Pointer llama_sampler_init_min_p(float minP, long minKeep);

  Pointer llama_sampler_init_temp(float temperature);

  Pointer llama_sampler_init_dist(int seed);

  Pointer llama_sampler_init_greedy();

  int llama_sampler_sample(Pointer sampler, Pointer context, int index);

  int llama_sampler_get_seed(Pointer sampler);

  Pointer llama_sampler_clone(Pointer sampler);

  Pointer llama_sampler_init_temp_ext(float t, float delta, float exponent);

  Pointer llama_sampler_init_xtc(float p, float t, long minKeep, int seed);

  Pointer llama_sampler_init_top_n_sigma(float n);

  Pointer llama_sampler_init_grammar_lazy_patterns(
      Pointer vocab,
      String grammar,
      String root,
      String[] triggerPatterns,
      long numTriggerPatterns,
      int[] triggerTokens,
      long numTriggerTokens);

  Pointer llama_sampler_init_dry(
      Pointer vocab,
      float dryMultiplier,
      float dryBase,
      int dryAllowedLength,
      int dryPenaltyLastN,
      String[] seqBreakers,
      long numBreakers);

  Pointer llama_sampler_init_logit_bias(int nVocab, int nLogitBias, Pointer logitBias);

  Pointer llama_sampler_init_infill(Pointer vocab);

  void llama_sampler_reset(Pointer sampler);

  void llama_sampler_free(Pointer sampler);

  int llama_chat_apply_template(
      String template,
      LlamaChatMessage[] messages,
      long count,
      byte addAssistant,
      byte[] buffer,
      int capacity);

  LlamaModelQuantizeParams.ByValue llama_model_quantize_default_params();

  String llama_flash_attn_type_name(int flashAttnType);

  String llama_load_mode_name(int loadMode);

  int llama_load_mode_from_str(String value);

  String llama_ftype_name(int ftype);

  void llama_backend_free();

  Pointer llama_load_model_from_file(String pathModel, LlamaModelParams.ByValue params);

  Pointer llama_model_load_from_splits(
      String[] paths, long nPaths, LlamaModelParams.ByValue params);

  Pointer llama_model_load_from_file_ptr(Pointer file, LlamaModelParams.ByValue params);

  void llama_model_save_to_file(Pointer model, String pathModel);

  void llama_free_model(Pointer model);

  Pointer llama_model_init_from_user(
      Pointer metadata,
      SetTensorDataCallback setTensorData,
      Pointer setTensorDataUd,
      LlamaModelParams.ByValue params);

  Pointer llama_new_context_with_model(Pointer model, LlamaContextParams.ByValue params);

  long llama_time_us();

  long llama_max_tensor_buft_overrides();

  byte llama_supports_mmap();

  byte llama_supports_mlock();

  byte llama_supports_rpc();

  int llama_n_ctx_seq(Pointer context);

  int llama_n_ubatch(Pointer context);

  int llama_n_seq_max(Pointer context);

  int llama_n_rs_seq(Pointer context);

  int llama_n_ctx_train(Pointer model);

  int llama_n_embd(Pointer model);

  int llama_n_layer(Pointer model);

  int llama_n_head(Pointer model);

  int llama_n_vocab(Pointer vocab);

  Pointer llama_get_model(Pointer context);

  int llama_model_rope_type(Pointer model);

  int llama_model_n_embd_inp(Pointer model);

  int llama_model_n_embd_out(Pointer model);

  int llama_model_n_layer_nextn(Pointer model);

  int llama_model_n_head(Pointer model);

  int llama_model_n_head_kv(Pointer model);

  int llama_model_n_swa(Pointer model);

  int llama_model_n_cls_out(Pointer model);

  String llama_model_cls_label(Pointer model, int i);

  String llama_model_meta_key_str(int key);

  int llama_model_ftype(Pointer model);

  byte llama_model_has_encoder(Pointer model);

  byte llama_model_has_decoder(Pointer model);

  int llama_model_decoder_start_token(Pointer model);

  byte llama_model_is_diffusion(Pointer model);

  int llama_model_quantize(String fnameInp, String fnameOut, LlamaModelQuantizeParams params);

  Pointer llama_adapter_lora_init_from_file_ptr(Pointer model, Pointer file);

  int llama_adapter_meta_val_str(Pointer adapter, String key, byte[] buf, long bufSize);

  int llama_adapter_meta_count(Pointer adapter);

  int llama_adapter_meta_key_by_index(Pointer adapter, int i, byte[] buf, long bufSize);

  int llama_adapter_meta_val_str_by_index(Pointer adapter, int i, byte[] buf, long bufSize);

  long llama_adapter_get_alora_n_invocation_tokens(Pointer adapter);

  Pointer llama_adapter_get_alora_invocation_tokens(Pointer adapter);

  int llama_set_adapter_cvec(
      Pointer context, float[] data, long len, int nEmbd, int ilStart, int ilEnd);

  void llama_memory_seq_div(Pointer memory, int seqId, int p0, int p1, int d);

  int llama_memory_seq_pos_min(Pointer memory, int seqId);

  byte llama_memory_can_shift(Pointer memory);

  long llama_get_state_size(Pointer context);

  long llama_copy_state_data(Pointer context, byte[] destination);

  long llama_set_state_data(Pointer context, byte[] source);

  byte llama_state_load_file(
      Pointer context,
      String pathSession,
      int[] tokensOut,
      long nTokenCapacity,
      long[] nTokenCountOut);

  byte llama_load_session_file(
      Pointer context,
      String pathSession,
      int[] tokensOut,
      long nTokenCapacity,
      long[] nTokenCountOut);

  byte llama_state_save_file(Pointer context, String pathSession, int[] tokens, long nTokenCount);

  byte llama_save_session_file(Pointer context, String pathSession, int[] tokens, long nTokenCount);

  long llama_state_seq_get_size(Pointer context, int seqId);

  long llama_state_seq_get_data(Pointer context, byte[] destination, long size, int seqId);

  long llama_state_seq_set_data(Pointer context, byte[] source, long size, int destSeqId);

  long llama_state_seq_save_file(
      Pointer context, String filepath, int seqId, int[] tokens, long nTokenCount);

  long llama_state_seq_load_file(
      Pointer context,
      String filepath,
      int destSeqId,
      int[] tokensOut,
      long nTokenCapacity,
      long[] nTokenCountOut);

  long llama_state_seq_get_size_ext(Pointer context, int seqId, int flags);

  long llama_state_seq_get_data_ext(
      Pointer context, byte[] destination, long size, int seqId, int flags);

  long llama_state_seq_set_data_ext(
      Pointer context, byte[] source, long size, int destSeqId, int flags);

  LlamaBatch llama_batch_get_one(int[] tokens, int nTokens);

  int llama_batch_ext_add(Pointer batch, int seqId);

  int llama_batch_ext_add_embd(Pointer batch, int seqId, LlamaEmbd.ByValue embd);

  byte llama_batch_ext_add_seq(Pointer batch, int idx, int seqId);

  byte llama_batch_ext_set_embd_token(Pointer batch, int idx, LlamaEmbd.ByValue embd);

  byte llama_batch_ext_set_embd_state(Pointer batch, int idx, LlamaEmbd.ByValue embd);

  byte llama_batch_ext_set_output_embd(Pointer batch, int idx, byte value);

  int llama_n_threads(Pointer context);

  int llama_n_threads_batch(Pointer context);

  void llama_set_embeddings(Pointer context, byte embeddings);

  void llama_set_causal_attn(Pointer context, byte causalAttn);

  byte llama_get_causal_attn(Pointer context);

  void llama_set_warmup(Pointer context, byte warmup);

  void llama_set_abort_callback(
      Pointer context, AbortCallback abortCallback, Pointer abortCallbackData);

  void llama_synchronize(Pointer context);

  int llama_get_sampled_token_ith(Pointer context, int i);

  Pointer llama_get_sampled_probs_ith(Pointer context, int i);

  int llama_get_sampled_probs_count_ith(Pointer context, int i);

  Pointer llama_get_sampled_logits_ith(Pointer context, int i);

  int llama_get_sampled_logits_count_ith(Pointer context, int i);

  Pointer llama_get_sampled_candidates_ith(Pointer context, int i);

  int llama_get_sampled_candidates_count_ith(Pointer context, int i);

  byte llama_vocab_is_control(Pointer vocab, int token);

  int llama_vocab_pad(Pointer vocab);

  int llama_vocab_mask(Pointer vocab);

  byte llama_vocab_get_add_sep(Pointer vocab);

  Pointer llama_vocab_get_suppress_tokens(Pointer vocab, int[] nSuppressTokens);

  int llama_vocab_fim_pad(Pointer vocab);

  int llama_vocab_fim_rep(Pointer vocab);

  int llama_vocab_fim_sep(Pointer vocab);

  String llama_token_get_text(Pointer vocab, int token);

  float llama_token_get_score(Pointer vocab, int token);

  int llama_token_get_attr(Pointer vocab, int token);

  byte llama_token_is_eog(Pointer vocab, int token);

  byte llama_token_is_control(Pointer vocab, int token);

  int llama_token_bos(Pointer vocab);

  int llama_token_eos(Pointer vocab);

  int llama_token_eot(Pointer vocab);

  int llama_token_cls(Pointer vocab);

  int llama_token_sep(Pointer vocab);

  int llama_token_nl(Pointer vocab);

  int llama_token_pad(Pointer vocab);

  byte llama_add_bos_token(Pointer vocab);

  byte llama_add_eos_token(Pointer vocab);

  int llama_token_fim_pre(Pointer vocab);

  int llama_token_fim_suf(Pointer vocab);

  int llama_token_fim_mid(Pointer vocab);

  int llama_token_fim_pad(Pointer vocab);

  int llama_token_fim_rep(Pointer vocab);

  int llama_token_fim_sep(Pointer vocab);

  int llama_chat_builtin_templates(Pointer[] output, long len);

  byte llama_set_sampler(Pointer context, int seqId, Pointer sampler);

  Pointer llama_sampler_init(LlamaSamplerI iface, Pointer ctx);

  String llama_sampler_name(Pointer sampler);

  void llama_sampler_copy(Pointer src, Pointer dst);

  Pointer llama_sampler_chain_get(Pointer chain, int i);

  Pointer llama_sampler_init_grammar_lazy(
      Pointer vocab,
      String grammar,
      String root,
      String[] triggerWords,
      long numTriggerWords,
      int[] triggerTokens,
      long numTriggerTokens);

  Pointer llama_sampler_init_adaptive_p(float target, float decay, int seed);

  int llama_split_path(
      byte[] splitPath, long maxlen, String pathPrefix, int splitNo, int splitCount);

  int llama_split_prefix(
      byte[] splitPrefix, long maxlen, String splitPath, int splitNo, int splitCount);

  void llama_log_get(Pointer[] logCallback, Pointer[] userData);

  LlamaPerfContextData.ByValue llama_perf_context(Pointer context);

  LlamaPerfSamplerData.ByValue llama_perf_sampler(Pointer chain);

  void llama_perf_sampler_print(Pointer chain);

  void llama_perf_sampler_reset(Pointer chain);

  byte llama_opt_param_filter_all(Pointer tensor, Pointer userdata);

  void llama_opt_init(Pointer context, Pointer model, LlamaOptParams.ByValue params);

  void llama_opt_epoch(
      Pointer context,
      Pointer dataset,
      Pointer resultTrain,
      Pointer resultEval,
      long idataSplit,
      Pointer callbackTrain,
      Pointer callbackEval);
}
