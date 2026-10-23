package io.github.yaulun.javacpp;

/** Actual and expected native build identities. */
public record NativeVersions(
    String llama, String ggml, String commit, String expectedVersion, String expectedCommit) {
  /** Returns whether both the version and commit match the pinned ABI. */
  public boolean matchesExpected() {
    return llama != null
        && commit != null
        && llama.startsWith(expectedVersion)
        && commit.startsWith(expectedCommit);
  }

  @Override
  public String toString() {
    return "llama " + llama + " / ggml " + ggml + " / commit " + commit;
  }
}
