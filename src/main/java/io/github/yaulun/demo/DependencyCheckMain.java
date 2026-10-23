package io.github.yaulun.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Dependency diagnostics with isolated exception capture for each C01-C14 check. */
public final class DependencyCheckMain {
  private DependencyCheckMain() {}

  /** Runs checks and writes a UTF-8 check-report.txt; exit codes match the inference demo. */
  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String[] args) {
    try {
      CliArgs options =
          CliArgs.parse(args, Set.of("--help", "--quick"), Set.of("--natives", "--model"));
      if (options.has("--help")) {
        System.out.println("Usage: run-check.cmd [--quick] [--natives directory] [--model file]");
        return 0;
      }
      DemoPaths paths = DemoPaths.detect();
      Path natives = options.value("--natives").map(Path::of).orElse(paths.nativesDir());
      Path file = options.value("--model").map(Path::of).orElse(paths.defaultModel());
      return new DiagnosticRun(paths, natives, file, options.has("--quick")).run();
    } catch (IllegalArgumentException e) {
      System.err.println("参数错误: " + e.getMessage());
      return 2;
    } catch (IOException | RuntimeException | LinkageError e) {
      System.err.println("检查程序: " + e);
      return 1;
    }
  }
}
