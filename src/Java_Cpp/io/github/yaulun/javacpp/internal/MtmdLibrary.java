package io.github.yaulun.javacpp.internal;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

/** Pinned mtmd.h C entry points; snake_case identifiers deliberately match the audited header. */
public interface MtmdLibrary extends Library {
  /** ggml_backend_sched_eval_callback: bool f(tensor*, bool ask, void* user_data). */
  interface SchedEvalCallback extends Callback {
    byte invoke(Pointer tensor, byte ask, Pointer userData);
  }

  /** mtmd_progress_callback: bool f(float progress, void* user_data). */
  interface ProgressCallback extends Callback {
    byte invoke(float progress, Pointer userData);
  }

  /** mtmd_bitmap_lazy_callback. */
  interface BitmapLazyCallback extends Callback {
    int invoke(long arg0, Pointer userData, Pointer bitmapOut, Pointer idOut);
  }

  String mtmd_default_marker();

  MtmdContextParams.ByValue mtmd_context_params_default();

  Pointer mtmd_init_from_file(
      String mmprojFname, Pointer textModel, MtmdContextParams.ByValue ctxParams);

  void mtmd_free(Pointer ctx);

  byte mtmd_decode_use_non_causal(Pointer ctx, Pointer chunk);

  byte mtmd_decode_use_mrope(Pointer ctx);

  byte mtmd_support_vision(Pointer ctx);

  byte mtmd_support_audio(Pointer ctx);

  int mtmd_get_audio_sample_rate(Pointer ctx);

  String mtmd_get_marker(Pointer ctx);

  Pointer mtmd_bitmap_init(int nx, int ny, Pointer data);

  Pointer mtmd_bitmap_init_from_audio(long nSamples, Pointer data);

  void mtmd_bitmap_free(Pointer bitmap);

  int mtmd_bitmap_get_nx(Pointer bitmap);

  int mtmd_bitmap_get_ny(Pointer bitmap);

  Pointer mtmd_bitmap_get_data(Pointer bitmap);

  long mtmd_bitmap_get_n_bytes(Pointer bitmap);

  byte mtmd_bitmap_is_audio(Pointer bitmap);

  String mtmd_bitmap_get_id(Pointer bitmap);

  void mtmd_bitmap_set_id(Pointer bitmap, String id);

  void mtmd_bitmap_set_mergeable(Pointer bitmap, byte mergeable);

  Pointer mtmd_bitmap_init_lazy(
      Pointer ctx, String id, Pointer userData, BitmapLazyCallback callback);

  Pointer mtmd_input_chunks_init();

  void mtmd_input_chunks_free(Pointer chunks);

  long mtmd_input_chunks_size(Pointer chunks);

  Pointer mtmd_input_chunks_get(Pointer chunks, long idx);

  int mtmd_tokenize(
      Pointer ctx, Pointer output, MtmdInputText text, Pointer bitmaps, long nBitmaps);

  long mtmd_input_chunk_get_n_tokens(Pointer chunk);

  int mtmd_input_chunk_get_type(Pointer chunk);

  int mtmd_tokenize_from_parts(
      Pointer ctx, Pointer output, Pointer parts, long nParts, byte addSpecial);

  Pointer mtmd_input_chunk_get_tokens_text(Pointer chunk, LongByReference nTokensOutput);

  Pointer mtmd_input_chunk_get_tokens_image(Pointer chunk);

  String mtmd_input_chunk_get_id(Pointer chunk);

  int mtmd_input_chunk_get_n_pos(Pointer chunk);

  Pointer mtmd_input_chunk_copy(Pointer chunk);

  void mtmd_input_chunk_free(Pointer chunk);

  Pointer mtmd_input_chunk_get_placeholder(Pointer chunk);

  int mtmd_input_chunk_save(
      Pointer chunk, Pointer outBuf, long outLen, LongByReference expectedOutLen);

  Pointer mtmd_input_chunk_load(Pointer buf, long len);

  long mtmd_image_tokens_get_n_tokens(Pointer imageTokens);

  long mtmd_image_tokens_get_nx(Pointer imageTokens);

  long mtmd_image_tokens_get_ny(Pointer imageTokens);

  String mtmd_image_tokens_get_id(Pointer imageTokens);

  int mtmd_image_tokens_get_n_pos(Pointer imageTokens);

  MtmdTypes.DecoderPos.ByValue mtmd_image_tokens_get_decoder_pos(
      Pointer imageTokens, int pos0, long i);

  int mtmd_encode(Pointer ctx, Pointer imageTokens);

  int mtmd_encode_chunk(Pointer ctx, Pointer chunk);

