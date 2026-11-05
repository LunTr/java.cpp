package dev.javacpp.compat.python;

import dev.javacpp.python.PythonStrings;
import java.util.Set;

/** Token-preserving normalization of Python expression precedence for the Java template engine. */
final class PythonJinjaDialect {
  private PythonJinjaDialect() {}

  static String normalize(String source) {
    source = source.replace("\r\n", "\n").replace('\r', '\n');
    if (source.endsWith("\n")) source = source.substring(0, source.length() - 1);
    source = forElse(source);
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
      String close;
      if (kind == '{') {
        close = "}}";
      } else if (kind == '%') {
        close = "%}";
      } else {
        close = "#}";
      }
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
      int expressionStart = 0;
      if (left) {
        expressionStart = 1;
      }
      int expressionEnd = content.length();
      if (right) {
        expressionEnd = content.length() - 1;
      }
      String expression = content.substring(expressionStart, expressionEnd);
      expression = operands(methods(PythonJinjaSlices.normalize(expression)));
      String stripped = expression.stripLeading();
      boolean isFor = stripped.startsWith("for ") || stripped.equals("for");
      result.append('{').append(kind);
      if (left) result.append(content.charAt(0));
      String padded;
      if (stripped.startsWith("macro ")) {
        padded = pad(expression);
      } else {
        padded = pad(named(expression));
      }
      // '{{ }}' gets the full Python-expression rewrite; '{% %}' tags get it only
      // for the expression that follows the tag keyword ('{% if X %}', '{% set t = X %}').
      String rewritten;
      if (kind == '{') {
        rewritten = argSafe(padded);
      } else {
        String tag = stripped.split("\\s", 2)[0];
        if (tag.equals("if") || tag.equals("elif") || tag.equals("while")) {
          int keywordEnd = padded.indexOf(tag) + tag.length();
          // jinjava judges truthiness natively and counts the strings "false"/"False"
          // as false where CPython counts every non-empty string as true, so route the
          // condition through PyObject_IsTrue.
          rewritten =
              padded.substring(0, keywordEnd)
                  + ' '
                  + "(_cpp_py_truthy("
                  + argSafe(padded.substring(keywordEnd))
                  + "))";
        } else if (tag.equals("do") || tag.equals("print")) {
          int keywordEnd = padded.indexOf(tag) + tag.length();
          rewritten =
              padded.substring(0, keywordEnd) + ' ' + argSafe(padded.substring(keywordEnd));
        } else if (tag.equals("set")) {
          int eq = assignEquals(padded);
          if (eq < 0) {
            rewritten = padded;
          } else {
            // strict() forces observation: 'set t = len()' must raise like
            // jinja2 even if the variable is never read.
            rewritten =
                padded.substring(0, eq + 1)
                    + ' '
                    + "(_cpp_py_strict("
                    + argSafe(padded.substring(eq + 1))
                    + "))";
          }
        } else if (isFor) {
          int inAt = forIn(padded);
          if (inAt < 0) {
            rewritten = comparisons(orAnd(padded));
          } else {
            rewritten = padded.substring(0, inAt + 3) + argSafe(padded.substring(inAt + 3));
          }
        } else {
          rewritten = padded;
        }
      }
      // '\u0002' marks 'x is T ARG' - an expression jinja2 cannot parse. Expand it to
      // close-delimiter + unknown tag so template parsing fails fatally even when the
      // fragment sits inside a dead branch.
      if (rewritten.indexOf('\u0002') >= 0)
        rewritten = rewritten.replace("\u0002", close + " {% __cpp_isarg__ %}");
      result.append(rewritten);
      if (right) result.append(content.charAt(content.length() - 1));
      result.append(close);
      if (kind == '%' && expression.strip().equals("raw")) {
        // jinjava's {% raw %} drops whitespace before {% endraw %}; emit the payload as a
        // literal so bytes survive exactly.
        var matcher =
            java.util.regex.Pattern.compile("\\{%[-+]?\\s*endraw\\s*[-+]?%}").matcher(source);
        if (matcher.find(end + 2)) {
          result.setLength(result.length() - (end + 2 - start));
          result.append("{{ _cpp_raw('")
              .append(
                  java.util.Base64.getEncoder()
                      .encodeToString(
                          source
                              .substring(end + 2, matcher.start())
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              .append("') }}");
          offset = matcher.end();
          continue;
        }
        result.append(source.substring(offset));
        break;
      }
      offset = end + 2;
    }
    return result.toString();
  }

  /** First top-level '=' (assignment, not ==/!=/<=/>=) in a '{% set %}' body, or -1. */
  private static int assignEquals(String text) {
    char quote = 0;
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
      else if (c == '='
          && depth == 0
          && !(i + 1 < text.length() && text.charAt(i + 1) == '=')
          && !(i > 0 && "=!<>".indexOf(text.charAt(i - 1)) >= 0)) return i;
    }
    return -1;
  }

