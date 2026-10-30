package io.github.yaulun.javacpp;

import com.sun.jna.Pointer;
import io.github.yaulun.javacpp.compat.python.PythonInternalsBatch;
import io.github.yaulun.javacpp.compat.python.PythonInternalsPort;
import io.github.yaulun.javacpp.internal.LlamaBatch;
import io.github.yaulun.javacpp.internal.LlamaContextParams;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import io.github.yaulun.javacpp.internal.LlamaModelParams;
import io.github.yaulun.javacpp.internal.LlamaSamplerChainParams;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** b11669 implementation of the _internals port; handles stay as JNA pointers. */
public final class PythonNativeInternals implements PythonInternalsPort {
  private final LlamaLibrary library;

  public PythonNativeInternals(LlamaLibrary library) {
    this.library = library;
  }

  public static PythonNativeInternals create() {
    return new PythonNativeInternals(LlamaRuntime.get().library());
  }

  private static Pointer handle(Object value) {
    return (Pointer) value;
  }

  @Override
  public boolean exists(String path) {
    try {
      return Files.exists(Path.of(path));
    } catch (InvalidPathException | SecurityException failure) {
      return false;
    }
  }

  /** Native stdout/stderr suppression is not part of the Java binding scope. */
  @Override
  public void suppressStdoutStderr(boolean disable) {}

  @Override
  public Object modelDefaultParams() {
    return library.llama_model_default_params();
  }

  @Override
  public Object modelLoadFromFile(String path, Object params) {
    return library.llama_model_load_from_file(path, (LlamaModelParams.ByValue) params);
  }

  @Override
  public Object modelGetVocab(Object model) {
    return library.llama_model_get_vocab(handle(model));
  }

  @Override
  public void modelFree(Object model) {
    library.llama_model_free(handle(model));
  }

  @Override
  public int modelNCtxTrain(Object model) {
    return library.llama_model_n_ctx_train(handle(model));
  }

  @Override
  public void modelDesc(Object model, byte[] buffer) {
    library.llama_model_desc(handle(model), buffer, buffer.length);
  }

  @Override
  public int modelMetaCount(Object model) {
    return library.llama_model_meta_count(handle(model));
  }

  @Override
  public int modelMetaKeyByIndex(Object model, int index, byte[] buffer) {
    return library.llama_model_meta_key_by_index(handle(model), index, buffer, buffer.length);
  }

  @Override
  public int modelMetaValStrByIndex(Object model, int index, byte[] buffer) {
    return library.llama_model_meta_val_str_by_index(handle(model), index, buffer, buffer.length);
  }

  @Override
  public int vocabBos(Object vocab) {
    return library.llama_vocab_bos(handle(vocab));
  }

  @Override
  public int tokenize(
      Object vocab, byte[] text, int[] tokens, int capacity, boolean addBos, boolean special) {
    return library.llama_tokenize(
        handle(vocab),
        text,
        text.length,
        tokens,
        capacity,
        (byte) (addBos ? 1 : 0),
        (byte) (special ? 1 : 0));
  }

  @Override
  public int tokenToPiece(Object vocab, int token, byte[] buffer, int lstrip, boolean special) {
    return library.llama_token_to_piece(
        handle(vocab), token, buffer, buffer.length, lstrip, (byte) (special ? 1 : 0));
  }

  @Override
  public Object contextDefaultParams() {
    return library.llama_context_default_params();
  }

  @Override
  public Object initFromModel(Object model, Object params) {
    return library.llama_init_from_model(handle(model), (LlamaContextParams.ByValue) params);
  }

  @Override
  public Object getMemory(Object context) {
    return library.llama_get_memory(handle(context));
  }

  @Override
  public void memoryClear(Object memory, boolean data) {
    library.llama_memory_clear(handle(memory), (byte) (data ? 1 : 0));
  }

  @Override
  public void contextFree(Object context) {
    library.llama_free(handle(context));
  }

  @Override
  public int decode(Object context, PythonInternalsBatch batch) {
    return library.llama_decode(handle(context), ((NativeBatch) batch).value());
  }

