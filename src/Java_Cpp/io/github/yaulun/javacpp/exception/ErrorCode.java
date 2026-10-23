package io.github.yaulun.javacpp.exception;

/** Stable diagnostic codes with actionable repair hints. */
public enum ErrorCode {
  E101("Unsupported platform", "Use Windows x64 with a 64-bit JDK 21 or later."),
  E102("Native directory unavailable", "Set --natives, -Djavacpp.natives or JAVACPP_NATIVES."),
  E103(
      "Native files missing", "Extract one complete b11669 release into the configured directory."),
  E104(
      "Native library load failed",
      "Check VC++ x64 runtime, DLL dependencies and JDK architecture."),
  E105("Required native symbol missing", "Use the complete official b11669 library set."),
  E106("Native ABI mismatch", "Use b11669; update bindings before changing the native version."),
  E107("Backend unavailable", "Restore ggml-cpu-*.dll and check backend dependencies."),
  E201("Model file unavailable", "Wait for the model download to finish or set --model."),
  E202("Model read failed", "Check file permissions and available disk space."),
  E203("Invalid GGUF file", "Verify the download size and SHA-256."),
  E204("Unsupported GGUF version", "Use a GGUF v2 or v3 model."),
  E205("Model load failed", "Inspect native logs; try --ngl 0 and verify the model file."),
  E206("Model load cancelled", "Retry when ready; inspect the progress listener if it threw."),
  E301("Context creation failed", "Reduce --ctx or use --ngl 0."),
  E302("Context capacity exceeded", "Increase --ctx, shorten the prompt or use /reset."),
  E303("Inference failed", "Clear or recreate the context and inspect native logs."),
  E304("Inference aborted", "Retry after clearing the context."),
  E401("Token conversion failed", "Shorten the input and verify model vocabulary."),
  E402("Chat template missing", "Select ChatTemplate.named(\"chatml\")."),
  E403("Chat template unsupported", "Choose a built-in template such as chatml."),
  E501("Sampler creation failed", "Check sampling parameters and available memory.");
  private final String title;
  private final String hint;

  ErrorCode(String title, String hint) {
    this.title = title;
    this.hint = hint;
  }

  /** Returns the short diagnostic title. */
  public String title() {
    return title;
  }

  /** Returns the suggested repair action. */
  public String hint() {
    return hint;
  }
}
