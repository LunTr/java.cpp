package io.github.yaulun.javacpp;

/** Wire roles accepted by chat templates. */
public enum Role {
  SYSTEM("system"),
  USER("user"),
  ASSISTANT("assistant");
  private final String wireName;

  Role(String wireName) {
    this.wireName = wireName;
  }

  /** Returns the lowercase role name passed to native chat templates. */
  public String wireName() {
    return wireName;
  }
}
