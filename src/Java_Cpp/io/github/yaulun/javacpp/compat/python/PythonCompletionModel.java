package io.github.yaulun.javacpp.compat.python;

/** Vocabulary metadata used by original completion prompt construction. */
public interface PythonCompletionModel {
  int bos();

  int eos();

  int cls();

  int sep();

  boolean addBos();

  boolean addEos();

  boolean isEog(int token);

  String tokenText(int token);

  String metadata(String key, String fallback);

  int contextLength();

  String modelPath();

  void resetTimings();

  void printTimings();
}
