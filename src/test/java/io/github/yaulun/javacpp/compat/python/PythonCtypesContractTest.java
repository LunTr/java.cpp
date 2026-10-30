package io.github.yaulun.javacpp.compat.python;

import static org.junit.jupiter.api.Assertions.*;

import io.github.yaulun.javacpp.compat.python.PythonCtypesExtensions.Host;
import io.github.yaulun.javacpp.compat.python.PythonCtypesExtensions.Path;
import io.github.yaulun.javacpp.compat.python.PythonCtypesExtensions.PythonFunction;
import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PyAttributed;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Replays the pinned _ctypes_extensions.py fixture over traced Java port doubles. */
class PythonCtypesContractTest {
  private static final String OBJECT_DOC =
      "The base class of the class hierarchy.\n\n"
          + "When called, it accepts no arguments and returns a new featureless\n"
          + "instance that has no instance attributes and cannot be given any.\n";
  private static final String STR_DOC =
      "str(object='') -> str\n"
          + "str(bytes_or_buffer[, encoding[, errors]]) -> str\n"
          + "\n"
          + "Create a new string object from the given object. If encoding or\n"
          + "errors is specified, then the object must expose a data buffer\n"
          + "that will be decoded using the given encoding and error handler.\n"
          + "Otherwise, returns the result of object.__str__() (if defined)\n"
          + "or repr(object).\n"
          + "encoding defaults to 'utf-8'.\n"
          + "errors defaults to 'strict'.";

