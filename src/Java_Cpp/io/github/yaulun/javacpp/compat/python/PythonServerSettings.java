package io.github.yaulun.javacpp.compat.python;

import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Ports server/settings.py set_dynamic_defaults: n_threads / n_threads_batch of -1 are replaced
 * with multiprocessing.cpu_count() (an injectable supplier); the values map is mutated in place.
 */
public final class PythonServerSettings {
  private PythonServerSettings() {}

  public static Map<String, Object> setDynamicDefaults(
      Map<String, Object> values, IntSupplier cpuCount) {
    Objects.requireNonNull(values);
    int cpus = cpuCount.getAsInt();
    if (Objects.equals(values.getOrDefault("n_threads", 0), -1)) {
      values.put("n_threads", cpus);
    }
    if (Objects.equals(values.getOrDefault("n_threads_batch", 0), -1)) {
      values.put("n_threads_batch", cpus);
    }
    return values;
  }
}