  Pointer mtmd_get_output_embd(Pointer ctx);

  Pointer mtmd_batch_init(Pointer ctx);

  void mtmd_batch_free(Pointer batch);

  int mtmd_batch_add_chunk(Pointer batch, Pointer chunk);

  int mtmd_batch_encode(Pointer batch);

  Pointer mtmd_batch_get_output_embd(Pointer batch, Pointer chunk);

  MtmdTypes.Caps.ByValue mtmd_get_cap_from_file(String mmprojFname);

  MtmdTypes.GenAudioInfo.ByValue mtmd_gen_audio_get_info(Pointer ctx);

  MtmdTypes.GenInp.ByValue mtmd_gen_inp_default(Pointer ctx);

  int mtmd_gen_audio_process(Pointer ctx, MtmdTypes.GenInp inp, MtmdTypes.GenOut out);

  Pointer mtmd_test_create_input_chunks();

  byte mtmd_helper_support_video(Pointer ctx);

  MtmdTypes.InitOpt.ByValue mtmd_helper_init_opt_default();

  MtmdTypes.BitmapWrapper.ByValue mtmd_helper_bitmap_init_from_file_wrapper(
      Pointer ctx, String fname, byte placeholder, MtmdTypes.InitOpt.ByValue opt);

  MtmdTypes.BitmapWrapper.ByValue mtmd_helper_bitmap_init_from_buf_wrapper(
      Pointer ctx, Pointer buf, long length, byte placeholder, MtmdTypes.InitOpt.ByValue opt);

  /** mtmd_helper_post_decode_callback. */
  interface PostDecodeCallback extends Callback {
    int invoke(MtmdTypes.HelperEmbdBatch batch, Pointer userData);
  }

  long mtmd_helper_get_n_tokens(Pointer chunks);

  int mtmd_helper_get_n_pos(Pointer chunks);

  void mtmd_helper_image_get_decoder_pos(Pointer image, int pos0, MtmdTypes.DecoderPos outPos);

  int mtmd_helper_eval_chunks(
      Pointer ctx,
      Pointer lctx,
      Pointer chunks,
      int nPast,
      int seqId,
      int nBatch,
      byte logitsLast,
      IntByReference newNPast);

  int mtmd_helper_eval_chunk_single(
      Pointer ctx,
      Pointer lctx,
      Pointer chunk,
      int nPast,
      int seqId,
      int nBatch,
      byte logitsLast,
      IntByReference newNPast);

  int mtmd_helper_decode_image_chunk(
      Pointer ctx,
      Pointer lctx,
      Pointer chunk,
      Pointer encodedEmbd,
      int nPast,
      int seqId,
      int nBatch,
      IntByReference newNPast,
      PostDecodeCallback callback,
      Pointer userData);

  MtmdTypes.VideoInitParams.ByValue mtmd_helper_video_init_params_default();

  Pointer mtmd_helper_video_init(
      Pointer ctx, String path, MtmdTypes.VideoInitParams.ByValue params);

  Pointer mtmd_helper_video_init_from_buf(
      Pointer ctx, Pointer buf, long length, MtmdTypes.VideoInitParams.ByValue params);

  void mtmd_helper_video_free(Pointer ctx);

  MtmdTypes.VideoInfo.ByValue mtmd_helper_video_get_info(Pointer ctx);

  int mtmd_helper_video_read_next(
      Pointer ctx, PointerByReference outBitmap, PointerByReference outText);

  byte mtmd_helper_model_can_chat(Pointer lctx, Pointer mctx);

  Pointer mtmd_helper_gen_audio_init(Pointer lctx, Pointer mctx);

  void mtmd_helper_gen_audio_free(Pointer ctx);

  void mtmd_helper_gen_audio_reset(Pointer ctx);

  int mtmd_helper_gen_audio_set_input(Pointer ctx, MtmdTypes.GenAudioInp inp);

  int mtmd_helper_gen_audio_step_prompt(Pointer ctx, int nBatch);

  int mtmd_helper_gen_audio_step_gen(
      Pointer ctx,
      int sampled,
      Pointer hStateIn,
      PointerByReference hStateOut,
      ByteByReference outStop);

  int mtmd_helper_gen_audio_get_output(
      Pointer ctx,
      IntByReference outSampleRate,
      PointerByReference outData,
      LongByReference outDataLen,
      LongByReference outNSamples);

  void mtmd_log_set(LlamaLibrary.LogCallback logCallback, Pointer userData);

  void mtmd_helper_log_set(LlamaLibrary.LogCallback logCallback, Pointer userData);
}
