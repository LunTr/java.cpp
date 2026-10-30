package io.github.yaulun.javacpp.compat.python;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** CPython str methods exposed to the Jinja adapter through the py_* filters. */
final class PythonStrings {
  private PythonStrings() {}

  /** Python str method names the dialect rewrites into py_* filter calls. */
  static final Set<String> NAMES =
      Set.of(
          "startswith", "endswith", "strip", "lstrip", "rstrip", "split", "rsplit",
          "splitlines", "join", "replace", "find", "rfind", "index", "rindex", "count",
          "lower", "upper", "title", "capitalize", "swapcase", "removeprefix",
          "removesuffix", "zfill", "center", "ljust", "rjust", "isdigit", "isdecimal",
          "isnumeric", "isalpha", "isalnum", "isspace", "islower", "isupper", "istitle",
          "partition", "rpartition", "expandtabs");

  static Object apply(String name, Object self, Object[] args) {
    if (!(self instanceof String text))
      throw Py.error(
          PythonErrorType.ATTRIBUTE_ERROR,
          "'" + Py.typeName(self) + "' object has no attribute '" + name + "'");
    arity(name, args);
    return switch (name) {
      case "startswith" -> affix(text, args, true);
      case "endswith" -> affix(text, args, false);
      case "strip" -> strip(text, args, true, true, "strip");
      case "lstrip" -> strip(text, args, true, false, "lstrip");
      case "rstrip" -> strip(text, args, false, true, "rstrip");
      case "split" -> split(text, args, false);
      case "rsplit" -> split(text, args, true);
      case "splitlines" -> splitlines(text, args);
      case "join" -> join(text, args);
      case "replace" -> replace(text, args);
      case "find" -> find(text, args, true, false);
      case "rfind" -> find(text, args, false, false);
      case "index" -> find(text, args, true, true);
      case "rindex" -> find(text, args, false, true);
      case "count" -> count(text, args);
      case "lower" -> text.toLowerCase(Locale.ROOT);
      case "upper" -> text.toUpperCase(Locale.ROOT);
      case "title" -> title(text);
      case "capitalize" -> capitalize(text);
      case "swapcase" -> swapcase(text);
      case "removeprefix" -> remove(text, args, true);
      case "removesuffix" -> remove(text, args, false);
      case "zfill" -> zfill(text, args);
      case "center" -> justify(text, args, 0);
      case "ljust" -> justify(text, args, 1);
      case "rjust" -> justify(text, args, 2);
      case "isdigit" -> every(text, PythonStrings::digit);
      case "isdecimal" ->
          every(text, cp -> Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER);
      case "isnumeric" -> every(text, PythonStrings::numeric);
      case "isalpha" -> every(text, Character::isLetter);
      case "isalnum" -> every(text, cp -> Character.isLetter(cp) || numeric(cp));
      case "isspace" -> every(text, PythonStrings::space);
      case "islower" -> cased(text, true);
      case "isupper" -> cased(text, false);
      case "istitle" -> isTitle(text);
      case "partition" -> partition(text, args, true);
      case "rpartition" -> partition(text, args, false);
      case "expandtabs" -> expandtabs(text, args);
      default -> throw new AssertionError(name);
    };
  }

  // ---- Arity --------------------------------------------------------------------------------

  private static void arity(String name, Object[] args) {
    switch (name) {
      case "startswith", "endswith" -> clinic(name, args, 1, 3);
      case "strip", "lstrip", "rstrip" -> manual(name, args, 0, 1);
      case "split", "rsplit" -> clinic(name, args, 0, 2);
      case "splitlines", "expandtabs" -> clinic(name, args, 0, 1);
      case "join", "zfill", "partition", "rpartition", "removeprefix", "removesuffix" ->
          exact1(name, args);
      case "replace" -> manual(name, args, 2, 3);
      case "find", "rfind", "index", "rindex", "count" -> clinic(name, args, 1, 3);
      case "center", "ljust", "rjust" -> manual(name, args, 1, 2);
      default -> noArgs(name, args);
    }
  }