  @TestFactory
  Stream<DynamicTest> matchesPinnedCtypesExtensions() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-ctypes-contracts.tsv"),
            "Run generate_ctypes_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index ->
                DynamicTest.dynamicTest(
                    "ctypes:" + index,
                    () -> {
                      String[] parts = lines.get(index).split("\t");
                      Object argument = decode(parts[1]);
                      Object actual =
                          switch (parts[0]) {
                            case "load" -> runLoad(cast(argument));
                            case "decorator" -> runDecorator(cast(argument));
                            case "byref" -> runByref(cast(argument));
                            default -> throw new IllegalArgumentException(parts[0]);
                          };
                      assertEquals(parts[2], encode(actual), "argument=" + argument);
                    }));
  }

  // ---- load_shared_library --------------------------------------------------------------

  private static Object runLoad(Map<Object, Object> argument) {
    List<List<Object>> trace = new ArrayList<>();
    var existing = new java.util.HashSet<String>();
    for (Object path : list(argument, "existing")) existing.add((String) path);
    var environ = new TraceEnviron(trace, list(argument, "env"));
    Map<String, List<Object>> failures = new LinkedHashMap<>();
    for (Object entry : list(argument, "fail"))
      failures.put((String) castList(entry).get(0), castList(entry).subList(1, 3));
    var host =
        new Host() {
          @Override
          public String platform() {
            return (String) argument.get("platform");
          }

          @Override
          public List<Integer> versionInfo() {
            List<Object> version = list(argument, "version");
            if (version.isEmpty()) version = List.of(3, 13, 9);
            List<Integer> numbers = new ArrayList<>();
            for (Object field : version) numbers.add(((Number) field).intValue());
            return numbers;
          }

          @Override
          public void addDllDirectory(String directory) {
            trace.add(List.of("add-dll-dir", directory));
          }

          @Override
          public Map<String, String> environ() {
            return environ;
          }

          @Override
          public String pathsep() {
            Object separator = argument.get("pathsep");
            return separator == null ? ";" : (String) separator;
          }

          @Override
          public String joinPath(String directory, String name) {
            trace.add(List.of("join", directory, name));
            return directory + "/" + name;
          }

          @Override
          public Object loadLibrary(String path, Map<String, Object> keywords) {
            List<Object> pairs = new ArrayList<>();
            for (var entry : keywords.entrySet())
              pairs.add(java.util.Arrays.asList(entry.getKey(), entry.getValue()));
            trace.add(List.of("cdll", path, pairs));
            List<Object> failure = failures.get(path);
            if (failure != null) throw new RuntimeException((String) failure.get(1));
            return List.of("lib", path);
          }

          @Override
          public Object rtldGlobal() {
            Object rtld = argument.get("rtld");
            return rtld == null ? 2 : rtld;
          }

          @Override
          public Object byref(Object object, Object offset) {
            throw new UnsupportedOperationException();
          }
        };
    var base = new FakePath(trace, existing, (String) argument.get("base"));
    var extensions = new PythonCtypesExtensions(host);
    Map<String, Object> outcome = new LinkedHashMap<>();
    try {
      outcome.put("result", extensions.loadSharedLibrary((String) argument.get("name"), base));
    } catch (PythonException error) {
      outcome.put("error", error.type().pythonName());
      outcome.put("message", error.getMessage());
    } catch (RuntimeException error) {
      outcome.put("error", error.getClass().getSimpleName());
      outcome.put("message", error.getMessage());
    }
    outcome.put("trace", trace);
    List<Object> env = new ArrayList<>();
    for (String key : new ArrayList<>(environ.keySet())) env.add(List.of(key, environ.get(key)));
    outcome.put("env", env);
    return outcome;
  }

  /** os.environ double: os.environ[key] raises the original KeyError on missing keys. */
  private static final class TraceEnviron extends LinkedHashMap<String, String> {
    private final List<List<Object>> trace;

    TraceEnviron(List<List<Object>> trace, List<Object> pairs) {
      this.trace = trace;
      for (Object pair : pairs) {
        List<Object> entry = castList(pair);
        super.put((String) entry.get(0), (String) entry.get(1));
      }
    }

    @Override
    public String get(Object key) {
      trace.add(List.of("env-get", key));
      if (!super.containsKey(key)) throw Py.error(PythonErrorType.KEY_ERROR, "'" + key + "'");
      return super.get(key);
    }

    @Override
    public String put(String key, String value) {
      trace.add(List.of("env-set", key, value));
      return super.put(key, value);
    }

    @Override
    public boolean containsKey(Object key) {
      trace.add(List.of("env-has", key));
      return super.containsKey(key);
    }

    @Override
    public String getOrDefault(Object key, String defaultValue) {
      trace.add(List.of("env-getdef", key, defaultValue));
      return super.getOrDefault(key, defaultValue);
    }
  }

  private static final class FakePath implements Path {
    private final List<List<Object>> trace;
    private final java.util.Set<String> existing;
    private final String text;

    FakePath(List<List<Object>> trace, java.util.Set<String> existing, String text) {
      this.trace = trace;
      this.existing = existing;
      this.text = text;
    }

    @Override
    public Path join(String name) {
      return new FakePath(trace, existing, text + "/" + name);
    }

    @Override
    public boolean exists() {
      trace.add(List.of("exists", text));
      return existing.contains(text);
    }

    @Override
    public String toString() {
      return text;
    }
  }

  // ---- ctypes_function_for_shared_library chain -----------------------------------------

  private static Object runDecorator(Map<Object, Object> argument) {
    List<List<Object>> trace = new ArrayList<>();
    var library =
        (PyAttributed)
            name -> {
              trace.add(List.of("getattr", name));
              if (argument.containsKey("missing"))
                throw Py.error(
                    PythonErrorType.ATTRIBUTE_ERROR, "function '" + name + "' not found");
              return new PythonFunction((args, kwargs) -> null);
            };
    Object f = wrapped(argument.get("f"));
    var extensions = new PythonCtypesExtensions(null);
    var closure = extensions.ctypesFunctionForSharedLibrary(library);
    Map<String, Object> outcome = new LinkedHashMap<>();
    Object decorator;
    try {
      decorator = closure.call(list(argument, "args"), keywords(argument.get("kwargs")));
    } catch (RuntimeException error) {
      record(outcome, error);
      outcome.put("trace", trace);
      return outcome;
    }
    List<Object> fArgs =
        argument.containsKey("f_args") ? castList(argument.get("f_args")) : List.of(f);
    List<Object> callArgs = new ArrayList<>();
    for (Object value : fArgs) callArgs.add(value instanceof String text ? textValue(text) : value);
    Map<String, Object> fKeywords = keywords(argument.get("f_kwargs"));
    if (fKeywords != null)
      fKeywords.replaceAll((key, value) -> value instanceof String text ? textValue(text) : value);
    try {
      Object result =
          ((io.github.yaulun.javacpp.python.PyCallable) decorator).call(callArgs, fKeywords);
      if (result == f) {
        outcome.put("result", "f");
      } else {
        outcome.put("result", "func");
        List<Object> entries = new ArrayList<>();
        for (var entry : ((PythonFunction) result).dict().entrySet())
          entries.add(
              java.util.Arrays.asList(
                  entry.getKey(),
                  entry.getKey().equals("__wrapped__") ? "<wrapped>" : entry.getValue()));
        outcome.put("func", entries);
      }
    } catch (RuntimeException error) {
      record(outcome, error);
    }
    outcome.put("trace", trace);
    return outcome;
  }

  /** Models the f argument variants used by the pinned fixture. */
  private static Object wrapped(Object kind) {
    if (kind == null || "function".equals(kind)) {
      var function = new PythonFunction((args, kwargs) -> null);
      function
          .withDescriptor("__module__", "mod")
          .withDescriptor("__name__", "f")
          .withDescriptor("__qualname__", "run_decorator.<locals>.f")
          .withDescriptor("__doc__", "doc")
          .withDescriptor("__annotations__", new LinkedHashMap<>())
          .withDescriptor("__type_params__", new ArrayList<>());
      function.dict().put("extra", 1);
      return function;
    }
    if ("callable".equals(kind)) {
      var callable = new PythonFunction((args, kwargs) -> null);
      callable.withDescriptor("__module__", "__main__").withDescriptor("__doc__", null);
      callable.dict().put("extra", 1);
      return callable;
    }
    if ("mapping".equals(kind)) {
      Map<String, Object> mapping = new LinkedHashMap<>();
      mapping.put("extra", 1);
      return mapping;
    }
    // plain object(): only the type __doc__ resolves through getattr.
    return (PyAttributed) name -> name.equals("__doc__") ? OBJECT_DOC : Py.ABSENT;
  }

  /** A Python str argument: only the str type __doc__ resolves through getattr. */
  private static Object textValue(String value) {
    return (PyAttributed) name -> name.equals("__doc__") ? STR_DOC : Py.ABSENT;
  }

  // ---- _byref and the byref alias --------------------------------------------------------

  /** A stand-in ctypes instance for the byref port. */
  private static final class CtypesData {}

  private static Object runByref(Map<Object, Object> argument) {
    var ctypesValue = new CtypesData();
    var host =
        new Host() {
          @Override
          public String platform() {
            return "win32";
          }

          @Override
          public List<Integer> versionInfo() {
            return List.of(3, 13);
          }

          @Override
          public void addDllDirectory(String directory) {}

          @Override
          public Map<String, String> environ() {
            return Map.of();
          }

          @Override
          public String pathsep() {
            return ";";
          }

          @Override
          public String joinPath(String directory, String name) {
            return directory + "/" + name;
          }

          @Override
          public Object loadLibrary(String path, Map<String, Object> keywords) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Object rtldGlobal() {
            return 2;
          }

          @Override
          public Object byref(Object object, Object offset) {
            if (!(object instanceof CtypesData))
              throw Py.error(
                  PythonErrorType.TYPE_ERROR,
                  "byref() argument must be a ctypes instance, not '" + Py.typeName(object) + "'");
            long value;
            if (offset instanceof Boolean flag) value = flag ? 1 : 0;
            else if (offset instanceof Integer || offset instanceof Long)
              value = ((Number) offset).longValue();
            else
              throw Py.error(
                  PythonErrorType.TYPE_ERROR,
                  "'" + Py.typeName(offset) + "' object cannot be interpreted as an integer");
            return List.of("cparam", value);
          }
        };
    var extensions = new PythonCtypesExtensions(host);
    List<Object> args = new ArrayList<>();
    for (Object value : list(argument, "args")) args.add("obj".equals(value) ? ctypesValue : value);
    Map<String, Object> outcome = new LinkedHashMap<>();
    try {
      Object result =
          argument.get("call").equals("byref")
              ? extensions.byref(args, keywords(argument.get("kwargs")))
              : extensions.byrefStub(args, keywords(argument.get("kwargs")));
      outcome.put("result", result);
    } catch (RuntimeException error) {
      record(outcome, error);
    }
    return outcome;
  }

  // ---- helpers --------------------------------------------------------------------------

  private static void record(Map<String, Object> outcome, RuntimeException error) {
    if (error instanceof PythonException python) {
      outcome.put("error", python.type().pythonName());
      outcome.put("message", python.getMessage());
    } else {
      outcome.put("error", error.getClass().getSimpleName());
      outcome.put("message", error.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> cast(Object value) {
    return (Map<Object, Object>) value;
  }

  private static Map<String, Object> keywords(Object value) {
    if (value == null) return null;
    Map<String, Object> keywords = new LinkedHashMap<>();
    for (var entry : cast(value).entrySet())
      keywords.put((String) entry.getKey(), entry.getValue());
    return keywords;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> castList(Object value) {
    return (List<Object>) value;
  }

  private static List<Object> list(Map<Object, Object> argument, String key) {
    Object value = argument.get(key);
    return value == null ? List.of() : castList(value);
  }

  private static Object decode(String base64) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64))));
  }

  private static String encode(Object value) throws Exception {
    var bytes = new ByteArrayOutputStream();
    PythonContractData.write(new DataOutputStream(bytes), value);
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }
}
