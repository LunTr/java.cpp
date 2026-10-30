package io.github.yaulun.javacpp.compat.python;

/** Token-preserving normalization of Python expression precedence for the Java template engine. */
final class PythonJinjaDialect {
  private PythonJinjaDialect() {}

  static String normalize(String source) {
    source = source.replace("\r\n", "\n").replace('\r', '\n');
    if (source.endsWith("\n")) source = source.substring(0, source.length() - 1);
    StringBuilder result = new StringBuilder();
    int offset = 0;
    while (offset < source.length()) {
      int start = source.indexOf('{', offset);
      if (start < 0 || start + 1 == source.length()) {
        result.append(source.substring(offset));
        break;
      }
      result.append(source, offset, start);
      char kind = source.charAt(start + 1);
      if (kind != '{' && kind != '%' && kind != '#') {
        result.append('{');
        offset = start + 1;
        continue;
      }
      String close = kind == '{' ? "}}" : kind == '%' ? "%}" : "#}";
      int end = end(source, start + 2, close);
      if (end < 0) {
        result.append(source.substring(start));
        break;
      }
      String content = source.substring(start + 2, end);
      if (kind == '#') {
        result.append(source, start, end + 2);
        offset = end + 2;
        continue;
      }
      boolean left = content.startsWith("-") || content.startsWith("+"),
          right = content.endsWith("-") || content.endsWith("+");
      String expression = content.substring(left ? 1 : 0, content.length() - (right ? 1 : 0));
      expression = methods(PythonJinjaSlices.normalize(expression));
      result.append('{').append(kind);
      if (left) result.append(content.charAt(0));
      result.append(
          pad(expression.stripLeading().startsWith("macro ") ? expression : named(expression)));
      if (right) result.append(content.charAt(content.length() - 1));
      result.append(close);
      offset = end + 2;
      if (kind == '%' && expression.strip().equals("raw")) {
        var matcher =
            java.util.regex.Pattern.compile("\\{%[-+]?\\s*endraw\\s*[-+]?%}").matcher(source);
        if (matcher.find(offset)) {
          result.append(source, offset, matcher.end());
          offset = matcher.end();
        } else {
          result.append(source.substring(offset));
          break;
        }
      }
    }
    return result.toString();
  }

  private static int end(String source, int start, String close) {
    char quote = 0;
    for (int i = start; i < source.length() - 1; i++) {
      char c = source.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
      } else if (c == '\'' || c == '"') quote = c;
      else if (source.startsWith(close, i)) return i;
    }
    return -1;
  }

  /** Rewrites Python str method calls {@code x.name(args)} into {@code x|py_name(args)} filters. */
  private static String methods(String text) {
    StringBuilder result = new StringBuilder(text.length());
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
      if (c == '.'
          && i + 1 < text.length()
          && (Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) == '_')) {
        int j = i + 2;
        while (j < text.length()
            && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
        String name = text.substring(i + 1, j);
        if (j < text.length() && text.charAt(j) == '(' && PythonStrings.NAMES.contains(name)) {
          result.append("|py_").append(name);
          i = j - 1;
          continue;
        }
      }
      result.append(c);
    }
    return result.toString();
  }

  private static String named(String text) {
    StringBuilder result = new StringBuilder();
    char quote = 0;
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      result.append(c);
      if (quote != 0) {
        if (c == '\\' && i + 1 < text.length()) result.append(text.charAt(++i));
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == '(') depth++;
      if (c == ')') depth--;
      if (c != '='
          || depth <= 0
          || (i > 0 && "=!<>".indexOf(text.charAt(i - 1)) >= 0)
          || (i + 1 < text.length() && text.charAt(i + 1) == '=')) continue;
      int j = i + 1, nesting = 0;
      char innerQuote = 0;
      for (; j < text.length(); j++) {
        char value = text.charAt(j);
        if (innerQuote != 0) {
          if (value == '\\') j++;
          else if (value == innerQuote) innerQuote = 0;
          continue;
        }
        if (value == '\'' || value == '"') {
          innerQuote = value;
          continue;
        }
        if (nesting == 0 && (value == ',' || value == ')')) break;
        if (value == '(' || value == '[' || value == '{') nesting++;
        else if (value == ')' || value == ']' || value == '}') nesting--;
      }
      result.append('(').append(named(text.substring(i + 1, j))).append(')');
      i = j - 1;
    }
    return result.toString();
  }

  private static String pad(String expression) {
    StringBuilder result = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < expression.length(); i++) {
      char c = expression.charAt(i);
      if (quote != 0) {
        result.append(c);
        if (c == '\\' && i + 1 < expression.length()) result.append(expression.charAt(++i));
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        result.append(c);
        continue;
      }
      if ("+-*/%<>!=|~".indexOf(c) >= 0) {
        if ((c == '-' || c == '+')
            && i > 1
            && "eE".indexOf(expression.charAt(i - 1)) >= 0
            && Character.isDigit(expression.charAt(i - 2))) {
          result.append(c);
          continue;
        }
        result.append(' ').append(c);
        if (i + 1 < expression.length()
            && ("=!<>".indexOf(c) >= 0 && expression.charAt(i + 1) == '='
                || c == '*' && expression.charAt(i + 1) == '*'
                || c == '/' && expression.charAt(i + 1) == '/'))
          result.append(expression.charAt(++i));
        result.append(' ');
      } else result.append(c);
    }
    return result.toString();
  }
}
