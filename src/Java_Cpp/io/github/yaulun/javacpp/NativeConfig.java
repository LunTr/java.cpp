package io.github.yaulun.javacpp;

import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.NativeLibraryException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable runtime configuration. A JVM owns one native directory for its lifetime. */
public final class NativeConfig {
  private final Path nativesDir;
  private final LogLevel logLevel;
  private final NativeLogListener logListener;

  private NativeConfig(Builder builder) {
    String configured = System.getProperty("javacpp.natives", System.getenv("JAVACPP_NATIVES"));
    Path selected =
        builder.nativesDir != null
            ? builder.nativesDir
            : configured == null || configured.isBlank() ? null : Path.of(configured);
    nativesDir = selected == null ? null : selected.toAbsolutePath().normalize();
    logLevel = builder.logLevel;
    logListener = builder.logListener;
  }

  /** Creates a runtime configuration builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Uses system-property then environment-variable native directory configuration. */
  public static NativeConfig defaults() {
    return builder().build();
  }

  /** Returns the configured directory or E102 when no directory was selected. */
  public Path nativesDir() {
    if (nativesDir == null) {
      throw new NativeLibraryException(ErrorCode.E102, "No native directory configured", List.of());
    }
    return nativesDir;
  }

  /** Returns the minimum severity delivered to the native log observer. */
  public LogLevel logLevel() {
    return logLevel;
  }

  /** Returns the optional native log observer; null retains diagnostic lines only. */
  public NativeLogListener logListener() {
    return logListener;
  }

  /** Builder for runtime options. Native ABI checks always remain enabled. */
  public static final class Builder {
    private Path nativesDir;
    private LogLevel logLevel = LogLevel.WARN;
    private NativeLogListener logListener;

    private Builder() {}

    /** Sets the directory containing llama.dll and its dependencies. */
    public Builder nativesDir(Path value) {
      nativesDir = Objects.requireNonNull(value);
      return this;
    }

    /** Sets the minimum severity delivered to the listener. */
    public Builder logLevel(LogLevel value) {
      logLevel = Objects.requireNonNull(value);
      return this;
    }

    /** Sets a listener; null retains only the recent diagnostic log. */
    public Builder logListener(NativeLogListener value) {
      logListener = value;
      return this;
    }

    /** Returns an immutable configuration. */
    public NativeConfig build() {
      return new NativeConfig(this);
    }
  }
}
