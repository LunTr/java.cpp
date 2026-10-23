package io.github.yaulun.demo;

import com.sun.jna.Native;
import io.github.yaulun.demo.download.DownloadSource;
import io.github.yaulun.demo.download.ModelCatalog;
import io.github.yaulun.demo.download.ModelDownloader;
import io.github.yaulun.demo.download.ModelSpec;
import io.github.yaulun.javacpp.ChatReply;
import io.github.yaulun.javacpp.ChatSession;
import io.github.yaulun.javacpp.ChatTemplate;
import io.github.yaulun.javacpp.ContextParams;
import io.github.yaulun.javacpp.GenerationConfig;
import io.github.yaulun.javacpp.GgufHeader;
import io.github.yaulun.javacpp.JavaCpp;
import io.github.yaulun.javacpp.LlamaContext;
import io.github.yaulun.javacpp.LlamaModel;
import io.github.yaulun.javacpp.LlamaRuntime;
import io.github.yaulun.javacpp.ModelParams;
import io.github.yaulun.javacpp.NativeConfig;
import io.github.yaulun.javacpp.NativeLayout;
import io.github.yaulun.javacpp.Sampler;
import io.github.yaulun.javacpp.TokenListener;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class DiagnosticRun {
  private enum Status {
    PASS,
    WARN,
    FAIL,
    SKIP
  }

  private record Result(String id, String title, Status status, String detail, long nanos) {}

  @FunctionalInterface
  private interface Probe {
    String run() throws Exception;
  }

  private final DemoPaths paths;
  private final Path natives;
  private final Path file;
  private final boolean quick;
  private final Map<String, Result> results = new LinkedHashMap<>();
  private LlamaRuntime runtime;
  private LlamaModel model;
  private boolean modelReady;

  DiagnosticRun(DemoPaths paths, Path natives, Path file, boolean quick) {
    this.paths = paths;
    this.natives = natives;
    this.file = file;
    this.quick = quick;
  }

  int run() throws IOException {
    try {
      check("C01", "操作系统", List.of(), false, () -> NativeLayout.current().toString());
      check(
          "C02",
          "Java",
          List.of(),
          false,
          () -> {
            if (Runtime.version().feature() < 21 || Native.POINTER_SIZE != 8) {
              throw new IllegalStateException("JDK 21 x64 required");
            }
            return System.getProperty("java.runtime.version");
          });
      check(
          "C03",
          "控制台编码",
          List.of(),
          false,
          () -> {
            String encoding = System.out.charset().name();
            return encoding.equalsIgnoreCase("UTF-8")
                ? "UTF-8: 中文🙂"
                : "WARN: stdout=" + encoding + "; use run-*.cmd";
          });
      check(
          "C04",
          "JNA",
          List.of("C02"),
          false,
          () -> "JNA " + Native.VERSION + ", pointer=" + Native.POINTER_SIZE);
      check("C05", "VC++ 运行库", List.of("C01"), false, this::checkVcRuntime);
      check(
          "C06",
          "natives 文件",
          List.of(),
          false,
          () -> {
            NativeLayout layout = NativeLayout.current();
            if (!Files.isDirectory(natives)) {
              throw new IllegalStateException("Native directory missing: " + natives);
            }
            List<String> missing = layout.missingRequired(natives);
            if (!missing.isEmpty() || layout.cpuBackends(natives).isEmpty()) {
              throw new IllegalStateException(
                  "Missing="
                      + missing
                      + "; CPU="
                      + layout.cpuBackends(natives)
                      + "; nested="
                      + layout.findNestedFolder(natives));
            }
            return Files.isRegularFile(natives.resolve("ggml-cuda.dll"))
                ? natives.toString()
                : "WARN: CPU package: " + natives;
          });
      check(
          "C07",
          "加载、版本与 ABI",
          List.of("C04", "C05", "C06"),
          false,
          () -> {
            runtime = LlamaRuntime.initialize(NativeConfig.builder().nativesDir(natives).build());
            return runtime.versions() + "\n" + runtime.systemInfo();
          });
      check(
          "C08",
          "计算设备",
          List.of("C07"),
          false,
          () -> {
            if (!runtime.hasGpu() && Files.isRegularFile(natives.resolve("ggml-cuda.dll"))) {
              throw new IllegalStateException(
                  "CUDA unavailable: " + runtime.diagnoseLibrary("ggml-cuda.dll"));
            }
            return (runtime.hasGpu() ? "" : "WARN: CPU selected\n") + runtime.devices();
          });
      check("C09", "NVIDIA 驱动", List.of(), false, this::checkDriver);
      check(
          "C10",
          "模型文件",
          List.of(),
          false,
          () -> {
            if (!Files.isRegularFile(file)) {
              return "WARN: Model download pending: " + file;
            }
            GgufHeader header = GgufHeader.read(file);
            String digest = quick ? "omitted (--quick)" : ModelDownloader.sha256(file);
            ModelSpec expected =
                List.of(ModelCatalog.Q8_0, ModelCatalog.Q4_K_M).stream()
                    .filter(
                        spec ->
                            file.getFileName().toString().equals(spec.fileName())
                                && header.fileSize() == spec.sizeBytes())
                    .findFirst()
                    .orElse(null);
            if (expected != null && !quick && !digest.equals(expected.sha256())) {
              throw new IllegalStateException(
                  "SHA-256 mismatch: expected=" + expected.sha256() + ", actual=" + digest);
            }
            modelReady = true;
            return (expected == null
                    ? "WARN: Local GGUF revision differs from pinned download catalog\n"
                    : "")
                + header
                + "\nSHA-256 "
                + digest
                + "\nActual loading is checked by C13; managed downloads require the pinned digest.";
          });
      check(
          "C11",
          "磁盘空间",
          List.of(),
          false,
          () -> {
            Path existing = paths.modelsDir();
            while (!Files.exists(existing)) {
              existing = existing.getParent();
            }
            long bytes = Files.getFileStore(existing).getUsableSpace();
            if (bytes < 2L * 1024 * 1024 * 1024) {
              if (Files.exists(file)) {
                return "WARN: Available bytes=" + bytes;
              }
              throw new IllegalStateException("Less than 2 GiB available: " + bytes);
            }
            return "Available GiB=" + bytes / (1024 * 1024 * 1024);
          });
      check("C12", "模型源网络", List.of(), quick, this::checkNetwork);
      check(
          "C13",
          "冒烟推理",
          List.of("C07", "C10"),
          quick || !modelReady,
          () -> {
            LlamaModel loaded = sharedModel();
            try (LlamaContext context =
                    loaded.newContext(ContextParams.builder().contextLength(512).build());
                Sampler sampler = Sampler.greedy(loaded.vocab())) {
              ChatReply reply =
                  new ChatSession(context, sampler, ChatTemplate.fromModel(loaded))
                      .generationConfig(new GenerationConfig(16, null))
                      .send("你好，请用一句话介绍自己。", TokenListener.NONE);
              return loaded.info().summary()
                  + "\n"
                  + reply.content()
                  + "\n"
                  + reply.stats().summary();
            }
          });
      check(
          "C14",
          "异常自测",
          List.of("C07", "C10"),
          quick || !modelReady,
          () -> RuntimeSelfTest.run(sharedModel()));
    } finally {
      if (model != null) {
        model.close();
      }
    }
    StringBuilder report =
        new StringBuilder(JavaCpp.banner())
            .append('\n')
            .append(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))
            .append('\n');
    for (Result result : results.values()) {
      report
          .append('[')
          .append(result.status())
          .append("] ")
          .append(result.id())
          .append(' ')
          .append(result.title())
          .append(" (")
          .append(result.nanos() / 1_000_000)
          .append(" ms)\n")
          .append(result.detail())
          .append("\n\n");
    }
    String summary =
        Arrays.stream(Status.values())
            .map(
                status ->
                    status
                        + "="
                        + results.values().stream()
                            .filter(result -> result.status() == status)
                            .count())
            .collect(Collectors.joining(" "));
    report.append(summary).append('\n');
    Files.writeString(paths.reportFile(), report, StandardCharsets.UTF_8);
    System.out.println(summary + "\nReport: " + paths.reportFile());
    return results.values().stream().anyMatch(result -> result.status() == Status.FAIL) ? 1 : 0;
  }

  private void check(
      String id, String title, List<String> dependencies, boolean skip, Probe probe) {
    long start = System.nanoTime();
    Status status;
    String detail;
    boolean blocked =
        dependencies.stream()
            .anyMatch(
                dependency ->
                    !results.containsKey(dependency)
                        || results.get(dependency).status() == Status.FAIL
                        || results.get(dependency).status() == Status.SKIP);
    if (skip || blocked) {
      status = Status.SKIP;
      detail =
          skip ? "Omitted by --quick or model pending" : "Prerequisite failed: " + dependencies;
    } else {
      try {
        detail = probe.run();
        status = detail.startsWith("WARN:") ? Status.WARN : Status.PASS;
      } catch (Throwable failure) {
        status = Status.FAIL;
        detail = failure.toString();
        if (failure.getCause() != null) {
          detail += "\nCause: " + failure.getCause();
        }
      }
    }
    Result result = new Result(id, title, status, detail, System.nanoTime() - start);
    results.put(id, result);
    System.out.println(
        "[" + status + "] " + id + " " + title + "  " + detail.lines().findFirst().orElse(""));
  }

  private LlamaModel sharedModel() {
    if (model == null) {
      model =
          LlamaModel.load(file, ModelParams.builder().gpuLayers(runtime.hasGpu() ? -1 : 0).build());
    }
    return model;
  }

  private String checkVcRuntime() throws IOException {
    Path system = Path.of(System.getenv("SystemRoot"), "System32");
    Path cpp = system.resolve("msvcp140.dll");
    if (!Files.isRegularFile(cpp) || !Files.isRegularFile(system.resolve("vcruntime140_1.dll"))) {
      throw new IllegalStateException(
          "Install VC++ x64: https://aka.ms/vs/17/release/vc_redist.x64.exe");
    }
    String version =
        PeVersionReader.fileVersion(cpp)
            .orElseThrow(() -> new IllegalStateException("Cannot read VC++ version"));
    if (PeVersionReader.compare(version, "14.40") < 0) {
      throw new IllegalStateException("VC++ requires >=14.40, found " + version);
    }
    return version;
  }

  private String checkDriver() throws IOException, InterruptedException {
    Process process;
    try {
      process =
          new ProcessBuilder(
                  "nvidia-smi",
                  "--query-gpu=name,driver_version,memory.total",
                  "--format=csv,noheader")
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      return "WARN: nvidia-smi unavailable: " + e.getMessage();
    }
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      return "WARN: nvidia-smi timed out";
    }
    String text =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    if (process.exitValue() != 0) {
      return "WARN: " + text;
    }
    for (String line : text.lines().toList()) {
      String[] fields = line.split(",");
      if (fields.length >= 2) {
        if (PeVersionReader.compare(fields[1].strip(), "528.33") < 0) {
          throw new IllegalStateException(text);
        }
        if (PeVersionReader.compare(fields[1].strip(), "551.61") < 0) {
          return "WARN: " + text;
        }
      }
    }
    return text;
  }

  private String checkNetwork() throws InterruptedException {
    List<String> attempts = new ArrayList<>();
    try (HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()) {
      for (DownloadSource source : DownloadSource.values()) {
        try {
          HttpRequest request =
              HttpRequest.newBuilder(source.uriFor(ModelCatalog.Q8_0))
                  .timeout(Duration.ofSeconds(8))
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build();
          int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
          if (status >= 200 && status < 400) {
            return source + " HTTP " + status;
          }
          attempts.add(source + " HTTP " + status);
        } catch (IOException e) {
          attempts.add(source + ": " + e.getMessage());
        }
      }
    }
    if (Files.isRegularFile(file)) {
      return "WARN: Local model available; " + attempts;
    }
    throw new IllegalStateException("Download mirrors unavailable: " + attempts);
  }
}
