package io.github.yaulun.javacpp.compat.python;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Ports _utils.MetaSingleton/Singleton: one shared instance registry keyed by class. call() runs
 * the supplied constructor (the super().__call__ equivalent, which receives all Python *args and
 * **kwargs) only on the first request for each class; constructor exceptions propagate and leave
 * the class unregistered.
 */
public class PythonSingleton {
  private static final Map<Class<?>, Object> INSTANCES = new ConcurrentHashMap<>();

  /** Ports Singleton.__init__: only chains to super().__init__. */
  protected PythonSingleton() {}

  /** Ports MetaSingleton.__call__; the factory receives the original call arguments. */
  @SuppressWarnings("unchecked")
  public static <T> T call(Class<T> cls, Supplier<? extends T> constructor) {
    return (T) INSTANCES.computeIfAbsent(Objects.requireNonNull(cls), key -> constructor.get());
  }

  /** Test hook mirroring a fresh interpreter registry. */
  public static void clearInstances() {
    INSTANCES.clear();
  }
}
