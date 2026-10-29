package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import com.hubspot.jinjava.*;
import com.hubspot.jinjava.interpret.*;
import com.hubspot.jinjava.lib.filter.Filter;
import com.hubspot.jinjava.lib.fn.ELFunctionDefinition;
import com.hubspot.jinjava.lib.tag.Tag;
import com.hubspot.jinjava.tree.TagNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Java template adapter for the original Jinja2ChatFormatter environment. */
public final class PythonJinjaTemplate {
  private final String source;
  private final Jinjava engine;
  private final String eos, bos;

  public PythonJinjaTemplate(String source, String eos, String bos) {
    this.source = PythonJinjaDialect.normalize(source);
    this.eos = eos;
    this.bos = bos;
    engine =
        new Jinjava(
            JinjavaConfig.newBuilder()
                .withTrimBlocks(true)
                .withLstripBlocks(true)
                .withKeepTrailingNewline(false)
                .withNestedInterpretationEnabled(false)
                .withMaxRenderDepth(Integer.MAX_VALUE)
                .withMaxMacroRecursionDepth(Integer.MAX_VALUE)
                .withMaxOutputSize(Long.MAX_VALUE)
                .withMaxStringLength(Long.MAX_VALUE)
                .withMaxListSize(Integer.MAX_VALUE)
                .withMaxMapSize(Integer.MAX_VALUE)
                .withRangeLimit(Integer.MAX_VALUE)
                .withInterperterFactory(
                    new InterpreterFactory() {
                      public JinjavaInterpreter newInstance(JinjavaInterpreter parent) {
                        return new PythonInterpreter(parent);
                      }

                      public JinjavaInterpreter newInstance(
                          Jinjava app, Context context, JinjavaConfig config) {
                        return new PythonInterpreter(app, context, config);
                      }
                    })
                .withLegacyOverrides(
                    LegacyOverrides.newBuilder()
                        .withUsePyishObjectMapper(true)
                        .withUseNaturalOperatorPrecedence(true)
                        .withHandleBackslashInQuotesOnly(true)
                        .build())
                .build());
    engine.getGlobalContext().setAutoEscape(false);
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_python_slice",
            PythonJinjaTemplate.class,
            "slice",
            Object.class,
            Object.class,
            Object.class,
            Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "namespace", PythonJinjaTemplate.class, "namespace", Object[].class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "raise_exception", PythonJinjaTemplate.class, "raiseException", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "strftime_now", PythonJinjaTemplate.class, "strftimeNow", String.class));
    engine.registerFilter(
        new Filter() {
          public String getName() {
            return "tojson";
          }

          public Object filter(Object value, JinjavaInterpreter interpreter, String... args) {
            return new PythonJson(false, null, null, false).write(value);
          }

          public Object filter(
              Object value,
              JinjavaInterpreter interpreter,
              Object[] args,
              Map<String, Object> kwargs) {
            return new PythonJson(
                    truth(kwargs.getOrDefault("ensure_ascii", false)),
                    kwargs.get("indent"),
                    kwargs.get("separators"),
                    truth(kwargs.getOrDefault("sort_keys", false)))
                .write(value);
          }
        });
    engine.registerTag(
        new Tag() {
          public String getName() {
            return "generation";
          }

          public String getEndTagName() {
            return "endgeneration";
          }

          public String interpret(TagNode node, JinjavaInterpreter interpreter) {
            StringBuilder text = new StringBuilder();
            for (var child : node.getChildren()) text.append(child.render(interpreter).getValue());
            return text.toString();
          }
        });
    JinjavaInterpreter parser = engine.newInterpreter();
    parser.parse(this.source);
    check(parser.getErrors());
  }

  public String render(Map<String, Object> bindings) {
    Map<String, Object> values =
        dict(
            "messages",
            List.of(),
            "eos_token",
            eos,
            "bos_token",
            bos,
            "add_generation_prompt",
            true,
            "functions",
            null,
            "function_call",
            null,
            "tools",
            null,
            "tool_choice",
            null);
    values.putAll(bindings);
    RenderResult result = engine.renderForResult(source, values);
    check(result.getErrors());
    return result.getOutput();
  }

  public static String raiseException(Object value) {
    throw error(PythonErrorType.VALUE_ERROR, string(value));
  }

  public static Object slice(Object value, Object start, Object stop, Object step) {
    return PythonSlices.slice(value, start, stop, step);
  }

  public static com.hubspot.jinjava.objects.Namespace namespace(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Object value : values) {
      if (value instanceof Map<?, ?> map)
        for (var entry : map.entrySet()) result.put(entry.getKey().toString(), entry.getValue());
      else if (value instanceof com.hubspot.jinjava.el.ext.NamedParameter named)
        result.put(named.getName(), named.getValue());
    }
    return new com.hubspot.jinjava.objects.Namespace(result);
  }

  private static final class PythonInterpreter extends JinjavaInterpreter {
    PythonInterpreter(JinjavaInterpreter parent) {
      super(parent);
    }

    PythonInterpreter(Jinjava app, Context context, JinjavaConfig config) {
      super(app, context, config);
    }

    @Override
    public String getAsString(Object value) {
      return value instanceof String text ? text : super.getAsString(value);
    }

    @Override
    public Object resolveELExpression(String expression, int line) {
      String text = expression.strip();
      var dotted =
          java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\.[A-Za-z_][A-Za-z_0-9]*")
              .matcher(text);
      if (dotted.matches() && !getContext().containsKey(dotted.group(1)))
        throw error(PythonErrorType.UNDEFINED_ERROR, "'" + dotted.group(1) + "' is undefined");
      var indexed =
          java.util.regex.Pattern.compile(
                  "([A-Za-z_][A-Za-z_0-9]*)\\[(-?[0-9]+)]\\.[A-Za-z_][A-Za-z_0-9]*")
              .matcher(text);
      if (indexed.matches()
          && getContext().get(indexed.group(1)) instanceof java.util.List<?> values) {
        int index = Integer.parseInt(indexed.group(2));
        if (index >= values.size() || index < -values.size())
          throw error(PythonErrorType.UNDEFINED_ERROR, "list object has no element " + index);
      }
      return super.resolveELExpression(expression, line);
    }
  }

  public static String strftimeNow(String format) {
    String pattern =
        format
            .replace("%Y", "uuuu")
            .replace("%m", "MM")
            .replace("%d", "dd")
            .replace("%H", "HH")
            .replace("%M", "mm")
            .replace("%S", "ss");
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
  }

  private static void check(List<TemplateError> errors) {
    for (TemplateError problem : errors) {
      if (Boolean.getBoolean("javacpp.jinja.debug"))
        System.err.println(
            "JINJA: "
                + problem.getReason()
                + " / "
                + problem.getFieldName()
                + " / "
                + problem.getMessage());
      for (Throwable cause = problem.getException(); cause != null; cause = cause.getCause())
        if (cause instanceof PythonException original) throw original;
      if (problem.getSeverity() == TemplateError.ErrorType.FATAL)
        throw error(PythonErrorType.TEMPLATE_SYNTAX_ERROR, syntaxMessage(problem.getMessage()));
    }
  }

  private static String syntaxMessage(String message) {
    if (message.contains("Unknown tag: "))
      return "Encountered unknown tag '"
          + message.substring(message.indexOf("Unknown tag: ") + 13)
          + "'.";
    if (message.contains("Missing end tag: endif"))
      return "Unexpected end of template. Jinja was looking for the following tags: 'elif' or 'else' or 'endif'. The innermost block that needs to be closed is 'if'.";
    return message;
  }
}
