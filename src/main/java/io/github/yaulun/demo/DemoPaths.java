package io.github.yaulun.demo;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

record DemoPaths(Path root, Path nativesDir, Path modelsDir) {
  static DemoPaths detect() {
    Path start = Path.of("").toAbsolutePath();
    Path root = findRoot(start);
    if (root == null) {
      try {
        root =
            findRoot(
                Path.of(
                    DemoPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
      } catch (URISyntaxException e) {
        throw new IllegalStateException("Cannot locate demo jar", e);
      }
    }
    if (root == null) {
      root = start;
    }
    Path cpu = root.resolve("natives/b11669-cpu");
    Path cuda = root.resolve("natives/llama-b11669-win-cuda-12.4-x64");
    Path nativeDefault = Files.isDirectory(cuda) ? cuda : cpu;
    return new DemoPaths(
        root,
        configured("javacpp.natives", "JAVACPP_NATIVES", nativeDefault),
        configured("javacpp.models", "JAVACPP_MODELS", root.resolve("gguf")));
  }

  Path defaultModel() {
    return modelsDir.resolve("Qwen3.5-0.8B-Q8_0.gguf");
  }

  Path reportFile() {
    return root.resolve("check-report.txt");
  }

  private static Path configured(String property, String environment, Path fallback) {
    String value = System.getProperty(property, System.getenv(environment));
    return (value == null || value.isBlank() ? fallback : Path.of(value))
        .toAbsolutePath()
        .normalize();
  }

  private static Path findRoot(Path path) {
    for (Path current = path; current != null; current = current.getParent()) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("src/Java_Cpp"))) {
        return current;
      }
    }
    return null;
  }
}
