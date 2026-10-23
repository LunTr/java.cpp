package io.github.yaulun.javacpp.internal;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

/** Version and backend device operations exported by ggml-base.dll. */
public interface GgmlBaseLibrary extends Library {
  String ggml_version();

  String ggml_commit();

  String ggml_backend_dev_name(Pointer device);

  String ggml_backend_dev_description(Pointer device);

  int ggml_backend_dev_type(Pointer device);

  void ggml_backend_dev_memory(Pointer device, LongByReference free, LongByReference total);
}
