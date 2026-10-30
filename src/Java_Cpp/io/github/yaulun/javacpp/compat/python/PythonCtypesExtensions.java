package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PyAttributed;
import io.github.yaulun.javacpp.python.PyCallable;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pinned _ctypes_extensions.py control flow over explicit host ports. The Host port owns
 * sys.platform/sys.version_info, os.add_dll_directory/os.environ/os.path joining and the
 * ctypes.CDLL/ctypes.byref entry points; the Path port owns pathlib joining and exists(). Every
 * load branch, environment mutation, keyword argument and exception of the original function is
 * reproduced; the ports decide whether an operating system effect actually happens.
 */
public final class PythonCtypesExtensions {
  /** The pinned _EMSCRIPTEN_SIDE_MODULE_SUFFIX constant. */
  public static final String EMSCRIPTEN_SIDE_MODULE_SUFFIX = ".cpython-00-wasm32-emscripten.so";

  /** functools.WRAPPER_ASSIGNMENTS in the pinned CPython 3.13.9 runtime. */
  private static final List<String> WRAPPER_ASSIGNMENTS =
      List.of(
          "__module__",
          "__name__",
          "__qualname__",
          "__doc__",
          "__annotations__",
          "__type_params__");

  private static final Map<String, List<String>> EMSCRIPTEN_DEPENDENCIES =
      Map.of(
          "llama", List.of("ggml-base", "ggml-cpu", "ggml"),
          "mtmd", List.of("ggml-base", "ggml-cpu", "ggml", "llama"));

  /** os/sys/ctypes services reached by the original module. */
  public interface Host {
    /** sys.platform. */
    String platform();

    /** sys.version_info leading integer fields, compared lexicographically like a tuple. */
    List<Integer> versionInfo();

    /** os.add_dll_directory. */
    void addDllDirectory(String directory);

    /** The mutable os.environ mapping; missing-key reads raise KeyError like the original. */
    Map<String, String> environ();

    /** os.pathsep. */
    String pathsep();

    /** os.path.join(directory, name). */
    String joinPath(String directory, String name);

    /** ctypes.CDLL(path, **keywords); the port throws whatever the load raises. */
    Object loadLibrary(String path, Map<String, Object> keywords);

    /** The ctypes.RTLD_GLOBAL constant value. */
    Object rtldGlobal();

    /** ctypes.byref(object, offset); the port defines the reference object and its errors. */
    Object byref(Object object, Object offset);
  }

  /** pathlib.Path operations used by the original function: / join, exists() and str(path). */
  public interface Path {
    Path join(String name);

    boolean exists();

    /** str(path). */
    @Override
    String toString();
  }

  /** Attribute-settable object: writes behave like assigning into the instance __dict__. */
  public interface MutableAttributed extends PyAttributed {
    void setAttr(String name, Object value);
  }

  /**
   * Dict-backed function or duck-typed object. Arbitrary attribute writes land in the exposed
   * insertion-ordered {@code __dict__}, matching CPython object assignment; descriptor-level
   * attributes (such as {@code __name__} on a function or {@code __module__} on a class) can be
   * layered on top through {@link #withDescriptor} and resolve through getattr without joining
   * {@code __dict__}.
   */
  public static final class PythonFunction implements MutableAttributed, PyCallable {
    private final Map<String, Object> descriptors = new LinkedHashMap<>();
    private final Map<String, Object> dict = new LinkedHashMap<>();
    private final PyCallable call;
    private final String pyType;

    public PythonFunction(PyCallable call, String pyType) {
      this.call = call;
      this.pyType = pyType;
    }

    public PythonFunction(PyCallable call) {
      this(call, "function");
    }

    /** Adds a getattr-visible attribute that stays outside the instance __dict__. */
    public PythonFunction withDescriptor(String name, Object value) {
      descriptors.put(name, value);
      return this;
    }

    @Override
    public Object attr(String name) {
      if (name.equals("__dict__")) return dict;
      if (descriptors.containsKey(name)) return descriptors.get(name);
      return dict.getOrDefault(name, Py.ABSENT);
    }

    @Override
    public void setAttr(String name, Object value) {
      dict.put(name, value);
    }

