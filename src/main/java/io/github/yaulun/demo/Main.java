package io.github.yaulun.demo;

import java.util.Arrays;

/** Command line entry: --check runs diagnostics; other options start local chat. */
public final class Main {
  private Main() {}

  /** Delegates to a diagnostic or inference main with explicit process exit codes. */
  public static void main(String[] args) {
    if (Arrays.asList(args).contains("--check")) {
      DependencyCheckMain.main(
          Arrays.stream(args).filter(arg -> !arg.equals("--check")).toArray(String[]::new));
    } else {
      InferenceDemoMain.main(args);
    }
  }
}
