package io.github.yaulun.javacpp;

/** Build identity of the facade and its supported native ABI. */
public final class JavaCpp {
  public static final String VERSION = "1.0.0-SNAPSHOT";
  public static final String LLAMA_CPP_BUILD = "b11669";
  public static final String LLAMA_CPP_VERSION = "0.7.0";
  public static final String LLAMA_CPP_COMMIT = "620d2225c";

  private JavaCpp() {}

  /** Returns the version banner. */
  public static String banner() {
    return "Java.cpp " + VERSION + " (llama.cpp " + LLAMA_CPP_BUILD + ")";
  }
}