    /** The instance __dict__; mutation is visible to functools.wraps and callers. */
    public Map<String, Object> dict() {
      return dict;
    }

    @Override
    public Object call(List<?> arguments, Map<String, ?> keywords) {
      return call.call(arguments, keywords);
    }

    @Override
    public String pyType() {
      return pyType;
    }
  }

  private final Host host;

  public PythonCtypesExtensions(Host host) {
    this.host = host;
  }

  /**
   * Binds load_shared_library(lib_base_name, base_path) like a Python call, then delegates to
   * {@link #loadSharedLibrary(String, Path)}.
   */
  public Object loadSharedLibrary(List<?> arguments, Map<String, ?> keywords) {
    Object[] bound =
        bind(
            "load_shared_library",
            new String[] {"lib_base_name", "base_path"},
            2,
            arguments,
            keywords);
    return loadSharedLibrary((String) bound[0], (Path) bound[1]);
  }

  /** Ports load_shared_library(lib_base_name, base_path) with the original statement order. */
  public Object loadSharedLibrary(String libBaseName, Path basePath) {
    List<Path> libPaths = new ArrayList<>();
    String platform = host.platform();
    if (platform.equals("emscripten")) {
      libPaths.add(basePath.join("lib" + libBaseName + EMSCRIPTEN_SIDE_MODULE_SUFFIX));
    } else if (platform.startsWith("linux") || platform.startsWith("freebsd")) {
      libPaths.add(basePath.join("lib" + libBaseName + ".so"));
    } else if (platform.equals("darwin")) {
      libPaths.add(basePath.join("lib" + libBaseName + ".so"));
      libPaths.add(basePath.join("lib" + libBaseName + ".dylib"));
    } else if (platform.equals("win32")) {
      libPaths.add(basePath.join(libBaseName + ".dll"));
      libPaths.add(basePath.join("lib" + libBaseName + ".dll"));
    } else {
      throw Py.error(PythonErrorType.RUNTIME_ERROR, "Unsupported platform");
    }

    Map<String, Object> cdllArgs = new LinkedHashMap<>();
    Map<String, String> environ = host.environ();

    if (platform.equals("win32")) {
      host.addDllDirectory(str(basePath));
      // os.environ["PATH"] raises KeyError through the port's mapping on a missing key.
      environ.put("PATH", str(basePath) + host.pathsep() + environ.get("PATH"));
    }

    if (platform.equals("win32") && versionAtLeast(host.versionInfo(), 3, 8)) {
      host.addDllDirectory(str(basePath));
      if (environ.containsKey("CUDA_PATH")) {
        host.addDllDirectory(host.joinPath(environ.get("CUDA_PATH"), "bin"));
        host.addDllDirectory(host.joinPath(environ.get("CUDA_PATH"), "lib"));
      }
      if (environ.containsKey("HIP_PATH")) {
        host.addDllDirectory(host.joinPath(environ.get("HIP_PATH"), "bin"));
        host.addDllDirectory(host.joinPath(environ.get("HIP_PATH"), "lib"));
      }
      cdllArgs.put("winmode", host.rtldGlobal());
    }

    if (platform.equals("emscripten")) {
      cdllArgs.put("mode", host.rtldGlobal());
      String libDir = str(basePath);
      String ldLibraryPath = environ.getOrDefault("LD_LIBRARY_PATH", "");
      if (!List.of(ldLibraryPath.split(java.util.regex.Pattern.quote(host.pathsep()), -1))
          .contains(libDir)) {
        environ.put(
            "LD_LIBRARY_PATH",
            ldLibraryPath.isEmpty() ? libDir : libDir + host.pathsep() + ldLibraryPath);
      }
      for (String dependency : EMSCRIPTEN_DEPENDENCIES.getOrDefault(libBaseName, List.of())) {
        Path dependencyPath = basePath.join("lib" + dependency + EMSCRIPTEN_SIDE_MODULE_SUFFIX);
        if (dependencyPath.exists()) {
          try {
            host.loadLibrary(str(dependencyPath), cdllArgs);
          } catch (RuntimeException error) {
            throw Py.error(
                PythonErrorType.RUNTIME_ERROR,
                "Failed to load shared library '" + dependencyPath + "': " + describe(error));
          }
        }
      }
    }

    for (Path libPath : libPaths) {
      if (libPath.exists()) {
        try {
          return host.loadLibrary(str(libPath), cdllArgs);
        } catch (RuntimeException error) {
          throw Py.error(
              PythonErrorType.RUNTIME_ERROR,
              "Failed to load shared library '" + libPath + "': " + describe(error));
        }
      }
    }

    throw Py.error(
        PythonErrorType.FILE_NOT_FOUND_ERROR,
        "Shared library with base name '" + libBaseName + "' not found");
  }

