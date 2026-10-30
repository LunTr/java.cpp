package io.github.yaulun.javacpp.python;

public final class PythonBytes {
  private static final String HEX = "0123456789abcdef";

  private PythonBytes() {}

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
