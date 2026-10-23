package io.github.yaulun.javacpp;

import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.NativeLibraryException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Supported native layout; this release targets Windows x64. */
public enum NativeLayout {
  WINDOWS_X64;

  /** Detects the current layout or raises E101 before loading any DLL. */
  public static NativeLayout current() {
    String arch = System.getProperty("os.arch");
    if (!System.getProperty("os.name").startsWith("Windows")
        || !(arch.equals("amd64") || arch.equals("x86_64"))) {
      throw new NativeLibraryException(
          ErrorCode.E101, System.getProperty("os.name") + " / " + arch, List.of());
    }
    return WINDOWS_X64;
  }

  /** Returns the core Windows x64 library filenames. */
  public List<String> requiredFiles() {
    return List.of("ggml-base.dll", "ggml.dll", "llama.dll", "libomp.dll");
  }

  /** Returns the filenames required by the optional CUDA 12.4 backend. */
  public List<String> cudaFiles() {
    return List.of("ggml-cuda.dll", "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll");
  }

  /** Returns missing regular files, without searching other directories. */
  public List<String> missingRequired(Path dir) {
    return requiredFiles().stream()
        .filter(name -> !Files.isRegularFile(dir.resolve(name)))
        .toList();
  }

  /** Lists CPU backend files in this directory only. */
  public List<Path> cpuBackends(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (var files = Files.list(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().matches("ggml-cpu-.*\\.dll"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Detects a common extra directory level using one shallow directory listing. */
  public Optional<Path> findNestedFolder(Path dir) {
    if (!Files.isDirectory(dir)) {
      return Optional.empty();
    }
    try (var files = Files.list(dir)) {
      return files
          .filter(Files::isDirectory)
          .filter(path -> Files.isRegularFile(path.resolve("llama.dll")))
          .findFirst();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
