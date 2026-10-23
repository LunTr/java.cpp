package io.github.yaulun.demo.download;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** Revision-pinned public model mirrors, tried in declaration order. */
public enum DownloadSource {
  MODELSCOPE(
      "modelscope",
      "https://www.modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/resolve/88467eb7c8e3b6e7894c794f373050d4dbc6ae8a/"),
  HF_MIRROR(
      "hf-mirror",
      "https://hf-mirror.com/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/"),
  HUGGINGFACE(
      "huggingface",
      "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/");
  private final String displayName;
  private final String prefix;

  DownloadSource(String displayName, String prefix) {
    this.displayName = displayName;
    this.prefix = prefix;
  }

  public String displayName() {
    return displayName;
  }

  /** Returns the download URI for the exact revision and file identity. */
  public URI uriFor(ModelSpec spec) {
    return URI.create(prefix + spec.fileName());
  }

  /** Resolves auto or one mirror name, rejecting unknown values. */
  public static List<DownloadSource> parse(String value) {
    if (value.equalsIgnoreCase("auto")) {
      return List.of(values());
    }
    return List.of(
        Arrays.stream(values())
            .filter(source -> source.displayName.equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown download source: " + value)));
  }
}
