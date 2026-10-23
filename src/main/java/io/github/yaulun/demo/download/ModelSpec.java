package io.github.yaulun.demo.download;

import java.util.Objects;

/** Fixed download identity: filename, exact byte size and lowercase SHA-256. */
public record ModelSpec(String quant, String fileName, long sizeBytes, String sha256) {
  /** Rejects path traversal, unknown size and malformed digests before file operations. */
  public ModelSpec {
    Objects.requireNonNull(quant);
    Objects.requireNonNull(fileName);
    Objects.requireNonNull(sha256);
    if (!fileName.matches("[A-Za-z0-9._-]+")
        || fileName.equals(".")
        || fileName.equals("..")
        || sizeBytes < 24
        || !sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid model download identity");
    }
  }
}
