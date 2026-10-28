package io.github.yaulun.javacpp.compat.python;

import java.util.function.Consumer;

/** Source free_lora_adapter order: clear the reference only after successful release. */
public final class PythonLlamaResource<T> {
  private T value;

  public PythonLlamaResource(T value) {
    this.value = value;
  }

  public T value() {
    return value;
  }

  public void free(Consumer<? super T> release) {
    if (value != null) {
      release.accept(value);
      value = null;
    }
  }
}
