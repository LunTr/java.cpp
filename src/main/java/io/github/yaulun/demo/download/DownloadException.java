package io.github.yaulun.demo.download;

import java.util.List;

/** Download failure retaining all mirror/retry diagnostics. */
public final class DownloadException extends RuntimeException {
  private final List<String> attempts;

  /** Creates an immutable failure summary with the original cause. */
  public DownloadException(List<String> attempts, Throwable cause) {
    super("Download failed:\n  " + String.join("\n  ", attempts), cause);
    this.attempts = List.copyOf(attempts);
  }

  public List<String> attempts() {
    return attempts;
  }
}
