package io.github.yaulun.javacpp.compat.python;

import java.util.Objects;

/** Python mtmd_cpp.py compatibility wrappers with real function bodies. */
public final class PythonMtmdCpp {
  /** Bitmap plus optional video stream context, mirroring mtmd_helper_bitmap_wrapper. */
  public record BitmapWrapperResult(Object bitmap, Object videoCtx) {}

  /** Injectable mtmd boundary so contract tests replay without the native library. */
  public interface MtmdAccess {
    int audioSampleRate(Object ctx);

    Object initOptDefault();

    BitmapWrapperResult bitmapInitFromFileWrapper(
        Object ctx, Object fname, boolean placeholder, Object opt);

    BitmapWrapperResult bitmapInitFromBufWrapper(
        Object ctx, Object buf, long length, boolean placeholder, Object opt);
  }

  private final MtmdAccess mtmd;
  private final PythonWarnings warnings;

  public PythonMtmdCpp(MtmdAccess mtmd, PythonWarnings warnings) {
    this.mtmd = Objects.requireNonNull(mtmd);
    this.warnings = Objects.requireNonNull(warnings);
  }

  /** Deprecated compatibility wrapper renamed upstream to mtmd_get_audio_sample_rate. */
  public int getAudioBitrate(Object ctx, PythonWarningLocation location) {
    warnings.deprecated(
        "mtmd_get_audio_bitrate", "use mtmd_get_audio_sample_rate instead", location);
    return mtmd.audioSampleRate(ctx);
  }

  /** Default opt then wrapper call; returns wrapper.bitmap like the original. */
  public Object bitmapInitFromFile(Object ctx, Object fname, boolean placeholder, Object opt) {
    if (opt == null) {
      opt = mtmd.initOptDefault();
    }
    return mtmd.bitmapInitFromFileWrapper(ctx, fname, placeholder, opt).bitmap();
  }

  /** Default opt then wrapper call; returns wrapper.bitmap like the original. */
  public Object bitmapInitFromBuf(
      Object ctx, Object buf, long length, boolean placeholder, Object opt) {
    if (opt == null) {
      opt = mtmd.initOptDefault();
    }
    return mtmd.bitmapInitFromBufWrapper(ctx, buf, length, placeholder, opt).bitmap();
  }
}
