package dev.javacpp.python;

public final class PythonBytes {
  private static final String HEX = "0123456789abcdef";

  private PythonBytes() {}

  /**
   * bytes.decode("utf-8") with strict error handling: malformed input raises UnicodeDecodeError
   * with CPython's byte position and reason wording.
   */
  public static String decodeUtf8(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length);
    int index = 0;
    while (index < bytes.length) {
      int first = Byte.toUnsignedInt(bytes[index]);
      if (first < 0x80) {
        result.append((char) first);
        index++;
        continue;
      }
      int length;
      int minimumSecond;
      int maximumSecond;
      if (first >= 0xc2 && first <= 0xdf) {
        length = 2;
        minimumSecond = 0x80;
        maximumSecond = 0xbf;
      } else if (first == 0xe0) {
        length = 3;
        minimumSecond = 0xa0;
        maximumSecond = 0xbf;
      } else if (first == 0xed) {
        length = 3;
        minimumSecond = 0x80;
        maximumSecond = 0x9f;
      } else if (first >= 0xe1 && first <= 0xef) {
        length = 3;
        minimumSecond = 0x80;
        maximumSecond = 0xbf;
      } else if (first == 0xf0) {
        length = 4;
        minimumSecond = 0x90;
        maximumSecond = 0xbf;
      } else if (first == 0xf4) {
        length = 4;
        minimumSecond = 0x80;
        maximumSecond = 0x8f;
      } else if (first >= 0xf1 && first <= 0xf3) {
        length = 4;
        minimumSecond = 0x80;
        maximumSecond = 0xbf;
      } else {
        throw undecodable(bytes, index, "invalid start byte");
      }
      int codePoint = first;
      for (int offset = 1; offset < length; offset++) {
        if (index + offset >= bytes.length) {
          throw undecodable(bytes, index, "unexpected end of data");
        }
        int next = Byte.toUnsignedInt(bytes[index + offset]);
        int low = offset == 1 ? minimumSecond : 0x80;
        int high = offset == 1 ? maximumSecond : 0xbf;
        if (next < low || next > high) {
          throw undecodable(bytes, index + offset, "invalid continuation byte");
        }
        codePoint = (codePoint << 6) | (next & 0x3f);
      }
      if (length == 2) {
        codePoint &= 0x7ff;
      } else if (length == 3) {
        codePoint &= 0xffff;
      } else {
        codePoint &= 0x1fffff;
      }
      result.appendCodePoint(codePoint);
      index += length;
    }
    return result.toString();
  }

  private static PythonException undecodable(byte[] bytes, int position, String reason) {
    return Py.error(
        PythonErrorType.UNICODE_DECODE_ERROR,
        "'utf-8' codec can't decode byte 0x"
            + String.format("%02x", Byte.toUnsignedInt(bytes[position]))
            + " in position "
            + position
            + ": "
            + reason);
  }

  public static String repr(byte[] bytes) {
    boolean single = false;
    boolean doubleQuote = false;
    for (byte value : bytes) {
      single |= value == '\'';
      doubleQuote |= value == '"';
    }
    char quote = single && !doubleQuote ? '"' : '\'';
    StringBuilder result = new StringBuilder("b").append(quote);
    for (byte value : bytes) {
      int unsigned = Byte.toUnsignedInt(value);
      if (unsigned == quote || unsigned == '\\') {
        result.append('\\').append((char) unsigned);
      } else if (unsigned == '\t') {
        result.append("\\t");
      } else if (unsigned == '\n') {
        result.append("\\n");
      } else if (unsigned == '\r') {
        result.append("\\r");
      } else if (unsigned >= 32 && unsigned < 127) {
        result.append((char) unsigned);
      } else {
        result.append("\\x").append(HEX.charAt(unsigned >>> 4)).append(HEX.charAt(unsigned & 15));
      }
    }
    return result.append(quote).toString();
  }
}
