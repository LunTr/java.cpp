package io.github.yaulun.javacpp.internal;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import io.github.yaulun.javacpp.NativeLayout;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.NativeLibraryException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates and loads a complete Windows library set in dependency order. */
public final class NativeLoader {
  private static final Map<String, ?> OPTIONS = Map.of(Library.OPTION_STRING_ENCODING, "UTF-8");

  private NativeLoader() {}

  /** Loads symbols only; no struct-returning calls occur before runtime version verification. */
  public static NativeLibraries load(Path directory, NativeLayout layout) {
    NativeLogBridge.ensureOutsideCallback();
    if (!Files.isDirectory(directory)) {
      throw failure(ErrorCode.E102, directory.toString(), null);
    }
    List<String> missing = layout.missingRequired(directory);
    if (!missing.isEmpty()) {
      String nested =
          layout.findNestedFolder(directory).map(path -> "; use directory " + path).orElse("");
      throw failure(ErrorCode.E103, missing + nested, null);
    }
    if (layout.cpuBackends(directory).isEmpty()) {
      throw failure(ErrorCode.E107, "No ggml-cpu-*.dll in " + directory, null);
    }
    if (Native.POINTER_SIZE != 8) {
      throw failure(ErrorCode.E101, "64-bit JDK required", null);
    }
    if (!kernel().SetDllDirectoryW(new WString(directory.toString()))) {
      throw failure(ErrorCode.E104, "SetDllDirectoryW error=" + Native.getLastError(), null);
    }
    GgmlBaseLibrary base = bind(directory.resolve("ggml-base.dll"), GgmlBaseLibrary.class);
    GgmlLibrary ggml = bind(directory.resolve("ggml.dll"), GgmlLibrary.class);
    LlamaLibrary llama = bind(directory.resolve("llama.dll"), LlamaLibrary.class);
    return new NativeLibraries(directory, llama, ggml, base);
  }

  private static <T extends Library> T bind(Path file, Class<T> type) {
    try {
      NativeLibrary library = NativeLibrary.getInstance(file.toString(), OPTIONS);
      List<String> missing = new ArrayList<>();
      for (Method method : type.getDeclaredMethods()) {
        try {
          library.getFunction(method.getName());
        } catch (UnsatisfiedLinkError e) {
          missing.add(method.getName());
        }
      }
      if (!missing.isEmpty()) {
        throw failure(ErrorCode.E105, file + ": " + missing, null);
      }
      return Native.load(file.toString(), type, OPTIONS);
    } catch (UnsatisfiedLinkError e) {
      throw failure(ErrorCode.E104, file + ": " + e.getMessage() + "; " + probe(file), e);
    }
  }

  /** Tries a DLL independently and immediately balances a successful loader reference. */
  public static String probe(Path file) {
    NativeLogBridge.ensureOutsideCallback();
    Pointer module =
        kernel().LoadLibraryExW(new WString(file.toAbsolutePath().toString()), null, 8);
    if (module != null) {
      if (!kernel().FreeLibrary(module)) {
        return file + ": FreeLibrary failed, Windows error " + Native.getLastError();
      }
      return file + ": loadable";
    }
    int error = Native.getLastError();
    String detail =
        switch (error) {
          case 126 -> "dependency missing; check VC++ runtime, libomp and CUDA runtime files";
          case 127 -> "dependency export missing; check runtime versions and matching build";
          case 193 -> "architecture or file format mismatch; use x64 libraries and JDK";
          case 1114 -> "initialization failed; check driver and runtime versions";
          default -> "check the Windows loader dependencies";
        };
    return file + ": Windows error " + error + ", " + detail;
  }

  private static Kernel32Ext kernel() {
    return Native.load("kernel32", Kernel32Ext.class);
  }

  private static NativeLibraryException failure(ErrorCode code, String detail, Throwable cause) {
    return new NativeLibraryException(
        code, detail, NativeLogBridge.instance().recentProblems(), cause);
  }
}
