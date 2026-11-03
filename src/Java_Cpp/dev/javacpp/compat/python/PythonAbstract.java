package dev.javacpp.compat.python;

import dev.javacpp.python.Py;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared ABCMeta instantiation rule: a subclass that still inherits any abstract method from the
 * given base class fails construction with the original TypeError listing the missing names in
 * sorted order. Bodies that simply raise NotImplementedError stay callable through super.
 */
final class PythonAbstract {
  private PythonAbstract() {}

  /** Java method name to Python method name and declared parameter types. */
  record Method(String javaName, String pythonName, Class<?>... parameters) {}

  static Method method(String javaName, String pythonName, Class<?>... parameters) {
    return new Method(javaName, pythonName, parameters);
  }

  /** Runs in a base-class constructor; throws TypeError when methods remain uninstantiated. */
  static void checkInstance(Object instance, Class<?> base, String typeName, Method... methods) {
    List<String> missing =
        java.util.Arrays.stream(methods)
            .filter(method -> inherited(instance.getClass(), base, method))
            .map(Method::pythonName)
            .sorted()
            .collect(Collectors.toList());
    if (!missing.isEmpty()) {
      throw Py.abstractError(typeName, missing);
    }
  }

  private static boolean inherited(Class<?> owner, Class<?> base, Method method) {
    try {
      return owner.getMethod(method.javaName(), method.parameters()).getDeclaringClass() == base;
    } catch (NoSuchMethodException failure) {
      throw new AssertionError(failure);
    }
  }
}
