package io.github.yaulun.javacpp.internal;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/** llava.h/clip.h C entry points; snake_case identifiers deliberately match the audited header. */
public interface LlavaLibrary extends Library {
  /** LLAVA_API bool: clip <-> llava embed size sanity check. */
  byte llava_validate_embed_size(Pointer ctxLlama, Pointer ctxClip);

  /** LLAVA_API llava_image_embed* built from image bytes. */
  Pointer llava_image_embed_make_with_bytes(
      Pointer ctxClip, int nThreads, byte[] imageBytes, int imageBytesLength);

  /** LLAVA_API llava_image_embed* built from an image filename. */
  Pointer llava_image_embed_make_with_filename(Pointer ctxClip, int nThreads, String imagePath);

  /** LLAVA_API void: frees an embed made by llava_image_embed_make_*. */
  void llava_image_embed_free(Pointer embed);

  /** LLAVA_API bool: writes the image embed into the llama context, advancing n_past. */
  byte llava_eval_image_embed(Pointer ctxLlama, Pointer embed, int nBatch, IntByReference nPast);

  /** CLIP_API clip_ctx*: loads an mmproj model. */
  Pointer clip_model_load(String fname, int verbosity);

  /** CLIP_API void: frees an mmproj context. */
  void clip_free(Pointer ctx);
}