  private static void clinic(String name, Object[] args, int min, int max) {
    int n = args.length;
    if (n < min)
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          name + "() takes at least " + min + " argument" + (min == 1 ? "" : "s") + " (" + n + " given)");
    if (n > max)
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          name + "() takes at most " + max + " argument" + (max == 1 ? "" : "s") + " (" + n + " given)");
  }

  private static void manual(String name, Object[] args, int min, int max) {
    int n = args.length;
    if (n < min)
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          name + " expected at least " + min + " argument" + (min == 1 ? "" : "s") + ", got " + n);
    if (n > max)
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          name + " expected at most " + max + " argument" + (max == 1 ? "" : "s") + ", got " + n);
  }

  private static void exact1(String name, Object[] args) {
    if (args.length != 1)
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          "str." + name + "() takes exactly one argument (" + args.length + " given)");
  }

  private static void noArgs(String name, Object[] args) {
    if (args.length != 0)
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          "str." + name + "() takes no arguments (" + args.length + " given)");
  }

  // ---- Character predicates -------------------------------------------------------------------

  private static boolean space(int cp) {
    return (cp >= 0x09 && cp <= 0x0D)
        || (cp >= 0x1C && cp <= 0x1F)
        || cp == 0x20
        || cp == 0x85
        || cp == 0xA0
        || (cp > 0x7F && Character.isSpaceChar(cp));
  }

  /** CPython str.isdigit: decimal numbers plus the Unicode "Digit" numeric type. */
  private static boolean digit(int cp) {
    int type = Character.getType(cp);
    if (type == Character.DECIMAL_DIGIT_NUMBER) return true;
    if (type != Character.OTHER_NUMBER) return false;
    return in(DIGIT_LETTERS, cp);
  }

  /** No-category characters whose Unicode numeric type is Digit, as (start, end) ranges. */
  private static final int[] DIGIT_LETTERS = {
    0xB2, 0xB3, 0xB9, 0xB9, 0x1369, 0x1371, 0x19DA, 0x19DA, 0x2070, 0x2070,
    0x2074, 0x2079, 0x2080, 0x2089, 0x2460, 0x2468, 0x2474, 0x247C, 0x2488, 0x2490,
    0x24EA, 0x24EA, 0x24F5, 0x24FD, 0x24FF, 0x24FF, 0x2776, 0x277E, 0x2780, 0x2788,
    0x278A, 0x2792, 0x10A40, 0x10A43, 0x10E60, 0x10E68, 0x11052, 0x1105A,
    0x1F100, 0x1F10A
  };

  /** CPython str.isnumeric: N* categories plus letters carrying a numeric value (e.g. 四). */
  private static boolean numeric(int cp) {
    int type = Character.getType(cp);
    if (type == Character.DECIMAL_DIGIT_NUMBER
        || type == Character.OTHER_NUMBER
        || type == Character.LETTER_NUMBER) return true;
    return type == Character.OTHER_LETTER && in(NUMERIC_LETTERS, cp);
  }

  /** Lo-category characters carrying a Unicode numeric value, as (start, end) ranges. */
  private static final int[] NUMERIC_LETTERS = {
    0x3405, 0x3405, 0x3483, 0x3483, 0x382A, 0x382A, 0x3B4D, 0x3B4D, 0x4E00, 0x4E00,
    0x4E03, 0x4E03, 0x4E07, 0x4E07, 0x4E09, 0x4E09, 0x4E5D, 0x4E5D, 0x4E8C, 0x4E8C,
    0x4E94, 0x4E94, 0x4E96, 0x4E96, 0x4EBF, 0x4EC0, 0x4EDF, 0x4EDF, 0x4EE8, 0x4EE8,
    0x4F0D, 0x4F0D, 0x4F70, 0x4F70, 0x5104, 0x5104, 0x5146, 0x5146, 0x5169, 0x5169,
    0x516B, 0x516B, 0x516D, 0x516D, 0x5341, 0x5341, 0x5343, 0x5345, 0x534C, 0x534C,
    0x53C1, 0x53C4, 0x56DB, 0x56DB, 0x58F1, 0x58F1, 0x58F9, 0x58F9, 0x5E7A, 0x5E7A,
    0x5EFE, 0x5EFF, 0x5F0C, 0x5F0E, 0x5F10, 0x5F10, 0x62FE, 0x62FE, 0x634C, 0x634C,
    0x67D2, 0x67D2, 0x6F06, 0x6F06, 0x7396, 0x7396, 0x767E, 0x767E, 0x8086, 0x8086,
    0x842C, 0x842C, 0x8CAE, 0x8CAE, 0x8CB3, 0x8CB3, 0x8D30, 0x8D30, 0x9621, 0x9621,
    0x9646, 0x9646, 0x964C, 0x964C, 0x9678, 0x9678, 0x96F6, 0x96F6, 0xF96B, 0xF96B,
    0xF973, 0xF973, 0xF978, 0xF978, 0xF9B2, 0xF9B2, 0xF9D1, 0xF9D1, 0xF9D3, 0xF9D3,
    0xF9FD, 0xF9FD, 0x20001, 0x20001, 0x20064, 0x20064, 0x200E2, 0x200E2,
    0x20121, 0x20121, 0x2092A, 0x2092A, 0x20983, 0x20983, 0x2098C, 0x2098C,
    0x2099C, 0x2099C, 0x20AEA, 0x20AEA, 0x20AFD, 0x20AFD, 0x20B19, 0x20B19,
    0x22390, 0x22390, 0x22998, 0x22998, 0x23B1B, 0x23B1B, 0x2626D, 0x2626D,
    0x2F890, 0x2F890
  };

  /** Membership test for a sorted (start, end) inclusive range table. */
  private static boolean in(int[] ranges, int cp) {
    for (int i = 0; i < ranges.length; i += 2) {
      if (cp < ranges[i]) return false;
      if (cp <= ranges[i + 1]) return true;
    }
    return false;
  }

  private static boolean hasCase(int cp) {
    return Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp);
  }

  private static boolean member(String chars, int cp) {
    for (int i = 0; i < chars.length(); i += Character.charCount(chars.codePointAt(i)))
      if (chars.codePointAt(i) == cp) return true;
    return false;
  }

  // ---- Argument coercion ----------------------------------------------------------------------

  private static Object arg(Object[] args, int index) {
    return index < args.length ? args[index] : null;
  }

  /** "must be str, not X" used by find/index/count/partition style first arguments. */
  private static String mustBeStr(Object value) {
    if (!(value instanceof String text))
      throw Py.error(
          PythonErrorType.TYPE_ERROR, "must be str, not " + Py.typeName(value));
    return text;
  }

  private static int[] span(String text, Object start, Object stop) {
    int size = text.codePointCount(0, text.length());
    int a = start == null ? 0 : Py.bound(sliceIndex(start), size, false);
    int b = stop == null ? size : Py.bound(sliceIndex(stop), size, false);
    return new int[] {Math.min(a, size), Math.min(b, size)};
  }

  private static BigInteger sliceIndex(Object value) {
    if (value instanceof Boolean
        || value instanceof BigInteger
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof PyIndexed) return Py.index(value);
    throw Py.error(
        PythonErrorType.TYPE_ERROR,
        "slice indices must be integers or None or have an __index__ method");
  }

  private static String slice(String text, int[] span) {
    int a = text.offsetByCodePoints(0, span[0]), b = text.offsetByCodePoints(0, span[1]);
    return text.substring(a, Math.max(a, b));
  }

  // ---- Methods ---------------------------------------------------------------------------------

  private static Boolean affix(String text, Object[] args, boolean prefix) {
    String method = prefix ? "startswith" : "endswith";
    Object marker = args[0];
    int[] span = span(text, arg(args, 1), arg(args, 2));
    String body = slice(text, span);
    if (marker instanceof PyTuple<?> options) {
      for (Object option : options) {
        if (!(option instanceof String piece))
          throw Py.error(
              PythonErrorType.TYPE_ERROR,
              "tuple for " + method + " must only contain str, not " + Py.typeName(option));
        if (prefix ? body.startsWith(piece) : body.endsWith(piece)) return true;
      }
      return false;
    }
    if (!(marker instanceof String piece))
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          method + " first arg must be str or a tuple of str, not " + Py.typeName(marker));
    return prefix ? body.startsWith(piece) : body.endsWith(piece);
  }

  private static Object strip(
      String text, Object[] args, boolean left, boolean right, String name) {
    String chars = null;
    if (args.length > 0 && args[0] != null) {
      if (!(args[0] instanceof String piece))
        throw Py.error(PythonErrorType.TYPE_ERROR, name + " arg must be None or str");
      chars = piece;
    }
    int length = text.length();
    int a = 0, b = length;
    if (chars == null) {
      if (left) while (a < b && space(text.codePointAt(a))) a += Character.charCount(text.codePointAt(a));
      if (right)
        while (b > a && space(text.codePointBefore(b))) b -= Character.charCount(text.codePointBefore(b));
    } else {
      if (left)
        while (a < b && member(chars, text.codePointAt(a)))
          a += Character.charCount(text.codePointAt(a));
      if (right)
        while (b > a && member(chars, text.codePointBefore(b)))
          b -= Character.charCount(text.codePointBefore(b));
    }
    return text.substring(a, b);
  }

  private static Object split(String text, Object[] args, boolean reverse) {
    Object marker = args.length > 0 ? args[0] : null;
    int maxsplit = args.length > 1 ? Py.index(args[1]).intValue() : -1;
    if (marker == null) return new ArrayList<Object>(whitespaceSplit(text, maxsplit, reverse));
    if (!(marker instanceof String sep))
      throw Py.error(
          PythonErrorType.TYPE_ERROR, "must be str or None, not " + Py.typeName(marker));
    if (sep.isEmpty()) throw Py.error(PythonErrorType.VALUE_ERROR, "empty separator");
    List<String> parts = new ArrayList<>();
    if (!reverse) {
      int from = 0;
      while (maxsplit < 0 || parts.size() < maxsplit) {
        int next = text.indexOf(sep, from);
        if (next < 0) break;
        parts.add(text.substring(from, next));
        from = next + sep.length();
      }
      parts.add(text.substring(from));
    } else {
      int to = text.length();
      while (maxsplit < 0 || parts.size() < maxsplit) {
        int next = text.lastIndexOf(sep, to - 1);
        if (next < 0) break;
        parts.add(0, text.substring(next + sep.length(), to));
        to = next;
      }
      parts.add(0, text.substring(0, to));
    }
    return new ArrayList<Object>(parts);
  }

  /** Whitespace split: separators are whitespace runs and edges produce no empty fields. */
  private static List<String> whitespaceSplit(String text, int maxsplit, boolean reverse) {
    int[] points = text.codePoints().toArray();
    List<String> parts = new ArrayList<>();
    if (!reverse) {
      int i = 0;
      while (i < points.length && (maxsplit < 0 || parts.size() < maxsplit)) {
        while (i < points.length && space(points[i])) i++;
        if (i == points.length) break;
        int start = i;
        while (i < points.length && !space(points[i])) i++;
        parts.add(text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, i)));
      }
      while (i < points.length && space(points[i])) i++;
      if (i < points.length) parts.add(text.substring(text.offsetByCodePoints(0, i)));
    } else {
      int i = points.length;
      while (i > 0 && (maxsplit < 0 || parts.size() < maxsplit)) {
        while (i > 0 && space(points[i - 1])) i--;
        if (i == 0) break;
        int end = i;
        while (i > 0 && !space(points[i - 1])) i--;
        parts.add(0, text.substring(text.offsetByCodePoints(0, i), text.offsetByCodePoints(0, end)));
      }
      while (i > 0 && space(points[i - 1])) i--;
      if (i > 0) parts.add(0, text.substring(0, text.offsetByCodePoints(0, i)));
    }
    return parts;
  }

  private static Object splitlines(String text, Object[] args) {
    boolean keep = args.length > 0 && Py.truth(args[0]);
    List<Object> result = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      int cp = text.codePointAt(i);
      boolean boundary =
          cp == '\n' || cp == '\r' || cp == 0x0B || cp == 0x0C
              || (cp >= 0x1C && cp <= 0x1E) || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
      if (!boundary) continue;
      int end = i + Character.charCount(cp);
      if (cp == '\r' && end < text.length() && text.charAt(end) == '\n') end++;
      result.add(text.substring(start, keep ? end : i));
      i = end - 1;
      start = end;
    }
    if (start < text.length()) result.add(text.substring(start));
    return result;
  }

  private static Object join(String text, Object[] args) {
    List<Object> items;
    try {
      items = Py.list(args[0]);
    } catch (PythonException error) {
      if (error.type() == PythonErrorType.TYPE_ERROR)
        throw Py.error(PythonErrorType.TYPE_ERROR, "can only join an iterable");
      throw error;
    }
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      Object item = items.get(i);
      if (!(item instanceof String piece))
        throw Py.error(
            PythonErrorType.TYPE_ERROR,
            "sequence item " + i + ": expected str instance, " + Py.typeName(item) + " found");
      if (i > 0) result.append(text);
      result.append(piece);
    }
    return result.toString();
  }

  private static Object replace(String text, Object[] args) {
    String old = replaceArg(args[0], 1);
    String replacement = replaceArg(args[1], 2);
    long count = args.length > 2 ? Py.index(args[2]).longValue() : -1;
    if (count == 0) return text;
    if (old.isEmpty()) {
      StringBuilder result = new StringBuilder();
      long limit = count < 0 ? Long.MAX_VALUE : count;
      int inserted = 0;
      for (int cp : text.codePoints().toArray()) {
        if (inserted < limit) {
          result.append(replacement);
          inserted++;
        }
        result.appendCodePoint(cp);
      }
      if (inserted < limit) result.append(replacement);
      return result.toString();
    }
    StringBuilder result = new StringBuilder();
    int from = 0, done = 0;
    while (count < 0 || done < count) {
      int next = text.indexOf(old, from);
      if (next < 0) break;
      result.append(text, from, next).append(replacement);
      from = next + old.length();
      done++;
    }
    return result.append(text.substring(from)).toString();
  }

  private static String replaceArg(Object value, int position) {
    if (!(value instanceof String text))
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          "replace() argument " + position + " must be str, not " + Py.typeName(value));
    return text;
  }

  private static Object find(String text, Object[] args, boolean forward, boolean raise) {
    String piece = mustBeStr(args[0]);
    int[] span = span(text, arg(args, 1), arg(args, 2));
    String body = slice(text, span);
    int at = forward ? body.indexOf(piece) : body.lastIndexOf(piece);
    if (at < 0) {
      if (raise) throw Py.error(PythonErrorType.VALUE_ERROR, "substring not found");
      return BigInteger.valueOf(-1);
    }
    return BigInteger.valueOf(span[0] + body.codePointCount(0, at));
  }

  private static Object count(String text, Object[] args) {
    String piece = mustBeStr(args[0]);
    int[] span = span(text, arg(args, 1), arg(args, 2));
    String body = slice(text, span);
    if (piece.isEmpty()) return BigInteger.valueOf(body.codePointCount(0, body.length()) + 1);
    int total = 0, from = 0;
    while ((from = body.indexOf(piece, from)) >= 0) {
      total++;
      from += piece.length();
    }
    return BigInteger.valueOf(total);
  }

  private static String title(String text) {
    StringBuilder result = new StringBuilder(text.length());
    boolean previous = false;
    for (int cp : text.codePoints().toArray()) {
      result.appendCodePoint(previous ? Character.toLowerCase(cp) : Character.toTitleCase(cp));
      previous = hasCase(cp);
    }
    return result.toString();
  }

  private static String capitalize(String text) {
    int[] points = text.codePoints().toArray();
    if (points.length == 0) return text;
    StringBuilder result = new StringBuilder().appendCodePoint(Character.toTitleCase(points[0]));
    for (int i = 1; i < points.length; i++) result.appendCodePoint(Character.toLowerCase(points[i]));
    return result.toString();
  }

  private static String swapcase(String text) {
    StringBuilder result = new StringBuilder(text.length());
    for (int cp : text.codePoints().toArray())
      result.appendCodePoint(
          Character.isUpperCase(cp)
              ? Character.toLowerCase(cp)
              : Character.isLowerCase(cp) ? Character.toUpperCase(cp) : cp);
    return result.toString();
  }

  private static Object remove(String text, Object[] args, boolean prefix) {
    String name = prefix ? "removeprefix" : "removesuffix";
    Object value = args[0];
    if (!(value instanceof String piece))
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          name + "() argument must be str, not " + (value == null ? "None" : Py.typeName(value)));
    if (prefix ? text.startsWith(piece) : text.endsWith(piece))
      return prefix ? text.substring(piece.length()) : text.substring(0, text.length() - piece.length());
    return text;
  }

  private static Object zfill(String text, Object[] args) {
    int width = Py.index(args[0]).intValue();
    int size = text.codePointCount(0, text.length());
    if (width <= size) return text;
    String pad = "0".repeat(width - size);
    if (!text.isEmpty() && "+-".indexOf(text.charAt(0)) >= 0)
      return text.charAt(0) + pad + text.substring(1);
    return pad + text;
  }

  private static Object justify(String text, Object[] args, int mode) {
    int width = Py.index(args[0]).intValue();
    String fill = " ";
    if (args.length > 1) {
      if (!(args[1] instanceof String piece))
        throw Py.error(
            PythonErrorType.TYPE_ERROR,
            "The fill character must be a unicode character, not " + Py.typeName(args[1]));
      if (piece.codePointCount(0, piece.length()) != 1)
        throw Py.error(
            PythonErrorType.TYPE_ERROR, "The fill character must be exactly one character long");
      fill = piece;
    }
    int size = text.codePointCount(0, text.length());
    if (width <= size) return text;
    int margin = width - size;
    int left = mode == 0 ? (margin + 1) / 2 : mode == 2 ? margin : 0;
    int right = margin - left;
    return fill.repeat(left) + text + fill.repeat(right);
  }

  private static Boolean every(String text, java.util.function.IntPredicate test) {
    int[] points = text.codePoints().toArray();
    if (points.length == 0) return false;
    for (int cp : points) if (!test.test(cp)) return false;
    return true;
  }

  private static Boolean cased(String text, boolean lower) {
    boolean found = false;
    for (int cp : text.codePoints().toArray()) {
      if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
        if (lower) return false;
        found = true;
      } else if (Character.isLowerCase(cp)) {
        if (!lower) return false;
        found = true;
      }
    }
    return found;
  }

  private static Boolean isTitle(String text) {
    boolean previous = false, found = false;
    for (int cp : text.codePoints().toArray()) {
      if (hasCase(cp)) {
        if (Character.isLowerCase(cp) ? !previous : previous) return false;
        found = true;
        previous = true;
      } else previous = false;
    }
    return found;
  }

  private static Object partition(String text, Object[] args, boolean forward) {
    String piece = mustBeStr(args[0]);
    if (piece.isEmpty())
      throw Py.error(PythonErrorType.VALUE_ERROR, "empty separator");
    int at = forward ? text.indexOf(piece) : text.lastIndexOf(piece);
    List<Object> result = new ArrayList<>(3);
    if (at < 0) {
      if (forward) {
        result.add(text);
        result.add("");
        result.add("");
      } else {
        result.add("");
        result.add("");
        result.add(text);
      }
    } else {
      result.add(text.substring(0, at));
      result.add(piece);
      result.add(text.substring(at + piece.length()));
    }
    return PyTuple.wrap(result);
  }

  private static Object expandtabs(String text, Object[] args) {
    int tab = args.length > 0 ? Py.index(args[0]).intValue() : 8;
    StringBuilder result = new StringBuilder();
    int column = 0;
    for (int cp : text.codePoints().toArray()) {
      if (cp == '\t') {
        if (tab > 0) {
          int spaces = tab - column % tab;
          result.append(" ".repeat(spaces));
          column += spaces;
        }
      } else {
        result.appendCodePoint(cp);
        column = cp == '\n' || cp == '\r' ? 0 : column + 1;
      }
    }
    return result.toString();
  }
}
