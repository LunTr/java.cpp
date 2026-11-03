package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Ports llama_cpp_ext._ctypes_function_from_names: iterate candidate symbol names, getattr each on
 * the library port collecting AttributeError misses, assign argtypes/restype on the first hit,
 * wraps it in the decorated function, otherwise raise the joined missing-symbol AttributeError.
 */
public final class PythonCtypesExt {
  private PythonCtypesExt() {}

  /** The loaded shared library; getattr misses throw AttributeError like ctypes.__getattr__. */
  public interface Lib {
    Object getattr(String name) throws PythonException;
  }

  /** Ports the outer call: returns a decorator function. */
  public static Function<Object, Object> ctypesFunctionFromNames(
      Lib lib, List<String> names, Object argtypes, Object restype) {
    return f -> {
      List<String> missing = new ArrayList<>();
      for (String name : names) {
        Object func;
        try {
          func = lib.getattr(name);
        } catch (PythonException failure) {
          if (failure.type() == PythonErrorType.ATTRIBUTE_ERROR) {
            missing.add(name);
            continue;
          }
          throw failure;
        }
        PythonCtypesExtensions.setAttr(func, "argtypes", argtypes);
        PythonCtypesExtensions.setAttr(func, "restype", restype);
        PythonCtypesExtensions.wraps(func, f);
        return func;
      }
      throw Py.error(
          PythonErrorType.ATTRIBUTE_ERROR,
          "None of the shared library symbols were found: " + String.join(", ", missing));
    };
  }
}
