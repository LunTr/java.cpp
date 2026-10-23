package io.github.yaulun.javacpp.internal;

import java.nio.file.Path;

/** Strong process-lifetime references to the loaded JNA libraries. */
public record NativeLibraries(
    Path directory, LlamaLibrary llama, GgmlLibrary ggml, GgmlBaseLibrary base) {}