  /** Position of the ' in ' separating target and iterable in a '{% for %}' body, or -1. */
  private static int forIn(String text) {
    char quote = 0;
    int depth = 0;
    for (int i = 0; i <= text.length() - 4; i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
      else if (depth == 0 && matchWordAt(text, i, "in")) return i;
    }
    return -1;
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
      if (c == '|') {
        // 'false|f'/'none|f'/'true|f'/'null|f' - jinjava reads '|' after a keyword
        // literal as bitwise-or; wrapping the literal in parens makes it a filter.
        int end = result.length();
        while (end > 0 && Character.isWhitespace(result.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(result.charAt(start - 1)))
          start--;
        String literal = result.substring(start, end);
        if (literal.equals("true")
            || literal.equals("false")
            || literal.equals("null")
            || literal.equals("__cpp_none")) {
          result.insert(start, '(').insert(end + 1, ')');
        }
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
        // '.name' after ')'/']'/'}' ('{..}.k', '(x).k', 'x[0].k'): jinjava resolves
        // attribute access on grouped/literal values to singleton lists, so go
        // through a dict-lookup function like Jinja2 does. Identifier bases are
        // rewritten only for the dict content names - jinjava must keep loop.*,
        // namespace attrs and method calls native.
        int prev = result.length() - 1;
        while (prev >= 0 && Character.isWhitespace(result.charAt(prev))) prev--;
        boolean methodCall = j < text.length() && text.charAt(j) == '(';
        if (prev >= 0 && !methodCall && ")]}".indexOf(result.charAt(prev)) >= 0) {
          String prefix = result.toString();
          int base = operandStart(prefix, prev + 1);
          String operand = prefix.substring(base);
          result.setLength(base);
          result.append("(_cpp_dictget(").append(operand).append(",'").append(name).append("'))");
          i = j - 1;
          continue;
        }
        if (prev >= 0
            && !methodCall
            && Character.isJavaIdentifierPart(result.charAt(prev))
            && DICT_ATTRS.contains(name)) {
          int base = prev;
          while (base > 0
              && (Character.isJavaIdentifierPart(result.charAt(base - 1))
                  || result.charAt(base - 1) == '.')) base--;
          String operand = result.substring(base, prev + 1);
          result.setLength(base);
          result.append("(_cpp_dictget(").append(operand).append(",'").append(name).append("'))");
          i = j - 1;
          continue;
        }
      }
      result.append(c);
    }
    return result.toString();
  }

  /**
   * Dict attribute names rewritten through _cpp_dictget for identifier bases
   * ({@code d.content}, {@code data.role}) - the grammar's mapping keys, kept away
   * from {@code loop.index}/{@code ns.count}/method calls.
   */
  private static final Set<String> DICT_ATTRS = Set.of("content", "role", "x", "y", "k");

