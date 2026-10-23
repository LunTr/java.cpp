package io.github.yaulun.javacpp;

import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Parsed little-endian GGUF header; validating this header does not validate all tensors. */
public record GgufHeader(int version, long tensorCount, long kvCount, long fileSize) {
  /** Reads exactly the 24-byte header; failures map to E201 through E204. */
  public static GgufHeader read(Path file) {
    Objects.requireNonNull(file, "file");
    if (!Files.isRegularFile(file)) {
      throw failure(ErrorCode.E201, file.toString(), null);
    }
    try (InputStream input = Files.newInputStream(file)) {
      return parse(input.readNBytes(24), Files.size(file));
    } catch (IOException | SecurityException e) {
      throw failure(ErrorCode.E202, file.toString(), e);
    }
  }

  /** Parses v2/v3 with nonnegative counts; rejects incomplete headers and invalid magic. */
  public static GgufHeader parse(byte[] first24, long fileSize) {
    Objects.requireNonNull(first24, "first24");
    String detail =
        first24.length > 0 && first24[0] == '<' ? "可能下载到了网页" : "Invalid GGUF magic/header";
    if (first24.length < 24 || fileSize < 24) {
      throw failure(ErrorCode.E203, detail, null);
    }
    ByteBuffer buffer = ByteBuffer.wrap(first24).order(ByteOrder.LITTLE_ENDIAN);
    if (buffer.getInt() != 0x46554747) {
      throw failure(ErrorCode.E203, detail, null);
    }
    int version = buffer.getInt();
    if (version != 2 && version != 3) {
      throw failure(ErrorCode.E204, "version=" + version, null);
    }
    long tensors = buffer.getLong();
    long keys = buffer.getLong();
    if (tensors < 0 || keys < 0) {
      throw failure(ErrorCode.E203, "Counts exceed signed 64-bit range", null);
    }
    return new GgufHeader(version, tensors, keys, fileSize);
  }

  private static ModelLoadException failure(ErrorCode code, String detail, Throwable cause) {
    return new ModelLoadException(code, detail, List.of(), cause);
  }
}
