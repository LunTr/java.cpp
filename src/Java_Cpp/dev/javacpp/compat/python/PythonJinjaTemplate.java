package dev.javacpp.compat.python;

import static dev.javacpp.python.PythonValues.*;

import com.hubspot.jinjava.*;
import com.hubspot.jinjava.interpret.*;
import com.hubspot.jinjava.lib.filter.Filter;
import com.hubspot.jinjava.lib.fn.ELFunctionDefinition;
import com.hubspot.jinjava.lib.tag.Tag;
import com.hubspot.jinjava.tree.TagNode;
import dev.javacpp.python.PythonErrorType;
import dev.javacpp.python.PythonException;
import dev.javacpp.python.PythonSlices;
import dev.javacpp.python.PythonStrings;
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
                .withKeepTrailingNewline(true)
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
                        .withIterateOverMapKeys(true)
                        .withHandleBackslashInQuotesOnly(true)
                        .withParseWhitespaceControlStrictly(true)
                        .withUseTrimmingForNotesAndExpressions(true)
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
            "",
            "_cpp_python_slice_soft",
            PythonJinjaTemplate.class,
            "sliceSoft",
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
    engine.registerFunction(
        new ELFunctionDefinition("", "range", PythonJinjaTemplate.class, "range", Object[].class));
    engine.registerFunction(
        new ELFunctionDefinition("", "len", PythonJinjaTemplate.class, "pyLen", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_raw", PythonJinjaTemplate.class, "rawLiteral", String.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_python_index",
            PythonJinjaTemplate.class,
            "index",
            Object.class,
            Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_eq", PythonJinjaTemplate.class, "pyEq", Object.class, Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_ne", PythonJinjaTemplate.class, "pyNe", Object.class, Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_py_tdiv",
            PythonJinjaTemplate.class,
            "pyTrueDiv",
            Object.class,
            Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_py_floordiv",
            PythonJinjaTemplate.class,
            "pyFloorDiv",
            Object.class,
            Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_py_mod",
            PythonJinjaTemplate.class,
            "pyMod",
            Object.class,
            Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_py_tern",
            PythonJinjaTemplate.class,
            "pyTern",
            Object.class,
            Object.class,
            Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_in", PythonJinjaTemplate.class, "pyIn", Object.class, Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_py_notin",
            PythonJinjaTemplate.class,
            "pyNotIn",
            Object.class,
            Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_cat", PythonJinjaTemplate.class, "pyCat", Object.class, Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_not", PythonJinjaTemplate.class, "pyNot", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_truthy", PythonJinjaTemplate.class, "pyTruthy", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_istrue", PythonJinjaTemplate.class, "pyIsTrue", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_isfalse", PythonJinjaTemplate.class, "pyIsFalse", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_strict", PythonJinjaTemplate.class, "pyStrict", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_defined", PythonJinjaTemplate.class, "pyDefined", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_py_ndef", PythonJinjaTemplate.class, "pyNdef", Object.class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "", "_cpp_tuple", PythonJinjaTemplate.class, "pyTuple", Object[].class));
    engine.registerFunction(
        new ELFunctionDefinition(
            "",
            "_cpp_dictget",
            PythonJinjaTemplate.class,
            "dictGet",
            Object.class,
            Object.class));
    engine.registerFilter(
        new Filter() {
          private final com.hubspot.jinjava.lib.filter.ListFilter delegate =
              new com.hubspot.jinjava.lib.filter.ListFilter();

          public String getName() {
            return "list";
          }

          public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
            if (var instanceof Map<?, ?> map) return new ArrayList<>(map.keySet());
            return delegate.filter(var, interpreter, args);
          }
        });
    generatorFilter(engine, "map", "sync_do_map", new com.hubspot.jinjava.lib.filter.MapFilter());
    generatorFilter(
        engine, "select", "select_or_reject", new com.hubspot.jinjava.lib.filter.SelectFilter());
    generatorFilter(
        engine, "reject", "select_or_reject", new com.hubspot.jinjava.lib.filter.RejectFilter());
    generatorFilter(engine, "batch", "do_batch", new com.hubspot.jinjava.lib.filter.BatchFilter());
    generatorFilter(
        engine, "unique", "sync_do_unique", new com.hubspot.jinjava.lib.filter.UniqueFilter());
    engine.registerFilter(
        new Filter() {
          public String getName() {
            return "tojson";
          }

          public Object filter(Object value, JinjavaInterpreter interpreter, String... args) {
            return dumpsJson(unnone(value), false, null, null, false);
          }

          public Object filter(
              Object value,
              JinjavaInterpreter interpreter,
              Object[] args,
              Map<String, Object> kwargs) {
            Integer indent = null;
            if (kwargs.get("indent") != null) {
              indent = ((Number) kwargs.get("indent")).intValue();
            }
            return dumpsJson(
                unnone(value),
                truth(kwargs.getOrDefault("ensure_ascii", false)),
                indent,
                (List<String>) kwargs.get("separators"),
                truth(kwargs.getOrDefault("sort_keys", false)));
          }
        });
    engine.registerFilter(
        new Filter() {
          public String getName() {
            return "sum";
          }

          public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
            return pySum(var);
          }
        });
    // jinja2 'x|default(d[,true])' substitutes d for an undefined value (and for a
    // falsy one when the boolean flag is set) - jinjava's stock version returns our
    // Undefined sentinel unchanged.
    engine.registerFilter(
        new Filter() {
          public String getName() {
            return "default";
          }

          public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
            Object substitute = "";
            if (args.length > 0) {
              substitute = args[0];
            }
            if (var == null || var instanceof Undefined) return substitute;
            if (args.length > 1
                && dev.javacpp.python.Py.truth(unnone(args[1]))
                && !dev.javacpp.python.Py.truth(unnone(var))) return substitute;
            return var;
          }
        });
    engine.registerFilter(
        new Filter() {
          public String getName() {
            return "attr";
          }

          public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
            // jinja2's attr filter resolves getattr only; on an Undefined value the
            // lookup itself raises UndefinedError.
            if (var == null || var instanceof Undefined)
              throw error(PythonErrorType.UNDEFINED_ERROR, "object has no attribute");
            if (var instanceof Map<?, ?> map) {
              String key = "";
              if (args.length > 0) {
                key = String.valueOf(args[0]);
              }
              if (map.containsKey(key)) {
                return map.get(key);
              }
              return Undefined.INSTANCE;
            }
            return Undefined.INSTANCE;
          }
        });
    // jinja2's |title capitalises the first letter of words separated by '-'
    // or whitespace - different from str.title() where '_' also breaks words.
    engine.registerFilter(
        new Filter() {
          public String getName() {
            return "title";
          }

          public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
            String text = dev.javacpp.python.Py.str(unnone(var));
            StringBuilder out = new StringBuilder(text.length());
            for (String piece : text.split("(?=[-\\s])|(?<=[-\\s])", -1)) {
              if (piece.isEmpty()) continue;
              int first = piece.codePointAt(0);
              out.appendCodePoint(Character.toUpperCase(first))
                  .append(piece.substring(Character.charCount(first)).toLowerCase());
            }
            return out.toString();
          }
        });
    for (String[] pyFilter :
        new String[][] {
          {"upper", "upper"}, {"lower", "lower"}, {"capitalize", "capitalize"},
          {"trim", "strip"}
        }) {
      engine.registerFilter(
          new Filter() {
            public String getName() {
              return pyFilter[0];
            }

            public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
              // jinja2's upper/lower/capitalize/trim filters stringify the value first
              // (true|upper -> 'TRUE'), unlike the py_* str-method port.
              Object input;
              if (var instanceof Undefined) {
                input = "";
              } else if (var instanceof String) {
                input = var;
              } else {
                input = dev.javacpp.python.Py.str(unnone(var));
              }
              return PythonStrings.apply(pyFilter[1], input, unnone(args));
            }
          });
    }
    for (String method : PythonStrings.NAMES) {
      engine.registerFilter(
          new Filter() {
            public String getName() {
              return "py_" + method;
            }

            public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
              return PythonStrings.apply(method, filterInput(var), unnone(args));
            }

            public Object filter(
                Object var,
                JinjavaInterpreter interpreter,
                Object[] args,
                Map<String, Object> kwargs) {
              return PythonStrings.apply(method, filterInput(var), unnone(args));
            }

            private Object filterInput(Object var) {
              if (var instanceof Undefined) {
                return "";
              }
              return var;
            }
          });
    }
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
    values.put("__cpp_none", dev.javacpp.python.PyNone.INSTANCE);
    for (var entry : bindings.entrySet()) {
      Object binding = entry.getValue();
      if (binding == null) {
        binding = dev.javacpp.python.PyNone.INSTANCE;
      }
      values.put(entry.getKey(), binding);
    }
    RenderResult result = engine.renderForResult(source, values);
    check(result.getErrors());
    return result.getOutput();
  }

  public static String raiseException(Object value) {
    throw error(PythonErrorType.VALUE_ERROR, string(value));
  }

  /**
   * Python slice {@code value[a:b:c]}: jinja2 compiles slices to {@code obj[slice(..)]}
   * without the environment.getitem guard, so a CPython TypeError propagates as a render
   * error (unlike plain {@code obj[key]} subscripts which degrade to undefined).
   */
  public static Object slice(Object value, Object start, Object stop, Object step) {
    return sliceOrThrow(value, start, stop, step);
  }

  /**
   * Slice on a literal base ({@code 'ab'[1:'x']}, {@code (1,2)[::0]}): jinja2 constant-
   * folds these through environment.getitem, which converts TypeError/LookupError into
   * an undefined that renders ''. Only those two error types degrade; the rest raise.
   */
  public static Object sliceSoft(Object value, Object start, Object stop, Object step) {
    try {
      return sliceOrThrow(value, start, stop, step);
    } catch (PythonException failure) {
      if (failure.type() == PythonErrorType.TYPE_ERROR
          || failure.type() == PythonErrorType.INDEX_ERROR) return Undefined.INSTANCE;
      throw failure;
    }
  }

  private static Object sliceOrThrow(Object value, Object start, Object stop, Object step) {
    if (value instanceof PyJinjaTuple tuple) {
      // tuple[a:b] -> tuple; keep the jinja wrapper so {% set %} and macro args keep
      // the '(1, 2)' repr.
      return PyJinjaTuple.of(
          PythonSlices.slice(tuple.inner, unnone(start), unnone(stop), unnone(step)));
    }
    if (value instanceof PyRange range) {
      int size = range.size();
      java.math.BigInteger stride = java.math.BigInteger.ONE;
      if (unnone(step) != null) {
        stride = sliceIndex(unnone(step));
      }
      if (stride.signum() == 0)
        throw error(PythonErrorType.VALUE_ERROR, "slice step cannot be zero");
      boolean reverse = stride.signum() < 0;
      long first;
      if (unnone(start) == null) {
        if (reverse) {
          first = size - 1;
        } else {
          first = 0;
        }
      } else {
        first =
            dev.javacpp.python.Py.bound(
                dev.javacpp.python.Py.index(unnone(start)), size, reverse);
      }
      long last;
      if (unnone(stop) == null) {
        if (reverse) {
          last = -1;
        } else {
          last = size;
        }
      } else {
        last =
            dev.javacpp.python.Py.bound(
                dev.javacpp.python.Py.index(unnone(stop)), size, reverse);
      }
      return new PyRange(
          range.start() + first * range.step(),
          range.start() + last * range.step(),
          range.step() * stride.longValue());
    }
    return PythonSlices.slice(value, unnone(start), unnone(stop), unnone(step));
  }

  /**
   * dict.name access on literals and parenthesised values ('{..}.k', '(x).k').
   * A null/Undefined base is an unbound name or a missing-key chain whose next
   * attribute lookup raises jinja2's UndefinedError.
   */
  public static Object dictGet(Object value, Object key) {
    if (value == null || value instanceof Undefined)
      throw error(PythonErrorType.UNDEFINED_ERROR, "base is undefined");
    Object name = unnone(key);
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey(name)) {
        return map.get(name);
      }
      return Undefined.INSTANCE;
    }
    if (value instanceof com.hubspot.jinjava.objects.Namespace ns) {
      Object resolved = ns.get(String.valueOf(name));
      if (resolved == null) {
        return Undefined.INSTANCE;
      }
      return resolved;
    }
    return Undefined.INSTANCE;
  }

  private static java.math.BigInteger sliceIndex(Object value) {
    strict(value);
    try {
      return dev.javacpp.python.Py.index(value);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "slice indices must be integers or None or have an __index__ method");
    }
  }

  /** Decode a base64 raw-block payload produced by the dialect. */
  public static String rawLiteral(String encoded) {
    return new String(
        Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Python equality: unlike jinjava's coercing ==, mismatched types just compare false. */
  public static Object pyEq(Object left, Object right) {
    return dev.javacpp.python.Py.equal(strict(left), strict(right));
  }

  public static Object pyNe(Object left, Object right) {
    return !dev.javacpp.python.Py.equal(strict(left), strict(right));
  }

  /** Python /: always float division, and division by zero raises. */
  public static Object pyTrueDiv(Object left, Object right) {
    double a = pyNumber(left), b = pyNumber(right);
    if (b == 0)
      throw error(PythonErrorType.ZERO_DIVISION_ERROR, "division by zero");
    return a / b;
  }

  /** Python //: floored division (Java % truncates toward zero). */
  public static Object pyFloorDiv(Object left, Object right) {
    if (!isFloat(left) && !isFloat(right)) {
      long a = pyLong(left), b = pyLong(right);
      if (b == 0)
        throw error(PythonErrorType.ZERO_DIVISION_ERROR, "integer division or modulo by zero");
      return Math.floorDiv(a, b);
    }
    double b = pyNumber(right);
    if (b == 0)
      throw error(PythonErrorType.ZERO_DIVISION_ERROR, "float floor division by zero");
    return Math.floor(pyNumber(left) / b);
  }

  /** Python %: floored modulo, keeping float results when either side is float. */
  public static Object pyMod(Object left, Object right) {
    if (!isFloat(left) && !isFloat(right)) {
      long a = pyLong(left), b = pyLong(right);
      if (b == 0)
        throw error(PythonErrorType.ZERO_DIVISION_ERROR, "integer division or modulo by zero");
      return Math.floorMod(a, b);
    }
    double a = pyNumber(left), b = pyNumber(right);
    if (b == 0)
      throw error(PythonErrorType.ZERO_DIVISION_ERROR, "float modulo");
    double result = a - Math.floor(a / b) * b;
    if (result == 0) {
      return Math.copySign(0, b);
    }
    return result;
  }

  /** jinja2 has no len builtin - 'len(x)' raises UndefinedError when evaluated. */
  public static Object pyLen(Object value) {
    // Deferred: jinjava evaluates _cpp_py_tern arguments eagerly, so a 'len' in a
    // dead ternary arm must not fail unless the result is actually observed.
    return new PyDeferredError(error(PythonErrorType.UNDEFINED_ERROR, "'len' is undefined"));
  }

  /**
   * A computation result that only raises when a consumer actually observes it -
   * used for eager-evaluated expressions whose failure must stay lazy.
   */
  public static final class PyDeferredError {
    private final PythonException error;

    PyDeferredError(PythonException error) {
      this.error = error;
    }
  }

  /** Throw the deferred error if the value carries one (observed = failed). */
  private static Object strict(Object value) {
    if (value instanceof PyDeferredError deferred) throw deferred.error;
    return value;
  }

  /** Python ternary / or / and helper: returns the deciding operand by truthiness. */
  public static Object pyTern(Object cond, Object ifTrue, Object ifFalse) {
    if (dev.javacpp.python.Py.truth(unnone(cond))) {
      return ifTrue;
    }
    return ifFalse;
  }

  /** jinja2 '{% if X %}' truthiness: PyObject_IsTrue on the condition. */
  public static Object pyTruthy(Object value) {
    return dev.javacpp.python.Py.truth(unnone(value));
  }

  /** jinja2 'x is true' / 'x is false': identity with the boolean object, not ==. */
  public static Object pyIsTrue(Object value) {
    return unnone(value) instanceof Boolean flag && flag;
  }

  public static Object pyIsFalse(Object value) {
    return unnone(value) instanceof Boolean flag && !flag;
  }

  /** Force observation of a deferred-error value ('{% set t = len() %}' must fail). */
  public static Object pyStrict(Object value) {
    return strict(value);
  }

  /** Python membership: 'a in b' delegates to Py.contains; TypeErrors propagate. */
  public static Object pyIn(Object item, Object container) {
    strict(item);
    strict(container);
    if (container instanceof Undefined)
      throw error(PythonErrorType.UNDEFINED_ERROR, "argument of type 'Undefined' is not iterable");
    return dev.javacpp.python.Py.contains(container, item);
  }

  public static Object pyNotIn(Object item, Object container) {
    return !(Boolean) pyIn(item, container);
  }

  /** Python '~' string concatenation: both operands stringify first. */
  public static Object pyCat(Object left, Object right) {
    return dev.javacpp.python.Py.str(unnone(left)) + dev.javacpp.python.Py.str(unnone(right));
  }

  public static Object pyNot(Object value) {
    return !dev.javacpp.python.Py.truth(unnone(value));
  }

  public static Object pyDefined(Object value) {
    return !(value instanceof Undefined) && value != null;
  }

  public static Object pyNdef(Object value) {
    return value instanceof Undefined || value == null;
  }

  /** Tuple literal inside a function-call argument position. */
  public static Object pyTuple(Object... elements) {
    return new PyJinjaTuple(new ArrayList<>(Arrays.asList(elements)));
  }

  private static boolean isFloat(Object value) {
    return value instanceof Float || value instanceof Double;
  }

  private static long pyLong(Object value) {
    if (value instanceof Boolean bool) {
      if (bool) {
        return 1;
      }
      return 0;
    }
    return dev.javacpp.python.Py.index(value).longValue();
  }

  private static double pyNumber(Object value) {
    if (value instanceof Boolean bool) {
      if (bool) {
        return 1.0;
      }
      return 0.0;
    }
    if (value instanceof Number number) return number.doubleValue();
    return dev.javacpp.python.Py.floating(value);
  }

  /**
   * Python subscript {@code value[key]}: negative indexes wrap, out-of-range or wrong key
   * types render empty like jinja2's environment.getitem, and unresolved (null) receivers
   * fail like an undefined-chain subscript.
   */
  public static Object index(Object value, Object key) {
    if (value == null)
      throw error(PythonErrorType.TYPE_ERROR, "'NoneType' object is not subscriptable");
    if (value instanceof dev.javacpp.python.PyNone) return null;
    value = unnone(value); // PyJinjaTuple unwraps to the List-backed PyTuple
    if (value instanceof Map<?, ?> map) return map.get(key);
    java.math.BigInteger i;
    try {
      i = dev.javacpp.python.Py.index(key);
    } catch (PythonException failure) {
      return null;
    }
    if (value instanceof String text) {
      int size = text.codePointCount(0, text.length());
      if (i.compareTo(java.math.BigInteger.valueOf(size)) >= 0
          || i.compareTo(java.math.BigInteger.valueOf(-size)) < 0) return null;
      int at = (i.intValue() + size) % size;
      return new String(Character.toChars(text.codePointAt(text.offsetByCodePoints(0, at))));
    }
    if (value instanceof List<?> list) {
      int size = list.size(), at = i.intValue();
      if (at >= size || at < -size) return null;
      return list.get((at + size) % size);
    }
    if (value instanceof PyRange range) {
      int size = range.size();
      if (i.compareTo(java.math.BigInteger.valueOf(size)) >= 0
          || i.compareTo(java.math.BigInteger.valueOf(-size)) < 0) return null;
      return range.get((i.intValue() + size) % size);
    }
    return null;
  }

  /**
   * jinja2's tojson filter delegates to json.dumps - Undefined, Namespace, range and
   * generator inputs raise 'Object of type X is not JSON serializable'.
   */
  private static String dumpsJson(
      Object value, boolean ascii, Integer indent, List<String> separators, boolean sortKeys) {
    if (value instanceof Undefined
        || value instanceof com.hubspot.jinjava.objects.Namespace
        || value instanceof PyRange
        || value instanceof PyGenerator)
      throw error(
          PythonErrorType.TYPE_ERROR,
          "Object of type " + jsonTypeName(value) + " is not JSON serializable");
    return PythonJson.dumps(value, ascii, indent, separators, sortKeys);
  }

  private static String jsonTypeName(Object value) {
    if (value instanceof Undefined) return "Undefined";
    if (value instanceof com.hubspot.jinjava.objects.Namespace) return "Namespace";
    if (value instanceof PyRange) return "range";
    if (value instanceof PyGenerator) return "generator";
    return dev.javacpp.python.Py.typeName(value);
  }

  /**
   * jinja2's {@code |sum} filter: numeric elements accumulate; a non-number makes the
   * '+': unsupported-operand TypeError surface.
   */
  public static Object pySum(Object var) {
    Object source = unnone(var);
    if (source != null
        && !(source instanceof Undefined)
        && !(source instanceof Iterable<?>)
        && !(source instanceof Iterator<?>)
        && !(source instanceof Map<?, ?>)
        && !source.getClass().isArray())
      throw error(
          PythonErrorType.TYPE_ERROR,
          "object of type '" + dev.javacpp.python.Py.typeName(source) + "' has no len()");
    java.util.Iterator<?> iterator = iterate(source);
    long intTotal = 0;
    double floatTotal = 0.0;
    boolean floating = false;
    while (iterator.hasNext()) {
      Object item = unnone(iterator.next());
      if (item instanceof Boolean flag) {
        if (flag) {
          intTotal += 1;
          floatTotal += 1.0;
        }
      } else if (item instanceof Byte
          || item instanceof Short
          || item instanceof Integer
          || item instanceof Long
          || item instanceof java.math.BigInteger) {
        long n = ((Number) item).longValue();
        intTotal += n;
        floatTotal += n;
      } else if (item instanceof Number number) {
        double n = number.doubleValue();
        if (n == Math.rint(n) && Double.isFinite(n)) {
          intTotal += (long) n;
          floatTotal += n;
        } else {
          floating = true;
          floatTotal += n;
        }
      } else {
        throw error(
            PythonErrorType.TYPE_ERROR,
            "unsupported operand type(s) for +: 'int' and '"
                + dev.javacpp.python.Py.typeName(item)
                + "'");
      }
    }
    if (floating) {
      return Double.valueOf(floatTotal);
    }
    return Long.valueOf(intTotal);
  }

  private static Object unnone(Object value) {
    strict(value);
    if (value instanceof dev.javacpp.python.PyNone) return null;
    // Internally a jinja tuple behaves exactly like a PyTuple; the wrapper only
    // shields it from jinjava's List->SizeLimitingPyList resolution copying.
    if (value instanceof PyJinjaTuple tuple) return tuple.inner;
    return value;
  }

  private static Object[] unnone(Object[] args) {
    Object[] copy = args.clone();
    for (int i = 0; i < copy.length; i++) copy[i] = unnone(copy[i]);
    return copy;
  }

  /** jinja2 range(): list-backed value whose repr is "range(start, stop[, step])". */
  public static Object range(Object... args) {
    long start = 0, stop, step = 1;
    if (args.length == 1) {
      stop = rangeBound(args[0]);
    } else if (args.length == 2) {
      start = rangeBound(args[0]);
      stop = rangeBound(args[1]);
    } else {
      start = rangeBound(args[0]);
      stop = rangeBound(args[1]);
      step = rangeBound(args[2]);
    }
    return new PyRange(start, stop, step);
  }

  private static long rangeBound(Object value) {
    return dev.javacpp.python.Py.index(value).longValue();
  }

  /** Register a filter whose Iterable result prints like the jinja2 generator it replaces. */
  private static void generatorFilter(
      Jinjava engine, String name, String generator, Filter delegate) {
    engine.registerFilter(
        new Filter() {
          public String getName() {
            return name;
          }

          public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
            if (args.length == 0 && (name.equals("select") || name.equals("reject")))
              return truthySelect(var, name.equals("select"));
            return PyGenerator.wrap(generator, delegate.filter(var, interpreter, args));
          }

          public Object filter(
              Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
            if (args.length == 0 && (name.equals("select") || name.equals("reject")))
              return truthySelect(var, name.equals("select"));
            return PyGenerator.wrap(generator, delegate.filter(var, interpreter, args, kwargs));
          }

          // jinja2 'x|select'/'x|reject' without a test name filters by truthiness.
          private Object truthySelect(Object var, boolean keep) {
            List<Object> items = new ArrayList<>();
            java.util.Iterator<?> it = iterate(var);
            while (it != null && it.hasNext()) {
              Object item = it.next();
              if (dev.javacpp.python.Py.truth(unnone(item)) == keep) items.add(item);
            }
            return PyGenerator.wrap(generator, items);
          }
        });
  }

  /** Iterate an Iterable/Iterator/array/Map(keys); null/Undefined gives an empty iterator. */
  private static java.util.Iterator<?> iterate(Object source) {
    strict(source);
    if (source instanceof Iterable<?> iterable) return iterable.iterator();
    if (source instanceof java.util.Iterator<?> it) return it;
    if (source instanceof Map<?, ?> map) return map.keySet().iterator();
    if (source != null && source.getClass().isArray())
      return java.util.Arrays.asList((Object[]) source).iterator();
    return java.util.Collections.emptyIterator();
  }

  /**
   * jinja2 range value: iterable and reprs as "range(start, stop)" / "range(start, stop,
   * step)". Deliberately not a Collection - jinjava's resolver wraps Lists/Maps into
   * SizeLimitingPy* copies, which would lose the range/generator repr when passed through
   * macro arguments or attribute resolution.
   */
  public static final class PyRange
      implements Iterable<Object>, dev.javacpp.python.PySized, dev.javacpp.python.PyComparable {
    private final long start, stop, step;

    PyRange(long start, long stop, long step) {
      this.start = start;
      this.stop = stop;
      this.step = step;
    }

    long start() {
      return start;
    }

    long step() {
      return step;
    }

    Object get(int index) {
      return start + (long) index * step;
    }

    int size() {
      long distance;
      if (step > 0) {
        distance = stop - start;
      } else {
        distance = start - stop;
      }
      long length;
      if (distance <= 0) {
        length = 0;
      } else {
        length = (distance + Math.abs(step) - 1) / Math.abs(step);
      }
      if (length > Integer.MAX_VALUE) {
        return Integer.MAX_VALUE;
      }
      return (int) length;
    }

    @Override
    public int pyLen() {
      return size();
    }

    /** CPython range equality: same start/stop/step (0/1-step tuples compare equal too). */
    @Override
    public Object pyCompare(Object other, dev.javacpp.python.Py.Op op) {
      boolean same = other instanceof PyRange r && start == r.start && stop == r.stop && step == r.step;
      return switch (op) {
        case EQ -> same;
        case NE -> !same;
        default -> dev.javacpp.python.Py.NOT_IMPLEMENTED;
      };
    }

    @Override
    public Iterator<Object> iterator() {
      return new Iterator<>() {
        private long next = start;

        public boolean hasNext() {
          if (step > 0) {
            return next < stop;
          }
          return next > stop;
        }

        public Object next() {
          long value = next;
          next += step;
          return value;
        }
      };
    }

    @Override
    public String toString() {
      if (step == 1) {
        return "range(" + start + ", " + stop + ")";
      }
      return "range(" + start + ", " + stop + ", " + step + ")";
    }
  }

  /** Lazily-materialized generator: prints like jinja2's, iterates like a list. */
  /**
   * Template-level tuple: reprs and compares like a PyTuple but is not a Collection,
   * so jinjava's resolver does not copy it into a SizeLimitingPyList (which would
   * render '[1, 2]') when the value passes through macro arguments or {% set %}.
   */
  public static final class PyJinjaTuple
      implements Iterable<Object>, dev.javacpp.python.PySized, dev.javacpp.python.PyComparable {
    private final dev.javacpp.python.PyTuple<Object> inner;

    PyJinjaTuple(List<Object> elements) {
      this.inner = dev.javacpp.python.PyTuple.wrap(elements);
    }

    static PyJinjaTuple of(Object value) {
      if (value instanceof PyJinjaTuple t) {
        return t;
      }
      return new PyJinjaTuple(new ArrayList<>((List<Object>) value));
    }

    Object get(int index) {
      return inner.get(index);
    }

    int size() {
      return inner.size();
    }

    @Override
    public int pyLen() {
      return inner.size();
    }

    @Override
    public Iterator<Object> iterator() {
      return inner.iterator();
    }

    @Override
    public Object pyCompare(Object other, dev.javacpp.python.Py.Op op) {
      Object rhs = other;
      if (other instanceof PyJinjaTuple t) {
        rhs = t.inner;
      }
      return switch (op) {
        case EQ -> dev.javacpp.python.Py.equal(inner, rhs);
        case NE -> !dev.javacpp.python.Py.equal(inner, rhs);
        default -> dev.javacpp.python.Py.NOT_IMPLEMENTED;
      };
    }

    @Override
    public String toString() {
      return dev.javacpp.python.Py.str(inner);
    }
  }

  public static final class PyGenerator implements Iterable<Object> {
    private final String name;
    private final List<Object> items;

    private PyGenerator(String name, List<Object> items) {
      this.name = name;
      this.items = items;
    }

    static Object wrap(String name, Object result) {
      if (result == null || result instanceof PyGenerator) return result;
      if (result instanceof Iterator<?> iterator) {
        List<Object> items = new ArrayList<>();
        iterator.forEachRemaining(items::add);
        return new PyGenerator(name, items);
      }
      if (result instanceof java.util.stream.Stream<?> stream)
        return new PyGenerator(name, new ArrayList<>(stream.toList()));
      if (result instanceof Iterable<?> iterable) return new PyGenerator(name, toList(iterable));
      return result;
    }

    private static List<Object> toList(Iterable<?> iterable) {
      List<Object> items = new ArrayList<>();
      for (Object item : iterable) items.add(item);
      return items;
    }

    @Override
    public Iterator<Object> iterator() {
      return items.iterator();
    }

    @Override
    public String toString() {
      return "<generator object " + name + " at 0x0>";
    }
  }

  /** ChainableUndefined: renders empty, is falsy, and marks resolved-null EL results. */
  static final class Undefined extends java.util.AbstractCollection<Object> {
    static final Undefined INSTANCE = new Undefined();

    @Override
    public Iterator<Object> iterator() {
      return Collections.emptyIterator();
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public String toString() {
      return "";
    }
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
      if (value == null) return "None";
      strict(value);
      if (value instanceof Undefined) return "";
      if (value instanceof dev.javacpp.python.PyNone) return "None";
      if (value instanceof String text) return text;
      if (value instanceof com.hubspot.jinjava.objects.Namespace ns)
        return "<Namespace " + dev.javacpp.python.Py.repr(new LinkedHashMap<>(ns)) + ">";
      if (value instanceof PyRange || value instanceof PyGenerator) return value.toString();
      return dev.javacpp.python.Py.str(value);
    }

    @Override
    public Object resolveELExpression(String expression, int line) {
      String text = expression.strip();
      var dotted =
          java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\.[A-Za-z_][A-Za-z_0-9]*")
              .matcher(text);
      if (dotted.matches() && !getContext().containsKey(dotted.group(1)))
        throw error(PythonErrorType.UNDEFINED_ERROR, "'" + dotted.group(1) + "' is undefined");
      var subscript =
          java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\[[^]]*]")
              .matcher(text);
      if (subscript.matches() && !getContext().containsKey(subscript.group(1)))
        throw error(PythonErrorType.UNDEFINED_ERROR, "'" + subscript.group(1) + "' is undefined");
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
      Object result = super.resolveELExpression(expression, line);
      if (result != null) return result;
      // A null EL result is either a Python none binding (renders "None") or an
      // undefined lookup (renders ""). Bare bound names keep the null; everything
      // else - missing names, failed attribute/item access - becomes Undefined.
      if (text.matches("[A-Za-z_]\\w*") && getContext().containsKey(text)) return null;
      return Undefined.INSTANCE;
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
