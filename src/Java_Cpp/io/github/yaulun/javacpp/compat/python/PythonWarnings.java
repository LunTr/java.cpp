package io.github.yaulun.javacpp.compat.python;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Per-instance warning registry with Python default filters or one explicit standard action. */
public final class PythonWarnings {
  private record Site(String message, PythonErrorType category, String module, int line) {}

  private final PythonWarningAction action;
  private final boolean defaultFilters;
  private final Consumer<PythonWarning> listener;
  private final Set<Site> emitted = new HashSet<>();

  private PythonWarnings(
      PythonWarningAction action, boolean defaultFilters, Consumer<PythonWarning> listener) {
    this.action = Objects.requireNonNull(action, "action");
    this.defaultFilters = defaultFilters;
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /** Uses Python defaults for supported categories, ignoring imported-module deprecations. */
  public static PythonWarnings defaults(Consumer<PythonWarning> listener) {
    return new PythonWarnings(PythonWarningAction.DEFAULT, true, listener);
  }

  /** Applies an explicit action, analogous to simplefilter(action) for supported categories. */
  public static PythonWarnings withAction(
      PythonWarningAction action, Consumer<PythonWarning> listener) {
    return new PythonWarnings(action, false, listener);
  }

  /** Emits, suppresses or raises a warning using this registry; listener exceptions propagate. */
  public synchronized void warn(PythonWarning warning) {
    Objects.requireNonNull(warning, "warning");
    if (action == PythonWarningAction.IGNORE
        || (defaultFilters
            && warning.category() == PythonErrorType.DEPRECATION_WARNING
            && !warning.location().module().equals("__main__"))) {
      return;
    }
    if (action == PythonWarningAction.ERROR) {
      throw new PythonException(warning.category(), warning.message(), null);
    }
    // CPython 3.13 warn/warn_explicit with module registries keeps once keys per module.
    String module = warning.location().module();
    int line =
        action == PythonWarningAction.MODULE || action == PythonWarningAction.ONCE
            ? 0
            : warning.location().lineNumber();
    Site site = new Site(warning.message(), warning.category(), module, line);
    if (action == PythonWarningAction.ALWAYS || emitted.add(site)) {
      listener.accept(warning);
    }
  }

  /** Emits the original llama-cpp-python legacy-symbol deprecation message. */
  public void deprecated(String symbol, String hint, PythonWarningLocation location) {
    warn(
        new PythonWarning(
            PythonErrorType.DEPRECATION_WARNING,
            Objects.requireNonNull(symbol) + " is deprecated; " + Objects.requireNonNull(hint),
            location));
  }

  /** Emits the original duplicate-BOS RuntimeWarning message. */
  public void duplicateBos(String bosText, PythonWarningLocation location) {
    warn(
        new PythonWarning(
            PythonErrorType.RUNTIME_WARNING,
            "Detected duplicate leading \""
                + Objects.requireNonNull(bosText)
                + "\" in prompt, this will likely reduce response quality, consider removing it...",
            location));
  }

  /** Clears previously emitted sites, analogous to clearing module warning registries. */
  public synchronized void clearRegistry() {
    emitted.clear();
  }
}
