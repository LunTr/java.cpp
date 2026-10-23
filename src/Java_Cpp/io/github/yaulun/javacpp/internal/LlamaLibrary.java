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

  String llama_version();

  String llama_print_system_info();

  void llama_log_set(LogCallback callback, Pointer data);

  void llama_backend_init();

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

  byte llama_vocab_is_eog(Pointer vocab, int token);

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

  void llama_memory_clear(Pointer memory, byte data);

  int llama_memory_seq_pos_max(Pointer memory, int sequence);

  Pointer llama_batch_ext_init(Pointer context);

  void llama_batch_ext_free(Pointer batch);

  void llama_batch_ext_clear(Pointer batch);

  int llama_batch_ext_add_token(Pointer batch, int sequence, int token);

  byte llama_batch_ext_set_pos(Pointer batch, int index, int[] position);

  byte llama_batch_ext_set_output_logits(Pointer batch, int index, byte value);

  int llama_process(Pointer context, int type, Pointer batch);

  Pointer llama_sampler_chain_init(LlamaSamplerChainParams.ByValue params);

  void llama_sampler_chain_add(Pointer chain, Pointer sampler);

  Pointer llama_sampler_init_penalties(
      int vocabSize, int lastN, float repeat, float frequency, float presence);

  Pointer llama_sampler_init_top_k(int topK);

  Pointer llama_sampler_init_top_p(float topP, long minKeep);

  Pointer llama_sampler_init_min_p(float minP, long minKeep);

  Pointer llama_sampler_init_temp(float temperature);

  Pointer llama_sampler_init_dist(int seed);

  Pointer llama_sampler_init_greedy();

  int llama_sampler_sample(Pointer sampler, Pointer context, int index);

  void llama_sampler_reset(Pointer sampler);

  void llama_sampler_free(Pointer sampler);

  int llama_chat_apply_template(
      String template,
      LlamaChatMessage[] messages,
      long count,
      byte addAssistant,
      byte[] buffer,
      int capacity);
}
