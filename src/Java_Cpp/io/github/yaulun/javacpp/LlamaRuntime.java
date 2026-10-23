package io.github.yaulun.javacpp;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.NativeLibraryException;
import io.github.yaulun.javacpp.internal.LlamaLibrary;
import io.github.yaulun.javacpp.internal.NativeLibraries;
import io.github.yaulun.javacpp.internal.NativeLoader;
import io.github.yaulun.javacpp.internal.NativeLogBridge;
import io.github.yaulun.javacpp.internal.StructSelfTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Process-lifetime singleton. Initialization verifies version, symbols and ABI before use. */
public final class LlamaRuntime {
  private static volatile LlamaRuntime instance;
  private static Path boundDirectory;
  private static boolean backendsLoaded;
  private final NativeLibraries libraries;
  private final NativeVersions versions;
  private final NativeLayout layout;

  private LlamaRuntime(NativeLibraries libraries, NativeVersions versions, NativeLayout layout) {
    this.libraries = libraries;
    this.versions = versions;
    this.layout = layout;
  }

  /** Initializes once; a later directory change raises IllegalStateException. */
  public static LlamaRuntime initialize(NativeConfig config) {
    NativeLogBridge.ensureOutsideCallback();
    return initializeLocked(config);
  }

  private static synchronized LlamaRuntime initializeLocked(NativeConfig config) {
    Objects.requireNonNull(config, "config");
    Path directory = config.nativesDir();
    NativeLayout layout = NativeLayout.current();
    if (boundDirectory != null && !boundDirectory.equals(directory)) {
      throw new IllegalStateException("JVM already bound to " + boundDirectory);
    }
    if (instance != null) {
      return instance;
    }
    // A partially loaded DLL set also binds the process; changing sets needs a fresh JVM.
    if (Files.isDirectory(directory)
        && layout.missingRequired(directory).isEmpty()
        && !layout.cpuBackends(directory).isEmpty()) {
      boundDirectory = directory;
    }
    NativeLibraries libraries = NativeLoader.load(directory, layout);
    NativeLogBridge log = NativeLogBridge.instance();
    log.configure(config.logLevel(), config.logListener());
    libraries.llama().llama_log_set(log, null);
    NativeVersions versions =
        new NativeVersions(
            libraries.llama().llama_version(),
            libraries.base().ggml_version(),
            libraries.base().ggml_commit(),
            JavaCpp.LLAMA_CPP_VERSION,
            JavaCpp.LLAMA_CPP_COMMIT);
    if (!versions.matchesExpected()) {
      throw new NativeLibraryException(ErrorCode.E106, versions.toString(), log.recentProblems());
    }
    List<String> problems = StructSelfTest.verify(libraries.llama());
    if (!problems.isEmpty()) {
      throw new NativeLibraryException(ErrorCode.E106, problems.toString(), log.recentProblems());
    }
    if (!backendsLoaded) {
      libraries.ggml().ggml_backend_load_all_from_path(directory.toString());
      libraries.llama().llama_backend_init();
      backendsLoaded = true;
    }
    LlamaRuntime candidate = new LlamaRuntime(libraries, versions, layout);
    if (candidate.devices().stream().noneMatch(device -> device.type() == DeviceType.CPU)) {
      throw new NativeLibraryException(ErrorCode.E107, "CPU backend missing", log.recentProblems());
    }
    instance = candidate;
    return instance;
  }

  /** Returns the initialized runtime or raises IllegalStateException. */
  public static LlamaRuntime get() {
    NativeLogBridge.ensureOutsideCallback();
    if (instance == null) {
      throw new IllegalStateException("Call LlamaRuntime.initialize first");
    }
    return instance;
  }

  /** Returns the initialized runtime if available, without initializing it. */
  public static Optional<LlamaRuntime> current() {
    NativeLogBridge.ensureOutsideCallback();
    return Optional.ofNullable(instance);
  }

  /** Returns the verified native version snapshot. */
  public NativeVersions versions() {
    return versions;
  }

  /** Returns the absolute normalized native library directory. */
  public Path nativesDir() {
    return libraries.directory();
  }

  /** Returns the native file layout selected for this process. */
  public NativeLayout layout() {
    return layout;
  }

  /** Queries device memory afresh and returns an immutable list. */
  public List<BackendDevice> devices() {
    NativeLogBridge.ensureOutsideCallback();
    List<BackendDevice> devices = new ArrayList<>();
    long count = libraries.ggml().ggml_backend_dev_count();
    for (int index = 0; index < count; index++) {
      Pointer device = libraries.ggml().ggml_backend_dev_get(index);
      LongByReference free = new LongByReference();
      LongByReference total = new LongByReference();
      libraries.base().ggml_backend_dev_memory(device, free, total);
      devices.add(
          new BackendDevice(
              index,
              libraries.base().ggml_backend_dev_name(device),
              libraries.base().ggml_backend_dev_description(device),
              DeviceType.fromNative(libraries.base().ggml_backend_dev_type(device)),
              free.getValue(),
              total.getValue()));
    }
    return List.copyOf(devices);
  }

  /** Returns whether any registered device is a discrete or integrated GPU. */
  public boolean hasGpu() {
    return devices().stream().anyMatch(BackendDevice::isGpu);
  }

  /** Returns whether the native build supports GPU layer offload. */
  public boolean supportsGpuOffload() {
    NativeLogBridge.ensureOutsideCallback();
    return libraries.llama().llama_supports_gpu_offload() != 0;
  }

  /** Returns native CPU feature and backend build information. */
  public String systemInfo() {
    NativeLogBridge.ensureOutsideCallback();
    return libraries.llama().llama_print_system_info();
  }

  /** Probes a filename in the configured directory; rejects paths outside that directory. */
  public String diagnoseLibrary(String fileName) {
    Checks.text(fileName, "fileName");
    Path file = libraries.directory().resolve(fileName).normalize();
    if (!Objects.equals(file.getParent(), libraries.directory())) {
      throw new IllegalArgumentException("fileName must be a filename in the native directory");
    }
    return NativeLoader.probe(file);
  }

  /** Returns an immutable snapshot of up to forty native diagnostic lines. */
  public List<String> recentNativeProblems() {
    return NativeLogBridge.instance().recentProblems();
  }

  LlamaLibrary library() {
    return libraries.llama();
  }
}