  @Override
  public PythonInternalsBatch batchInit(int nTokens, int embd, int nSeqMax) {
    LlamaBatch.ByValue value = new LlamaBatch.ByValue();
    LlamaBatch created = library.llama_batch_init(nTokens, embd, nSeqMax);
    created.read();
    value.n_tokens = created.n_tokens;
    value.token = created.token;
    value.embd = created.embd;
    value.pos = created.pos;
    value.n_seq_id = created.n_seq_id;
    value.seq_id = created.seq_id;
    value.logits = created.logits;
    return new NativeBatch(value, nTokens);
  }

  @Override
  public void batchFree(PythonInternalsBatch batch) {
    library.llama_batch_free(((NativeBatch) batch).value());
  }

  @Override
  public Object samplerChainDefaultParams() {
    return library.llama_sampler_chain_default_params();
  }

  @Override
  public Object samplerChainInit(Object params) {
    return library.llama_sampler_chain_init((LlamaSamplerChainParams.ByValue) params);
  }

  @Override
  public void samplerChainAdd(Object chain, Object sampler) {
    library.llama_sampler_chain_add(handle(chain), handle(sampler));
  }

  @Override
  public Object samplerChainRemove(Object chain, int index) {
    return library.llama_sampler_chain_remove(handle(chain), index);
  }

  @Override
  public Object samplerInitGreedy() {
    return library.llama_sampler_init_greedy();
  }

  @Override
  public Object samplerInitDist(long seed) {
    return library.llama_sampler_init_dist((int) seed);
  }

  @Override
  public Object samplerInitTopK(int k) {
    return library.llama_sampler_init_top_k(k);
  }

  @Override
  public Object samplerInitTopP(double p, long minKeep) {
    return library.llama_sampler_init_top_p((float) p, minKeep);
  }

  @Override
  public Object samplerInitMinP(double p, long minKeep) {
    return library.llama_sampler_init_min_p((float) p, minKeep);
  }

  @Override
  public Object samplerInitTemp(double temperature) {
    return library.llama_sampler_init_temp((float) temperature);
  }

  @Override
  public Object samplerInitPenalties(
      int nVocab, int penaltyLastN, double repeat, double freq, double present) {
    return library.llama_sampler_init_penalties(
        nVocab, penaltyLastN, (float) repeat, (float) freq, (float) present);
  }

  @Override
  public long samplerGetSeed(Object sampler) {
    return Integer.toUnsignedLong(library.llama_sampler_get_seed(handle(sampler)));
  }

  @Override
  public int samplerSample(Object sampler, Object context, int index) {
    return library.llama_sampler_sample(handle(sampler), handle(context), index);
  }

  @Override
  public void samplerFree(Object sampler) {
    library.llama_sampler_free(handle(sampler));
  }

  private static final class NativeBatch implements PythonInternalsBatch {
    private final LlamaBatch.ByValue value;
    private final int capacity;

    private NativeBatch(LlamaBatch.ByValue value, int capacity) {
      this.value = value;
      this.capacity = capacity;
    }

    private LlamaBatch.ByValue value() {
      return value;
    }

    private int index(int index) {
      return index < 0 ? capacity + index : index;
    }

    @Override
    public int capacity() {
      return capacity;
    }

    @Override
    public int nTokens() {
      return value.n_tokens;
    }

    @Override
    public void nTokens(int count) {
      value.n_tokens = count;
    }

    @Override
    public void token(int index, int token) {
      value.token.setInt(4L * index(index), token);
    }

    @Override
    public void pos(int index, int position) {
      value.pos.setInt(4L * index(index), position);
    }

    @Override
    public void seqId(int index, int slot, int sequence) {
      value.seq_id.getPointer(8L * index(index)).setInt(4L * slot, sequence);
    }

    @Override
    public void nSeqId(int index, int count) {
      value.n_seq_id.setInt(4L * index(index), count);
    }

    @Override
    public void logits(int index, boolean output) {
      value.logits.setByte(index(index), (byte) (output ? 1 : 0));
    }
  }
}
