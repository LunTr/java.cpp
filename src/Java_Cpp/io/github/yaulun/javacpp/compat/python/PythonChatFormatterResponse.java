package io.github.yaulun.javacpp.compat.python;

/** Fields of llama-cpp-python 0.3.36's static ChatFormatterResponse. */
public record PythonChatFormatterResponse(String prompt, Object stop) {
  public Object stoppingCriteria() {
    return null;
  }

  public boolean addedSpecial() {
    return false;
  }
}
