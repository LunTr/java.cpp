package io.github.yaulun.javacpp.compat.python;

import java.util.*;

/** Translate sequence slice expressions without changing literal or template text. */
final class PythonJinjaSlices {
  private PythonJinjaSlices() {}

  static String normalize(String text) {
    StringBuilder result = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        result.append(c);
        if (c == '\\' && i + 1 < text.length()) result.append(text.charAt(++i));
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        result.append(c);
        continue;
      }
      if (c != '[') {
        result.append(c);
        continue;
      }
      int end = i + 1, nesting = 0;
      char innerQuote = 0;
      List<Integer> colons = new ArrayList<>();
      for (; end < text.length(); end++) {
        char v = text.charAt(end);
        if (innerQuote != 0) {
          if (v == '\\') end++;
          else if (v == innerQuote) innerQuote = 0;
          continue;
        }
        if (v == '\'' || v == '"') {
          innerQuote = v;
          continue;
        }
        if (nesting == 0 && v == ']') break;
        if (nesting == 0 && v == ':') colons.add(end);
        if (v == '[' || v == '(' || v == '{') nesting++;
        else if (v == ']' || v == ')' || v == '}') nesting--;
      }
      if (end == text.length()
          || colons.isEmpty()
          || colons.size() > 2
          || result.toString().isBlank()) {
        result.append(c);
        continue;
      }
      int base = baseStart(result.toString(), result.length() - 1);
      if (base < 0) {
        result.append(c);
        continue;
      }
      String object = result.substring(base);
      result.setLength(base);
      List<String> parts = new ArrayList<>();
      int from = i + 1;
      for (int colon : colons) {
        parts.add(text.substring(from, colon));
        from = colon + 1;
      }
      parts.add(text.substring(from, end));
      while (parts.size() < 3) parts.add("");
      result.append("_cpp_python_slice(").append(object);
      for (String part : parts)
        result.append(',').append(part.isBlank() ? "none" : normalize(part));
      result.append(')');
      i = end;
    }
    return result.toString();
  }

  private static int baseStart(String text, int last) {
    while (last >= 0 && Character.isWhitespace(text.charAt(last))) last--;
    if (last < 0) return -1;
    char c = text.charAt(last);
    if (c == ')' || c == ']' || c == '\'' || c == '"') {
      Map<Integer, Integer> pairs = new HashMap<>();
      ArrayDeque<Integer> stack = new ArrayDeque<>();
      char quote = 0;
      int quoted = 0;
      for (int i = 0; i <= last; i++) {
        char v = text.charAt(i);
        if (quote != 0) {
          if (v == '\\') i++;
          else if (v == quote) {
            pairs.put(i, quoted);
            quote = 0;
          }
          continue;
        }
        if (v == '\'' || v == '"') {
          quote = v;
          quoted = i;
        } else if (v == '(' || v == '[') stack.push(i);
        else if ((v == ')' || v == ']') && !stack.isEmpty()) pairs.put(i, stack.pop());
      }
      Integer opening = pairs.get(last);
      if (opening == null) return -1;
      if (c == '\'' || c == '"') return opening;
      int previous = opening - 1;
      while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) previous--;
      if (previous >= 0
          && (Character.isJavaIdentifierPart(text.charAt(previous))
              || ")]'\"".indexOf(text.charAt(previous)) >= 0)) return baseStart(text, previous);
      return opening;
    }
    int start = last;
    while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) start--;
    int begin = start + 1;
    if (begin > last) return -1;
    if (start >= 0 && text.charAt(start) == '.') return baseStart(text, start - 1);
    return begin;
  }
}
