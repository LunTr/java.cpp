package io.github.yaulun.demo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class PeVersionReader {
  private PeVersionReader() {}

  static Optional<String> fileVersion(Path file) throws IOException {
    byte[] data = Files.readAllBytes(file);
    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    for (int offset = 0; offset <= data.length - 16; offset++) {
      if (buffer.getInt(offset) == 0xFEEF04BD && buffer.getInt(offset + 4) == 0x00010000) {
        int major = buffer.getInt(offset + 8);
        int minor = buffer.getInt(offset + 12);
        return Optional.of(
            (major >>> 16) + "." + (major & 65535) + "." + (minor >>> 16) + "." + (minor & 65535));
      }
    }
    return Optional.empty();
  }

  static int compare(String first, String second) {
    String[] left = first.split("\\.");
    String[] right = second.split("\\.");
    for (int index = 0; index < Math.max(left.length, right.length); index++) {
      int result =
          Integer.compare(
              index < left.length ? Integer.parseInt(left[index]) : 0,
              index < right.length ? Integer.parseInt(right[index]) : 0);
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }
}