  /**
   * jinjava ignores {@code else} in for-loops; rewrite
   * {@code {% for x in y %}B{% else %}E{% endfor %}} into
   * {@code {% if (y) %}{% for x in y %}B{% endfor %}{% else %}E{% endif %}} so an empty
   * iterable renders the else branch like Jinja2 does.
   */
  private static String forElse(String source) {
    StringBuilder out = new StringBuilder(source.length() + 32);
    // Frame positions are in `out`: start of the {% for %} tag, start/end of the
    // {% else %} tag. Since rewrites only ever touch the tail of `out`, positions
    // recorded earlier stay valid even for nested for/else blocks.
    // ifDepth snapshots the global if count when the for opens: an '{% else %}'
    // belongs to this for only while ifDepth is back at that level - deeper means
    // the else pairs with an {% if %} inside the loop body.
    record Frame(int tagStart, int elseTagStart, int elseTagEnd, int ifDepth) {}
    java.util.List<Frame> stack = new java.util.ArrayList<>();
    int ifDepth = 0;
    int offset = 0;
    while (offset < source.length()) {
      int start = source.indexOf('{', offset);
      if (start < 0) {
        out.append(source, offset, source.length());
        break;
      }
      char kind = ' ';
      if (start + 1 < source.length()) {
        kind = source.charAt(start + 1);
      }
      if (kind != '{' && kind != '%' && kind != '#') {
        out.append(source, offset, start + 1);
        offset = start + 1;
        continue;
      }
      String close;
      if (kind == '{') {
        close = "}}";
      } else if (kind == '%') {
        close = "%}";
      } else {
        close = "#}";
      }
      int end = end(source, start + 2, close);
      if (end < 0) {
        out.append(source, offset, source.length());
        break;
      }
      out.append(source, offset, start);
      String tagText = source.substring(start, end + 2);
      String tag =
          source
              .substring(start + 2, end)
              .replaceAll("^[-+]|[-+]$", "")
              .strip();
      String word = tag.split("\\s", 2)[0];
      if (kind != '%') {
        out.append(tagText);
        offset = end + 2;
        continue;
      }
      switch (word) {
        case "for" -> {
          stack.add(new Frame(out.length(), -1, -1, ifDepth));
          out.append(tagText);
        }
        case "endfor" -> {
          if (stack.isEmpty() || stack.get(stack.size() - 1).elseTagStart < 0) {
            if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            out.append(tagText);
            break;
          }
          Frame frame = stack.remove(stack.size() - 1);
          // out currently ends with '{% for x in I %}body{% else %}elseBody'.
          String head = out.substring(frame.tagStart, frame.elseTagStart);
          String elseBody = out.substring(frame.elseTagEnd, out.length());
          out.setLength(frame.tagStart);
          String forTag = head.substring(0, head.indexOf("%}") + 2);
          String body = head.substring(forTag.length());
          String inner = forTag.substring(forTag.indexOf("for") + 3);
          int inAt = -1;
          char quote = 0;
          for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
              if (c == '\\') i++;
              else if (c == quote) quote = 0;
              continue;
            }
            if (c == '\'' || c == '"') {
              quote = c;
              continue;
            }
            if (matchWordAt(inner, i, "in")) {
              inAt = i;
              break;
            }
          }
          if (inAt < 0) {
            out.append(head).append(elseBody).append(tagText);
            break;
          }
          String vars = inner.substring(0, inAt).strip();
          String raw = inner.substring(inAt + 2).replaceAll("[-+]?\\s*%}$", "").strip();
          // 'for x in y if c' (loop filter, no else) drops ' if c' from the iterable
          // and wraps the body in '{% if c %}'; 'for x in y if c else z' is a ternary
          // iterable and stays whole.
          String iterable = raw, cond = null;
          int[] ifW = wordAt(raw, 0, "if");
          if (ifW != null) {
            int[] elseW = wordAt(raw, ifW[0] + 2, "else");
            if (elseW == null || elseW[1] != ifW[1]) {
              iterable = raw.substring(0, ifW[0]).strip();
              cond = raw.substring(ifW[0] + 2).strip();
            }
          }
            if (cond != null) body = "{% if (" + argSafe(cond) + ") %}" + body + "{% endif %}";
          boolean lstrip = forTag.startsWith("{%-"), rstrip = forTag.endsWith("-%}");
          out.append("{% if (")
              .append(argSafe(iterable))
              .append(") %}")
              .append("{%");
          if (lstrip) {
            out.append("-");
          }
          out.append(" for ")
              .append(vars)
              .append(" in ")
              .append(argSafe(iterable));
          if (rstrip) {
            out.append(" -");
          }
          out.append(" %}")
              .append(body)
              .append("{% endfor %}")
              .append("{% else %}")
              .append(elseBody)
              .append("{% endif %}");
          }
        case "if" -> {
          ifDepth++;
          out.append(tagText);
        }
        case "endif" -> {
          ifDepth--;
          out.append(tagText);
        }
        case "else" -> {
          if (!stack.isEmpty() && ifDepth == stack.get(stack.size() - 1).ifDepth) {
            Frame frame = stack.get(stack.size() - 1);
            stack.set(
                stack.size() - 1,
                new Frame(
                    frame.tagStart,
                    out.length(),
                    out.length() + tagText.length(),
                    frame.ifDepth));
          }
          out.append(tagText);
        }
        default -> out.append(tagText);
      }
      offset = end + 2;
    }
    return out.toString();
  }

  /**
   * {@code {index, depth}} of the next word token outside quotes at or after
   * {@code from}, or null. Depth is absolute - brackets are counted from position 0
   * so a word inside parens opened before {@code from} still reports true depth.
   */
  private static int[] wordAt(String text, int from, String w) {
    char quote = 0;
    int depth = 0;
    for (int i = 0; i <= text.length() - w.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (i >= from && matchWordAt(text, i, w)) return new int[] {i, depth};
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
    }
    return null;
  }

  private static boolean matchWordAt(String text, int at, String w) {
    int end = at + w.length();
    if (end > text.length()) return false;
    if (!text.regionMatches(false, at, w, 0, w.length())) return false;
    if (at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))) return false;
    return end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
  }

  private static boolean matchWordEnd(String text, int end, String w) {
    int start = end - w.length();
    if (start < 0 || !text.regionMatches(false, start, w, 0, w.length())) return false;
    if (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) return false;
    return end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
  }

  /**
   * Rewrites applied to operands emitted inside {@code _cpp_py_*} call arguments: jinjava
   * accepts only a restricted grammar there, so ternaries, defined-tests, or/and and in
   * operators must all become ordinary function calls.
   */
  private static String argSafe(String text) {
    String s = unwrap(text);
    s = tupleLiterals(s);
    // isTests before ternary: 'x is T if c else e' hands 'if' to the test as a
    // shorthand argument in jinja2 (a syntax failure), it is not a ternary.
    s = isTests(s);
    s = ternary(s);
    s = notX(s);
    s = orAnd(s);
    s = inKw(s);
    return comparisons(s);
  }

  /**
   * Strip one fully enclosing paren pair, repeatedly - except when the inner content is
   * a tuple literal ('(1, 2)' has a top-level comma and must keep its parens).
   */
  private static String unwrap(String text) {
    String s = text.strip();
    while (s.length() > 1 && s.charAt(0) == '(' && matchingClose(s, 0) == s.length() - 1) {
      String inner = s.substring(1, s.length() - 1);
      if (!topCommas(inner).isEmpty()) break;
      s = inner.strip();
    }
    return s;
  }

  /** {@code A if C else B} -> {@code _cpp_py_tern(C, A, B)}. */
  private static String ternary(String text) {
    while (true) {
      int[] at = wordAt(text, 0, "if");
      if (at == null) return text;
      int depth = at[1];
      int[] elseAt = null;
      for (int from = at[0] + 2; ; ) {
        int[] w = wordAt(text, from, "else");
        if (w == null) return text;
        if (w[1] == depth) {
          elseAt = w;
          break;
        }
        from = w[0] + 4;
      }
      int leftStart = operandStartLow(text, at[0], IF_STOPS);
      int condEnd = elseAt[0];
      int rightEnd = operandEndLow(text, elseAt[0] + 4, TERNARY_STOPS);
      String rewritten =
          text.substring(0, leftStart)
              + "_cpp_py_tern("
              + argSafe(text.substring(at[0] + 2, condEnd))
              + ','
              + argSafe(text.substring(leftStart, at[0]))
              + ','
              + argSafe(text.substring(condEnd + 4, rightEnd))
              + ')'
              + text.substring(rightEnd);
      text = rewritten;
    }
  }

  private static final String[] IF_STOPS = {"if", "else"};
  private static final String[] TERNARY_STOPS = {"if", "else"};
  private static final String[] LOW_STOPS = {"and", "or", "if", "else"};

  /**
   * 'not X' -> '(_cpp_py_not(X))'. jinjava binds 'not' tighter than Python AND
   * silently drops a '| filter' inside the negated operand, so the whole
   * comparison-level operand must be captured inside a function call. The
   * operand runs until 'and'/'or' (or a depth-0 ')' ',' ']' '}') at top level.
   */
  private static String notX(String text) {
    StringBuilder out = new StringBuilder();
    int cursor = 0;
    while (cursor < text.length()) {
      int[] w = wordAt(text, cursor, "not");
      if (w == null) {
        out.append(text, cursor, text.length());
        return out.toString();
      }
      int at = w[0];
      // 'x is not y' keeps 'not' for isTests; 'x not in y' keeps it for inKw.
      int before = at - 1;
      while (before >= 0 && Character.isWhitespace(text.charAt(before))) before--;
      if (before + 1 < at && matchWordEnd(text, before + 1, "is")) {
        out.append(text, cursor, at + 3);
        cursor = at + 3;
        continue;
      }
      int operandFrom = at + 3;
      int afterWs = operandFrom;
      while (afterWs < text.length() && Character.isWhitespace(text.charAt(afterWs))) afterWs++;
      if (matchWordAt(text, afterWs, "in")) {
        out.append(text, cursor, operandFrom);
        cursor = operandFrom;
        continue;
      }
      int operandTo = notOperandEnd(text, operandFrom);
      out.append(text, cursor, at)
          .append("(_cpp_py_not(")
          // the operand still needs the same expression rewrites ('not not x',
          // 'not (a in b)') - recurse rather than copying the raw text.
          .append(argSafe(text.substring(operandFrom, operandTo)))
          .append("))");
      cursor = operandTo;
    }
    return out.toString();
  }

  /** End of a 'not' operand: up to 'and'/'or', an unmatched ')' ',' ']' '}' or end. */
  private static int notOperandEnd(String text, int from) {
    char quote = 0;
    int depth = 0;
    int at = from;
    while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (quote != 0) {
        if (c == '\\') at++;
        else if (c == quote) quote = 0;
        at++;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        at++;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') {
        if (depth == 0) return at;
        depth--;
      } else if (c == ',' && depth == 0) return at;
      else if (depth == 0 && (matchWordAt(text, at, "and") || matchWordAt(text, at, "or")))
        return at;
      at++;
    }
    return at;
  }

  /**
   * {@code x is defined}/{@code x is not defined}/{@code x is y}/{@code x is not y} ->
   * function calls. 'is' tests bind tighter than '=='/'!=' in Jinja2 ('x == y is not z'
   * is 'x == (y is-not z)', not a chained comparison), and must survive inside
   * function-call argument positions. After the test name jinja2 accepts a shorthand
   * argument (any name/literal/bracket) - the ported tests take none, so an argument
   * always fails - and a '|' after the name filters the test result, not the operand.
   */
  private static String isTests(String text) {
    StringBuilder out = new StringBuilder(text.length() + 16);
    int cursor = 0;
    while (true) {
      int at = -1;
      boolean negated = false;
      char quote = 0;
      for (int i = cursor; i < text.length(); i++) {
        char c = text.charAt(i);
        if (quote != 0) {
          if (c == '\\') i++;
          else if (c == quote) quote = 0;
          continue;
        }
        if (c == '\'' || c == '"') {
          quote = c;
          continue;
        }
        if (text.startsWith(" is not ", i)) {
          at = i;
          negated = true;
          break;
        }
        if (text.startsWith(" is ", i)) {
          at = i;
          break;
        }
      }
      if (at < 0) {
        out.append(text, cursor, text.length());
        return out.toString();
      }
      int nameAt;
      if (negated) {
        nameAt = at + " is not ".length();
      } else {
        nameAt = at + " is ".length();
      }
      while (nameAt < text.length() && Character.isWhitespace(text.charAt(nameAt))) nameAt++;
      int nameEnd = nameAt;
      if (nameAt < text.length() && text.charAt(nameAt) == '(') {
        // methods() wraps literals before '|': ' is (false)|f' - the name is inside.
        int probe = nameAt + 1;
        while (probe < text.length()
            && Character.isJavaIdentifierPart(text.charAt(probe))) probe++;
        if (probe < text.length() && text.charAt(probe) == ')') nameEnd = probe + 1;
      } else {
        while (nameEnd < text.length()
            && Character.isJavaIdentifierPart(text.charAt(nameEnd))) nameEnd++;
      }
      String name =
          nameEnd > nameAt
              ? text
                  .substring(nameAt, nameEnd)
                  .replaceAll("^\\(|\\)$", "")
              : null;
      boolean ported =
          name != null
              && (name.equals("defined")
                  || name.equals("true")
                  || name.equals("false")
                  || name.equals("__cpp_none"));
      int leftStart = operandStart(text, at);
      if (!ported) {
        // Unported test name or non-name token - keep the equality fallback so
        // 'x is foo' still approximates as a comparison.
        int rightEnd = operandEnd(text, nameAt);
        String comparison;
        if (negated) {
          comparison = "_cpp_py_ne";
        } else {
          comparison = "_cpp_py_eq";
        }
        out.append(text, cursor, leftStart)
            .append('(')
            .append(comparison)
            .append('(')
            .append(argSafe(text.substring(leftStart, at)))
            .append(',')
            .append(argSafe(text.substring(nameAt, rightEnd)))
            .append("))");
        cursor = rightEnd;
        continue;
      }
      int probe = nameEnd;
      while (probe < text.length() && Character.isWhitespace(text.charAt(probe))) probe++;
      if (probe < text.length() && isArgStart(text, probe)) {
        // 'x is T ARG': jinja2 hands ARG to the test; defined/true/false/none take no
        // argument so this always fails. If ARG is a complete operand followed by an
        // expression terminator the template parses and fails at render (lazy - safe
        // in dead branches); otherwise jinja2's own parse fails, which must poison the
        // whole template even inside dead branches, so emit an unparseable fragment.
        int argEnd = primaryEnd(text, probe);
        int next = argEnd;
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
        boolean parseable =
            next >= text.length() || ")]},|".indexOf(text.charAt(next)) >= 0;
        out.append(text, cursor, leftStart);
        if (parseable) {
          out.append("(raise_exception('is-arg'))");
        } else {
          // jinja2 fails to PARSE the template, so it must fail even inside dead
          // branches. Emit a sentinel now; normalize() expands it to '}} {% bad %}'
          // (a fatal unknown-tag parse error) once the whole expression is rewritten,
          // because further argSafe passes would mangle raw tag text.
          out.append('\u0002');
        }
        cursor = argEnd;
        continue;
      }
      String function = null;
      if (name.equals("defined")) {
        if (negated) {
          function = "_cpp_py_ndef";
        } else {
          function = "_cpp_py_defined";
        }
      } else if (name.equals("true")) {
        function = "_cpp_py_istrue";
      } else if (name.equals("false")) {
        function = "_cpp_py_isfalse";
      } else if (name.equals("__cpp_none")) {
        if (negated) {
          function = "_cpp_py_ne";
        } else {
          function = "_cpp_py_eq";
        }
      }
      out.append(text, cursor, leftStart).append('(');
      if (negated && function.startsWith("_cpp_py_is")) out.append("_cpp_py_not(");
      out.append(function).append('(').append(argSafe(text.substring(leftStart, at)));
      if (!name.equals("defined") && !function.startsWith("_cpp_py_is"))
        out.append(',').append(argSafe(name));
      if (negated && function.startsWith("_cpp_py_is")) out.append(')');
      out.append("))");
      cursor = nameEnd;
    }
  }

  /**
   * Whether {@code at} starts a jinja2 shorthand test argument: a name other than
   * else/or/and, a string/number literal, or an opening bracket.
   */
  private static boolean isArgStart(String text, int at) {
    char c = text.charAt(at);
    if (c == '\'' || c == '"' || c == '(' || c == '[' || c == '{' || Character.isDigit(c))
      return true;
    if (Character.isJavaIdentifierStart(c)) {
      int end = at;
      while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
      String word = text.substring(at, end);
      return !word.equals("else") && !word.equals("or") && !word.equals("and");
    }
    return false;
  }

  /** End of a primary + postfix chain (name, literal, group, then [.]/(.)/.attr runs). */
  private static int primaryEnd(String text, int at) {
    int j = at;
    char c = text.charAt(j);
    if (c == '\'' || c == '"') j = quoteEnd(text, j) + 1;
    else if (c == '(' || c == '[' || c == '{') {
      int close = matchingClose(text, j);
      j = close < 0 ? j + 1 : close + 1;
    } else {
      while (j < text.length() && Character.isJavaIdentifierPart(text.charAt(j))) j++;
    }
    while (true) {
      int k = j;
      while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
      if (k >= text.length()) return j;
      char p = text.charAt(k);
      if (p == '[' || p == '(') {
        int close = matchingClose(text, k);
        if (close < 0) return j;
        j = close + 1;
        continue;
      }
      if (p == '.' && k + 1 < text.length()
          && Character.isJavaIdentifierStart(text.charAt(k + 1))) {
        k++;
        while (k < text.length() && Character.isJavaIdentifierPart(text.charAt(k))) k++;
        j = k;
        continue;
      }
      return j;
    }
  }

  /**
   * {@code a or b} -> {@code _cpp_py_tern(a, a, b)}, {@code a and b} ->
   * {@code _cpp_py_tern(a, b, a)} - jinjava returns booleans for and/or where Python
   * returns the deciding operand.
   */
  private static String orAnd(String text) {
    for (String[] form :
        new String[][] {{"or", "_cpp_py_tern(%s,%s,%s)"}, {"and", "_cpp_py_tern(%s,%s,%s)"}}) {
      String word = form[0];
      boolean or = word.equals("or");
      String[] stops = or ? new String[] {"or", "if", "else"} : LOW_STOPS;
      StringBuilder out = new StringBuilder(text.length() + 16);
      int offset = 0, emitted = -1;
      int emittedLeft = -1, emittedRight = -1;
      while (true) {
        int[] hit = wordAt(text, offset, word);
        if (hit == null) {
          out.append(text, offset, text.length());
          break;
        }
        int at = hit[0];
        int leftStart = operandStartLow(text, at, stops);
        int rightStart = at + word.length();
        int rightEnd = operandEndLow(text, rightStart, stops);
        String right = argSafe(text.substring(rightStart, rightEnd));
        if (leftStart < offset) {
          if (emitted < 0) {
            out.append(text, offset, rightEnd);
            offset = rightEnd;
            continue;
          }
          int cut = emitted - Math.max(0, emittedLeft - leftStart);
          String left = out.substring(cut) + text.substring(Math.min(emittedRight, at), at);
          out.setLength(cut);
          String safeLeft = argSafe(left);
          out.append(or
              ? "(_cpp_py_tern(" + safeLeft + ',' + safeLeft + ',' + right + "))"
              : "(_cpp_py_tern(" + safeLeft + ',' + right + ',' + safeLeft + "))");
          offset = rightEnd;
          emitted = -1;
          continue;
        }
        String raw = text.substring(offset, leftStart);
        out.append(raw);
        if (!raw.isBlank()) emitted = -1;
        int start = out.length();
        String left = argSafe(text.substring(leftStart, at));
        out.append(or
            ? "(_cpp_py_tern(" + left + ',' + left + ',' + right + "))"
            : "(_cpp_py_tern(" + left + ',' + right + ',' + left + "))");
        offset = rightEnd;
        emitted = start;
        emittedLeft = leftStart;
        emittedRight = rightEnd;
      }
      text = out.toString();
    }
    return text;
  }

  /** {@code a in b} -> {@code _cpp_py_in(a, b)}, {@code a not in b} -> {@code _cpp_py_notin}. */
  private static String inKw(String text) {
    for (String[] form :
        new String[][] {{" not in ", "_cpp_py_notin"}, {" in ", "_cpp_py_in"}}) {
      String marker = form[0];
      String function = form[1];
      StringBuilder out = new StringBuilder(text.length() + 8);
      int offset = 0, emitted = -1;
      int emittedLeft = -1, emittedRight = -1;
      while (true) {
        int at = text.indexOf(marker, offset);
        if (at < 0) {
          out.append(text, offset, text.length());
          break;
        }
        // Skip matches inside string literals.
        if (insideQuote(text, at)) {
          out.append(text, offset, at + marker.length());
          offset = at + marker.length();
          continue;
        }
        int leftStart = operandStart(text, at);
        int rightStart = at + marker.length();
        int rightEnd = operandEnd(text, rightStart);
        String right = argSafe(text.substring(rightStart, rightEnd));
        if (leftStart < offset) {
          if (emitted < 0) {
            out.append(text, offset, rightEnd);
            offset = rightEnd;
            continue;
          }
          int cut = emitted - Math.max(0, emittedLeft - leftStart);
          String left = out.substring(cut) + text.substring(Math.min(emittedRight, at), at);
          out.setLength(cut);
          out.append('(').append(function).append('(').append(argSafe(left)).append(',').append(right).append("))");
          offset = rightEnd;
          emitted = -1;
          continue;
        }
        String raw = text.substring(offset, leftStart);
        out.append(raw);
        if (!raw.isBlank()) emitted = -1;
        int start = out.length();
        out.append('(')
            .append(function)
            .append('(')
            .append(argSafe(text.substring(leftStart, at)))
            .append(',')
            .append(right)
            .append("))");
        offset = rightEnd;
        emitted = start;
        emittedLeft = leftStart;
        emittedRight = rightEnd;
      }
      text = out.toString();
    }
    return text;
  }

  private static boolean insideQuote(String text, int at) {
    char quote = 0;
    for (int i = 0; i < at; i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
      } else if (c == '\'' || c == '"') quote = c;
    }
    return quote != 0;
  }

  /**
   * Tuple literals in operand position ({@code (1, 2)}) become {@code _cpp_tuple(1, 2)}:
   * inside function-call argument lists jinjava expands the parens into extra arguments.
   */
  private static String tupleLiterals(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); ) {
      char c = text.charAt(i);
      if (c == '\'' || c == '"') {
        int q = quoteEnd(text, i);
        out.append(text, i, q + 1);
        i = q + 1;
        continue;
      }
      if (c != '(' || !operandPosition(text, i)) {
        out.append(c);
        i++;
        continue;
      }
      int close = matchingClose(text, i);
      if (close < 0) {
        out.append(c);
        i++;
        continue;
      }
      String inner = text.substring(i + 1, close);
      java.util.List<int[]> commas = topCommas(inner);
      if (commas.isEmpty()) {
        out.append(c);
        i++;
        continue;
      }
      out.append("_cpp_tuple(");
      int from = 0;
      for (int k = 0; k <= commas.size(); k++) {
        int commaAt = k < commas.size() ? commas.get(k)[0] : inner.length();
        if (k > 0) out.append(',');
        out.append(argSafe(inner.substring(from, commaAt)));
        from = commaAt + 1;
      }
      out.append(')');
      i = close + 1;
    }
    return out.toString();
  }

  /** True when '(' sits in operand position (not a call/index/group continuation). */
  private static boolean operandPosition(String text, int at) {
    int j = at - 1;
    while (j >= 0 && Character.isWhitespace(text.charAt(j))) j--;
    if (j < 0) return true;
    char p = text.charAt(j);
    if (Character.isJavaIdentifierPart(p) || p == ')' || p == ']' || p == '}'
        || p == '\'' || p == '"') {
      if (Character.isJavaIdentifierPart(p)) {
        int s = j;
        while (s > 0 && Character.isJavaIdentifierPart(text.charAt(s - 1))) s--;
        return UNARY_AFTER.contains(text.substring(s, j + 1));
      }
      return false;
    }
    return true;
  }

  /** Top-level comma positions inside a parenthesised group. */
  private static java.util.List<int[]> topCommas(String text) {
    java.util.List<int[]> found = new java.util.ArrayList<>();
    char quote = 0;
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
      else if (c == ',' && depth == 0) found.add(new int[] {i});
    }
    return found;
  }

  /**
   * Low-precedence left-operand scan: walks back to the nearest comma, colon, opener or
   * stop word at the same nesting level.
   */
  private static int operandStartLow(String text, int end, String[] stops) {
    int j = end, depth = 0;
    while (j > 0) {
      char c = text.charAt(j - 1);
      if (c == '\'' || c == '"') {
        j = quoteStart(text, j - 1);
        continue;
      }
      if (c == ')' || c == ']' || c == '}') {
        depth++;
        j--;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') {
        if (depth == 0) return j;
        depth--;
        j--;
        continue;
      }
      if (depth == 0) {
        if (c == ',' || c == ':') return j;
        for (String w : stops) if (matchWordEnd(text, j, w)) return j;
      }
      j--;
    }
    return 0;
  }

  /**
   * Low-precedence right-operand scan: extends to the nearest stop word, comma, colon or
   * unmatched closer at the same nesting level.
   */
  private static int operandEndLow(String text, int start, String[] stops) {
    int j = start, depth = 0;
    while (j < text.length()) {
      char c = text.charAt(j);
      if (c == '\'' || c == '"') {
        j = quoteEnd(text, j) + 1;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') {
        depth++;
        j++;
        continue;
      }
      if (c == ')' || c == ']' || c == '}') {
        if (depth == 0) return j;
        depth--;
        j++;
        continue;
      }
      if (depth == 0) {
        if (c == ',' || c == ':') return j;
        for (String w : stops) if (matchWordAt(text, j, w)) return j;
      }
      j++;
    }
    return j;
  }

  /**
   * Expression-level rewrites that make jinjava evaluate Python literals: {@code none} becomes
   * the bound PyNone marker (so it prints "None" and stays falsy), {@code is none/is true/...}
   * tests become ==/!= comparisons, and boolean subscripts become their integer value.
   */
  private static String operands(String expression) {
    String text =
        outsideQuotes(
            expression,
            segment ->
                segment
                    .replaceAll("(?<![.\\w])none(?!\\w)", "__cpp_none")
                    .replaceAll("\\[\\s*(?i:false)\\s*]", "[0]")
                    .replaceAll("\\[\\s*(?i:true)\\s*]", "[1]"));
    return text;
  }

  /** Apply a transform to every non-quoted segment of an expression, quoting preserved. */
  private static String outsideQuotes(String text, java.util.function.UnaryOperator<String> map) {
    StringBuilder out = new StringBuilder(text.length());
    StringBuilder segment = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote == 0 && (c == '\'' || c == '"')) {
        out.append(map.apply(segment.toString()));
        segment.setLength(0);
        quote = c;
        out.append(c);
      } else if (quote != 0) {
        out.append(c);
        if (c == '\\' && i + 1 < text.length()) out.append(text.charAt(++i));
        else if (c == quote) quote = 0;
      } else {
        segment.append(c);
      }
    }
    return out.append(map.apply(segment.toString())).toString();
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

  /**
   * Rewrite Python binary operators into function calls. jinjava's native == coerces
   * operand types (and can throw), / on ints truncates, % truncates toward zero and //
   * truncates - while Python is type-agnostic, always-float and floored respectively.
   */
  private static String comparisons(String text) {
    StringBuilder out = new StringBuilder(text.length() + 16);
    int offset = 0;
    int emittedOperand = -1; // position in out where the last emitted call began
    int emittedLeft = -1; // source index where the emitted call's left operand began
    int emittedRight = -1; // source index where the emitted call ended
    while (true) {
      int at = operator(text, offset);
      if (at < 0) {
        out.append(text, offset, text.length());
        return out.toString();
      }
      String function = function(text, at);
      int leftStart = operandStart(text, at);
      int operandRight = at + functionEnd(text, at);
      int rightEnd = operandEnd(text, operandRight);
      if (leftStart < offset) {
        // left operand already emitted into out - a comparison/arithmetic chain:
        if (emittedOperand < 0) {
          out.append(text, offset, rightEnd);
          offset = rightEnd;
          continue;
        }
        // The operand may include parens/operators around the emitted call: the left
        // slice 'text[leftStart, emittedLeft)' was copied verbatim into out before the
        // call, and 'text[emittedRight, at)' (e.g. a closing paren) was never copied.
        // When leftStart sits inside the emitted region the operand was already
        // consumed ('(data)' in 'x == (data) != y'); cut at the call itself.
        int cut = emittedOperand - Math.max(0, emittedLeft - leftStart);
        String left = out.substring(cut) + text.substring(Math.min(emittedRight, at), at);
        out.setLength(cut);
        out.append('(')
            .append(function)
            .append(argSafe(left))
            .append(',')
            .append(argSafe(text.substring(operandRight, rightEnd)))
            .append("))");
        offset = rightEnd;
        emittedOperand = -1;
        continue;
      }
      String raw = text.substring(offset, leftStart);
      out.append(raw);
      if (!raw.isBlank()) emittedOperand = -1;
      int functionStart = out.length();
      out.append('(')
          .append(function)
          .append(argSafe(text.substring(leftStart, at)))
          .append(',')
          .append(argSafe(text.substring(operandRight, rightEnd)))
          .append("))");
      offset = rightEnd;
      emittedOperand = functionStart;
      emittedLeft = leftStart;
      emittedRight = rightEnd;
    }
  }

  /** Operator length at index: //, !=, == are 2, / and % are 1. */
  private static int functionEnd(String text, int at) {
    char c = text.charAt(at);
    if (c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '/') return 2;
    if ((c == '!' || c == '=') && at + 1 < text.length() && text.charAt(at + 1) == '=') return 2;
    return 1;
  }

  private static String function(String text, int at) {
    char c = text.charAt(at);
    if (c == '!') return "_cpp_py_ne(";
    if (c == '=') return "_cpp_py_eq(";
    if (c == '%') return "_cpp_py_mod(";
    if (c == '~') return "_cpp_py_cat(";
    if (c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '/')
      return "_cpp_py_floordiv(";
    return "_cpp_py_tdiv(";
  }

  /** Next ==, !=, //, /, % or ~ outside string literals, or -1. */
  private static int operator(String text, int from) {
    char quote = 0;
    for (int i = from; i < text.length() - 1; i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == '=' || c == '!') {
        if (text.charAt(i + 1) == '=') return i;
        continue;
      }
      if (c == '/' || c == '%' || c == '~') return i;
    }
    return -1;
  }

  /** Start index of the operand ending at {@code end} (exclusive). */
  private static int operandStart(String text, int end) {
    int j = end;
    while (j > 0 && Character.isWhitespace(text.charAt(j - 1))) j--;
    while (j > 0) {
      char c = text.charAt(j - 1);
      if (c == ')' || c == ']' || c == '}') {
        int open = matchingOpen(text, j - 1);
        if (open < 0) break;
        j = open;
        continue;
      }
      if (c == '\'' || c == '"') {
        j = quoteStart(text, j - 1);
        continue;
      }
      if (Character.isWhitespace(c)) {
        // 'x | f' - a spaced filter pipe keeps the operand going.
        int k = j - 1;
        while (k > 0 && Character.isWhitespace(text.charAt(k - 1))) k--;
        if (k > 0 && text.charAt(k - 1) == '|') {
          j = k - 2;
          while (j > 0 && Character.isWhitespace(text.charAt(j - 1))) j--;
          continue;
        }
        break;
      }
      if (c == '|') {
        j--;
        continue;
      }
      if (Character.isJavaIdentifierPart(c) || c == '.') {
        j--;
        continue;
      }
      break;
    }
    return unaryBefore(text, j);
  }

  /**
   * Extend an operand start over unary '-'/'+' prefixes ('- 2.25 // 7' must capture
   * '-2.25' so floordiv sees the negative operand, not '2.25' with the sign outside).
   * A sign is unary when preceded by another operator, an opener, a comma, a colon,
   * a keyword, or the start of the expression.
   */
  private static int unaryBefore(String text, int pos) {
    int j = pos;
    while (j > 0 && Character.isWhitespace(text.charAt(j - 1))) j--;
    while (j > 0 && (text.charAt(j - 1) == '-' || text.charAt(j - 1) == '+')) {
      int minus = j - 1;
      int k = minus;
      while (k > 0 && Character.isWhitespace(text.charAt(k - 1))) k--;
      if (k == 0) {
        j = minus;
        continue;
      }
      char p = text.charAt(k - 1);
      if ("([{,:+-*/%~=!<>&|?".indexOf(p) >= 0) {
        j = minus;
        continue;
      }
      if (Character.isJavaIdentifierPart(p)) {
        int s = k - 1;
        while (s > 0 && Character.isJavaIdentifierPart(text.charAt(s - 1))) s--;
        if (UNARY_AFTER.contains(text.substring(s, k))) {
          j = minus;
          continue;
        }
      }
      break;
    }
    return j;
  }

  private static final java.util.Set<String> UNARY_AFTER =
      java.util.Set.of("and", "or", "not", "in", "is", "if", "else", "elif", "for", "set",
          "return", "do", "with", "without");

  /** End index of the operand starting at {@code start}. */
  private static int operandEnd(String text, int start) {
    int j = start;
    while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
    // Unary sign(s), separated from the literal by padding whitespace ('- 3').
    while (j < text.length()
        && (text.charAt(j) == '-' || text.charAt(j) == '~' || text.charAt(j) == '+')) {
      j++;
      while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
    }
    while (j < text.length()) {
      char c = text.charAt(j);
      if (c == '(' || c == '[' || c == '{') {
        int close = matchingClose(text, j);
        if (close < 0) break;
        j = close + 1;
        continue;
      }
      if (c == '\'' || c == '"') {
        j = quoteEnd(text, j) + 1;
        continue;
      }
      if (c == '|') {
        j++;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
        while (j < text.length() && Character.isJavaIdentifierPart(text.charAt(j))) j++;
        continue;
      }
      if (Character.isWhitespace(c)) {
        // 'x | f' - a spaced filter pipe keeps the operand going.
        int k = j;
        while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
        if (k < text.length() && text.charAt(k) == '|') {
          j = k + 1;
          while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
          continue;
        }
        break;
      }
      if (Character.isJavaIdentifierPart(c) || c == '.') {
        j++;
        continue;
      }
      break;
    }
    return j;
  }

  private static int matchingOpen(String text, int close) {
    int depth = 0;
    char quote = 0;
    for (int i = close; i >= 0; i--) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == ')' || c == ']' || c == '}') depth++;
      else if (c == '(' || c == '[' || c == '{') {
        if (--depth == 0) return i;
      }
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
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') {
        if (--depth == 0) return i;
      }
    }
    return -1;
  }

  private static int quoteStart(String text, int endQuote) {
    char quote = text.charAt(endQuote);
    for (int i = endQuote - 1; i >= 0; i--) {
      if (text.charAt(i) == quote && (i == 0 || text.charAt(i - 1) != '\\')) return i;
    }
    return 0;
  }

  private static int quoteEnd(String text, int openQuote) {
    char quote = text.charAt(openQuote);
    for (int i = openQuote + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') i++;
      else if (c == quote) return i;
    }
    return text.length() - 1;
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
