package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Pinned mtmd.h small structures returned or passed by value. */
public final class MtmdTypes {
  private MtmdTypes() {}

  /** mtmd_decoder_pos: M-RoPE decoder attention position. */
  @Structure.FieldOrder({"t", "x", "y", "z"})
  public static class DecoderPos extends Structure {
    public int t;
    public int x;
    public int y;
    public int z;

    public static class ByValue extends DecoderPos implements Structure.ByValue {}
  }

  /** mtmd_caps: capabilities exposed by an mmproj file. */
  @Structure.FieldOrder({"inp_vision", "inp_audio"})
  public static class Caps extends Structure {
    public byte inp_vision;
    public byte inp_audio;

    public static class ByValue extends Caps implements Structure.ByValue {}
  }

  /** mtmd_gen_audio_info. */
  @Structure.FieldOrder({"type", "sample_rate", "model_variant"})
  public static class GenAudioInfo extends Structure {
    public int type;
    public int sample_rate;
    public String model_variant;

    public static class ByValue extends GenAudioInfo implements Structure.ByValue {}
  }

  /** mtmd_gen_inp: audio generation step input. */
  @Structure.FieldOrder({
    "type",
    "code0",
    "embd",
    "top_k",
    "top_p",
    "seed",
    "temp",
    "codes",
    "n_codes",
    "feats",
    "n_feats",
    "state_data",
    "state_size"
  })
  public static class GenInp extends Structure {
    public int type;
    public int code0;
    public Pointer embd;
    public int top_k;
    public float top_p;
    public int seed;
    public float temp;
    public Pointer codes;
    public long n_codes;
    public Pointer feats;
    public long n_feats;
    public Pointer state_data;
    public long state_size;

    public static class ByValue extends GenInp implements Structure.ByValue {}
  }

  /** mtmd_gen_out: audio generation step output. */
  @Structure.FieldOrder({
    "codes",
    "n_codes",
    "feats",
    "n_feats",
    "embd",
    "is_eos",
    "audio",
    "n_samples",
    "state_data",
    "state_size"
  })
  public static class GenOut extends Structure {
    public Pointer codes;
    public long n_codes;
    public Pointer feats;
    public long n_feats;
    public Pointer embd;
    public byte is_eos;
    public Pointer audio;
    public long n_samples;
    public Pointer state_data;
    public long state_size;
  }

  /** mtmd_helper_bitmap_wrapper: bitmap plus optional video stream context. */
  @Structure.FieldOrder({"bitmap", "video_ctx"})
  public static class BitmapWrapper extends Structure {
    public Pointer bitmap;
    public Pointer video_ctx;

    public static class ByValue extends BitmapWrapper implements Structure.ByValue {}
  }

  /** mtmd_helper_video_init_params. */
  @Structure.FieldOrder({"fps_target", "ffmpeg_bin_dir", "timestamp_interval_ms"})
  public static class VideoInitParams extends Structure {
    public float fps_target;
    public String ffmpeg_bin_dir;
    public long timestamp_interval_ms;

    public static class ByValue extends VideoInitParams implements Structure.ByValue {}
  }

  /** mtmd_helper_video_info: metadata for a decoded video stream. */
  @Structure.FieldOrder({"width", "height", "fps", "n_frames"})
  public static class VideoInfo extends Structure {
    public int width;
    public int height;
    public float fps;
    public int n_frames;

    public static class ByValue extends VideoInfo implements Structure.ByValue {}
  }

  /** mtmd_helper_embd_batch: one decoded sub-batch of embeddings. */
  @Structure.FieldOrder({"n_tokens", "embd", "n_embd", "pos", "n_pos", "seq_id"})
  public static class HelperEmbdBatch extends Structure {
    public int n_tokens;
    public Pointer embd;
    public int n_embd;
    public Pointer pos;
    public int n_pos;
    public int seq_id;
  }

  /** mtmd_helper_gen_audio_inp. */
  @Structure.FieldOrder({
    "seq_id",
    "prompt",
    "prompt_len",
    "speaker_ref",
    "lang",
    "top_k",
    "top_p",
    "seed",
    "out_type"
  })
  public static class GenAudioInp extends Structure {
    public int seq_id;
    public String prompt;
    public long prompt_len;
    public Pointer speaker_ref;
    public String lang;
    public int top_k;
    public float top_p;
    public int seed;
    public int out_type;
  }

  /** mtmd_helper_init_opt for the media loader helpers. */
  @Structure.FieldOrder({"video_params"})
  public static class InitOpt extends Structure {
    public VideoInitParams video_params;

    public static class ByValue extends InitOpt implements Structure.ByValue {}
  }
}
