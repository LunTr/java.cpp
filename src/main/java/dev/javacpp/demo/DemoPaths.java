package dev.javacpp.demo;

import dev.javacpp.demo.download.ModelCatalog;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
    return new DemoPaths(
        root,
        configured("javacpp.natives", "JAVACPP_NATIVES", pickNativesDir(root)),
        configured("javacpp.models", "JAVACPP_MODELS", root.resolve("gguf")));
  }

  /**
   * Prefers a natives subdirectory whose name marks a GPU backend, else the first subdirectory in
   * name order, else the natives directory itself. Version and platform specific directory names
   * are intentionally not baked in.
   */
  private static Path pickNativesDir(Path root) {
    Path natives = root.resolve("natives");
    List<Path> dirs;
    try (java.util.stream.Stream<Path> stream = Files.list(natives)) {
      dirs = stream.filter(Files::isDirectory).sorted().toList();
    } catch (IOException | java.io.UncheckedIOException failure) {
      return natives;
    }
    for (Path dir : dirs) {
      if (dir.getFileName().toString().toLowerCase(Locale.ROOT).contains("cuda")) {
        return dir;
      }
    }
    return dirs.isEmpty() ? natives : dirs.get(0);
  }

  Path defaultModel() {
    return modelsDir.resolve(ModelCatalog.Q8_0.fileName());
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