  /**
   * Ports ctypes_function_for_shared_library(lib): returns the ctypes_function closure, which binds
   * (name, argtypes, restype, enabled=True) and yields the decorator closure. The decorator maps
   * enabled=False to the untouched original function and enabled truthy values to the getattr(lib,
   * name) object after argtypes/restype assignment and functools.wraps(f) copying.
   */
  public PyCallable ctypesFunctionForSharedLibrary(Object library) {
    return new CtypesFunction(library);
  }

  /** Binds ctypes_function_for_shared_library(lib) like a Python call. */
  public PyCallable ctypesFunctionForSharedLibrary(List<?> arguments, Map<String, ?> keywords) {
    Object[] bound =
        bind("ctypes_function_for_shared_library", new String[] {"lib"}, 1, arguments, keywords);
    return new CtypesFunction(bound[0]);
  }

  /** Ports _byref: the TYPE_CHECKING stub body is the Ellipsis expression, so it returns None. */
  public Object byrefStub(List<?> arguments, Map<String, ?> keywords) {
    bind("_byref", new String[] {"obj", "offset"}, 1, arguments, keywords);
    return null;
  }

  /**
   * Ports the module-level {@code byref = _byref if TYPE_CHECKING else ctypes.byref} binding: at
   * runtime the name resolves to ctypes.byref on the host port. ctypes.byref is a METH_VARARGS C
   * function, so it rejects keyword arguments and reports its own arity messages; a missing offset
   * passes 0 to the port.
   */
  public Object byref(List<?> arguments, Map<String, ?> keywords) {
    if (keywords != null && !keywords.isEmpty())
      throw PythonValues.error(PythonErrorType.TYPE_ERROR, "byref() takes no keyword arguments");
    if (arguments.isEmpty())
      throw PythonValues.error(
          PythonErrorType.TYPE_ERROR, "byref expected at least 1 argument, got 0");
    if (arguments.size() > 2)
      throw PythonValues.error(
          PythonErrorType.TYPE_ERROR,
          "byref expected at most 2 arguments, got " + arguments.size());
    return host.byref(arguments.get(0), arguments.size() > 1 ? arguments.get(1) : 0);
  }

  private final class CtypesFunction implements PyCallable {
    private final Object library;

    private CtypesFunction(Object library) {
      this.library = library;
    }

    @Override
    public Object call(List<?> arguments, Map<String, ?> keywords) {
      Object[] bound =
          bind(
              "ctypes_function_for_shared_library.<locals>.ctypes_function",
              new String[] {"name", "argtypes", "restype", "enabled"},
              3,
              arguments,
              keywords);
      return new Decorator(
          library, bound[0], bound[1], bound[2], bound[3] == MISSING ? Boolean.TRUE : bound[3]);
    }
  }

  private final class Decorator implements PyCallable {
    private final Object library;
    private final Object name;
    private final Object argtypes;
    private final Object restype;
    private final Object enabled;

    private Decorator(
        Object library, Object name, Object argtypes, Object restype, Object enabled) {
      this.library = library;
      this.name = name;
      this.argtypes = argtypes;
      this.restype = restype;
      this.enabled = enabled;
    }

    @Override
    public Object call(List<?> arguments, Map<String, ?> keywords) {
      Object[] bound =
          bind(
              "ctypes_function_for_shared_library.<locals>.ctypes_function.<locals>.decorator",
              new String[] {"f"},
              1,
              arguments,
              keywords);
      Object f = bound[0];
      if (!Py.truth(enabled)) return f;
      Object func = getAttr(library, (String) name);
      setAttr(func, "argtypes", argtypes);
      setAttr(func, "restype", restype);
      updateWrapper(func, f);
      return func;
    }
  }

