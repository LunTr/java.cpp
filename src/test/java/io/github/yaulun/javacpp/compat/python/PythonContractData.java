package io.github.yaulun.javacpp.compat.python;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed cross-language fixtures; strings preserve all UTF-16 code units. */
final class PythonContractData {
  private PythonContractData() {}

  static Object read(DataInputStream input) throws IOException {
    int tag = input.readUnsignedByte();
    return switch (tag) {
      case 'n' -> null;
      case 'f' -> false;
      case 't' -> true;
      case 'i' -> {
        long number = input.readLong();
        yield number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
            ? (Object) (int) number
            : number;
      }
      case 'd' -> input.readDouble();
      case 's' -> {
        char[] chars = new char[input.readInt()];
        for (int i = 0; i < chars.length; i++) chars[i] = input.readChar();
        yield new String(chars);
      }
      case 'l' -> {
        int count = input.readInt();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) values.add(read(input));
        yield values;
      }
      case 'm' -> {
        int count = input.readInt();
        Map<Object, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) values.put(read(input), read(input));
        yield values;
      }
      default -> throw new IOException("Unknown fixture tag " + tag);
    };
  }

  static void write(DataOutputStream output, Object value) throws IOException {
    if (value == null) output.writeByte('n');
    else if (value instanceof Boolean flag) output.writeByte(flag ? 't' : 'f');
    else if (value instanceof String text) {
      output.writeByte('s');
      output.writeInt(text.length());
      output.writeChars(text);
    } else if (value instanceof Double || value instanceof Float) {
      output.writeByte('d');
      output.writeDouble(((Number) value).doubleValue());
    } else if (value instanceof Number number) {
      output.writeByte('i');
      output.writeLong(number.longValue());
    } else if (value instanceof List<?> values) {
      output.writeByte('l');
      output.writeInt(values.size());
      for (Object item : values) write(output, item);
    } else if (value instanceof Map<?, ?> values) {
      output.writeByte('m');
      output.writeInt(values.size());
      for (var entry : values.entrySet()) {
        write(output, entry.getKey());
        write(output, entry.getValue());
      }
    } else throw new IOException("Unsupported fixture value " + value);
  }
}
