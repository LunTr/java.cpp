package io.github.yaulun.javacpp.compat.python;

/** Fields of llama-cpp-python 0.3.36's static ChatFormatterResponse. */
public record PythonChatFormatterResponse(String prompt, Object stop) {
  /** Static formatters leave stopping_criteria unset. */
  public Object stoppingCriteria() {
    return null;
  }

  /** Static formatters leave added_special false. */
  public boolean addedSpecial() {
    return false;
  }
}