  private static final Object MISSING = new Object();

  /** Binds positional and keyword arguments with the pinned CPython TypeError text. */
  private static Object[] bind(
      String function,
      String[] parameters,
      int required,
      List<?> arguments,
      Map<String, ?> keywords) {
    Object[] values = new Object[parameters.length];
    java.util.Arrays.fill(values, MISSING);
    if (arguments.size() > parameters.length) {
      String detail =
          required < parameters.length
              ? "takes from " + required + " to " + parameters.length + " positional arguments"
              : "takes "
                  + parameters.length
                  + " positional argument"
                  + (parameters.length == 1 ? "" : "s");
      throw PythonValues.error(
          PythonErrorType.TYPE_ERROR,
          function + "() " + detail + " but " + arguments.size() + " were given");
    }
    for (int index = 0; index < arguments.size(); index++) values[index] = arguments.get(index);
    if (keywords != null) {
      for (String key : keywords.keySet()) {
        int index = List.of(parameters).indexOf(key);
        if (index < 0)
          throw PythonValues.error(
              PythonErrorType.TYPE_ERROR,
              function + "() got an unexpected keyword argument '" + key + "'");
        if (values[index] != MISSING)
          throw PythonValues.error(
              PythonErrorType.TYPE_ERROR,
              function + "() got multiple values for argument '" + key + "'");
        values[index] = keywords.get(key);
      }
    }
    List<String> missing = new ArrayList<>();
    for (int index = 0; index < required; index++)
      if (values[index] == MISSING) missing.add("'" + parameters[index] + "'");
    if (!missing.isEmpty()) {
      String joined;
      if (missing.size() == 1) joined = missing.get(0);
      else if (missing.size() == 2) joined = missing.get(0) + " and " + missing.get(1);
      else
        joined =
            String.join(", ", missing.subList(0, missing.size() - 1))
                + ", and "
                + missing.get(missing.size() - 1);
      throw PythonValues.error(
          PythonErrorType.TYPE_ERROR,
          function
              + "() missing "
              + missing.size()
              + " required positional argument"
              + (missing.size() == 1 ? "" : "s")
              + ": "
              + joined);
    }
    return values;
  }

  private static Object getAttr(Object owner, String name) {
    return Py.getAttr(owner, name);
  }

  private static void setAttr(Object owner, String name, Object value) {
    if (owner instanceof MutableAttributed mutable) {
      mutable.setAttr(name, value);
      return;
    }
    throw Py.error(
        PythonErrorType.ATTRIBUTE_ERROR,
        "'" + Py.typeName(owner) + "' object has no attribute '" + name + "'");
  }

  /** functools.update_wrapper(wrapper, wrapped) for the pinned WRAPPER_ASSIGNMENTS order. */
  private static void updateWrapper(Object wrapper, Object wrapped) {
    for (String attribute : WRAPPER_ASSIGNMENTS) {
      Object value = getAttrOrAbsent(wrapped, attribute);
      if (value != Py.ABSENT) setAttr(wrapper, attribute, value);
    }
    Object dict = getAttrOrAbsent(wrapped, "__dict__");
    if (dict instanceof Map<?, ?> entries) {
      for (Map.Entry<?, ?> entry : entries.entrySet())
        setAttr(wrapper, String.valueOf(entry.getKey()), entry.getValue());
    }
    setAttr(wrapper, "__wrapped__", wrapped);
  }

  private static Object getAttrOrAbsent(Object owner, String name) {
    if (owner instanceof PyAttributed attributed) return attributed.attr(name);
    return Py.ABSENT;
  }

  /** sys.version_info >= (major, minor) tuple comparison. */
  private static boolean versionAtLeast(List<Integer> version, int major, int minor) {
    int[] required = {major, minor};
    for (int index = 0; index < required.length; index++) {
      int actual = index < version.size() ? version.get(index) : 0;
      if (actual != required[index]) return actual > required[index];
    }
    return true;
  }

  private static String str(Path path) {
    return path.toString();
  }

  private static String describe(RuntimeException error) {
    String message = error.getMessage();
    return message == null ? "" : message;
  }
}
