package io.github.yaulun.demo.download;

import java.util.Locale;

/** Immutable model revisions from the task specification, used only for managed downloads. */
public final class ModelCatalog {
  public static final ModelSpec Q8_0 =
      new ModelSpec(
          "Q8_0",
          "Qwen3.5-0.8B-Q8_0.gguf",
          811843840L,
          "0ad885ffd4bb022fc4f0d33a3308fa108ef8613159d3b3a67e23abca056b7a6c");
  public static final ModelSpec Q4_K_M =
      new ModelSpec(
          "Q4_K_M",
          "Qwen3.5-0.8B-Q4_K_M.gguf",
          532517120L,
          "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517");

  private ModelCatalog() {}

  /** Resolves Q8_0 or Q4_K_M case-insensitively. */
  public static ModelSpec byQuant(String quant) {
    return switch (quant.toUpperCase(Locale.ROOT)) {
      case "Q8_0" -> Q8_0;
      case "Q4_K_M" -> Q4_K_M;
      default -> throw new IllegalArgumentException("quant must be Q8_0 or Q4_K_M");
    };
  }
}
