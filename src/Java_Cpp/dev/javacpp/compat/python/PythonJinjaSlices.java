package dev.javacpp.compat.python;

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
      if (end == text.length() || colons.size() > 2 || result.toString().isBlank()) {
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
      if (colons.isEmpty()) {
        result
            .append("_cpp_python_index(")
            .append(object)
            .append(',')
            .append(normalize(text.substring(i + 1, end)))
            .append(')');
        i = end;
        continue;
      }
      List<String> parts = new ArrayList<>();
      int from = i + 1;
      for (int colon : colons) {
        parts.add(text.substring(from, colon));
        from = colon + 1;
      }
      parts.add(text.substring(from, end));
      while (parts.size() < 3) parts.add("");
      result
          .append(isLiteralBase(object) ? "_cpp_python_slice_soft(" : "_cpp_python_slice(")
          .append(object);
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
      if (previous >= 0 && Character.isJavaIdentifierPart(text.charAt(previous))) {
        int wordStart = previous;
        while (wordStart >= 0 && Character.isJavaIdentifierPart(text.charAt(wordStart)))
          wordStart--;
        String word = text.substring(wordStart + 1, previous + 1);
        // A keyword before '(' is not a call target: '{% if (x)[i] %}', 'for y in (x)[i]'
        // subscript the group itself. A plain name is a call: 'f(x)[i]' subscripts the
        // call result, so keep walking back.
        if (KEYWORDS.contains(word)) return opening;
        return baseStart(text, previous);
      }
      if (previous >= 0 && ")]'\"".indexOf(text.charAt(previous)) >= 0)
        return baseStart(text, previous);
      return opening;
    }
    int start = last;
    while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) start--;
    int begin = start + 1;
    if (begin > last) return -1;
    String word = text.substring(begin, last + 1);
    // Keywords are never subscriptable bases: 'for x in []', 'not []', 'a if [..]'.
    if (KEYWORDS.contains(word) || word.startsWith("end")) return -1;
    if (start >= 0 && text.charAt(start) == '.') return baseStart(text, start - 1);
    return begin;
  }

  /**
   * Whether the slice base is a compile-time literal. jinja2's @optimizeconst folds
   * {@code literal[a:b]} through environment.getitem (TypeError/LookupError degrade to
   * undefined), while a name/call/filter base takes the runtime {@code obj[slice]} path
   * where TypeErrors propagate.
   */
  private static boolean isLiteralBase(String base) {
    String s = base.strip();
    while (s.length() > 1 && s.charAt(0) == '(' && matchingClose(s, 0) == s.length() - 1) {
      String inner = s.substring(1, s.length() - 1);
      if (!topLevelCommas(inner).isEmpty()) return true; // tuple literal
      s = inner.strip();
    }
    if (s.isEmpty()) return false;
    char c = s.charAt(0);
    // The base must be a single literal token - "'ab' | py_x(..)" is a filtered
    // call, which takes the strict runtime path like any other expression.
    if (c == '\'' || c == '"') return quoteEnd(s, 0) == s.length() - 1;
    if (c == '[' || c == '{') return matchingClose(s, 0) == s.length() - 1;
    if (Character.isDigit(c)) return s.matches("[0-9.eE_+-]+");
    return s.equals("true") || s.equals("false") || s.equals("none") || s.equals("__cpp_none");
  }

  private static int quoteEnd(String text, int at) {
    char quote = text.charAt(at);
    for (int i = at + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') i++;
      else if (c == quote) return i;
    }
    return -1;
  }

  private static int matchingClose(String text, int open) {
    int depth = 0;
    char quote = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') quote = c;
      else if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  private static List<Integer> topLevelCommas(String text) {
    List<Integer> commas = new ArrayList<>();
    int depth = 0;
    char quote = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') quote = c;
      else if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
      else if (c == ',' && depth == 0) commas.add(i);
    }
    return commas;
  }

  private static final Set<String> KEYWORDS =
      Set.of(
          "and", "or", "not", "in", "is", "if", "elif", "else", "for", "while", "set", "do",
          "with", "without", "import", "from", "as", "macro", "call", "block", "filter", "raw",
          "break", "continue", "recursive", "scoped", "required", "include", "extends", "trans",
          "pluralize", "autoescape", "ignore", "debug", "deprecated");
}
