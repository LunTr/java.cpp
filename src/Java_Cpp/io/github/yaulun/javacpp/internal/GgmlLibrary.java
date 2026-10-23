package io.github.yaulun.javacpp.internal;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/** Dynamic backend registration from ggml.dll. */
public interface GgmlLibrary extends Library {
  void ggml_backend_load_all_from_path(String directory);

  long ggml_backend_dev_count();

  Pointer ggml_backend_dev_get(long index);
}
