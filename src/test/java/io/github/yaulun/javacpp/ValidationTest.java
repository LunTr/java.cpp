package io.github.yaulun.javacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import io.github.yaulun.javacpp.exception.NativeLibraryException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationTest {
  @TempDir Path directory;

  @Test
  void validatesGgufBeforeNativeLoading() throws Exception {
    byte[] header =
        ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46554747)
            .putInt(3)
            .putLong(335)
            .putLong(41)
            .array();
    assertEquals(335, GgufHeader.parse(header, 24).tensorCount());
    assertEquals(
        ErrorCode.E203,
        assertThrows(ModelLoadException.class, () -> GgufHeader.parse(new byte[0], 0)).code());
    assertEquals(
        ErrorCode.E203,
        assertThrows(
                ModelLoadException.class,
                () ->
                    GgufHeader.parse(
                        "<html>invalid download page</html>".getBytes(StandardCharsets.UTF_8), 32))
            .code());
    header[4] = 1;
    assertEquals(
        ErrorCode.E204,
        assertThrows(ModelLoadException.class, () -> GgufHeader.parse(header, 24)).code());
    assertEquals(
        ErrorCode.E201,
        assertThrows(
                ModelLoadException.class, () -> GgufHeader.read(directory.resolve("missing.gguf")))
            .code());
    Files.write(directory.resolve("short.gguf"), new byte[5]);
    assertEquals(
        ErrorCode.E203,
        assertThrows(
                ModelLoadException.class, () -> GgufHeader.read(directory.resolve("short.gguf")))
            .code());
  }

  @Test
  void validatesBoundaryParametersWithoutDlls() {
    assertEquals(16, ContextParams.builder().contextLength(16).build().contextLength());
    assertEquals(262144, ContextParams.builder().contextLength(262144).build().contextLength());
    assertThrows(IllegalArgumentException.class, () -> ContextParams.builder().contextLength(15));
    assertThrows(
        IllegalArgumentException.class, () -> ContextParams.builder().contextLength(262145));
    assertThrows(IllegalArgumentException.class, () -> ContextParams.builder().batchSize(0));
    assertThrows(IllegalArgumentException.class, () -> ContextParams.builder().threads(0));
    assertThrows(IllegalArgumentException.class, () -> ModelParams.builder().gpuLayers(-2));
    assertThrows(IllegalArgumentException.class, () -> ModelParams.builder().mainGpu(-1));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().temperature(Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().temperature(-1));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().topP(0));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().topP(1.1f));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().minP(-0.1f));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().repeatPenalty(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> Sampler.builder().frequencyPenalty(Float.POSITIVE_INFINITY));
    assertThrows(
        IllegalArgumentException.class, () -> Sampler.builder().presencePenalty(Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().seed(4294967296L));
    assertThrows(IllegalArgumentException.class, () -> Sampler.builder().penaltyLastN(-1));
    assertThrows(IllegalArgumentException.class, () -> new GenerationConfig(0, null));
    assertFalse(GenerationConfig.defaults().cancel().getAsBoolean());
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.user("bad\0text"));
    ModelParams.Builder builder = ModelParams.builder().gpuLayers(0);
    ModelParams snapshot = builder.build();
    builder.gpuLayers(5);
    assertEquals(0, snapshot.gpuLayers());
    assertEquals(17, ContextParams.builder().threads(17).build().threadsBatch());
  }

  @Test
  void reportsErrorsWithImmutableEvidence() {
    var error = new NativeLibraryException(ErrorCode.E104, "llama.dll", List.of("native detail"));
    assertTrue(error.getMessage().startsWith("[E104]"));
    assertTrue(error.getMessage().contains("建议"));
    assertTrue(error.getMessage().contains("native detail"));
    assertThrows(UnsupportedOperationException.class, () -> error.nativeLog().add("x"));
    assertFalse(
        new NativeLibraryException(ErrorCode.E104, "x", List.of())
            .getMessage()
            .contains("Native log"));
  }

  @Test
  void enumeratesOnlyImmediateNativeFiles() throws Exception {
    NativeLayout layout = NativeLayout.WINDOWS_X64;
    assertEquals(4, layout.missingRequired(directory).size());
    for (String name : layout.requiredFiles()) {
      Files.createFile(directory.resolve(name));
    }
    Files.createFile(directory.resolve("ggml-cpu-x64.dll"));
    assertTrue(layout.missingRequired(directory).isEmpty());
    assertEquals(1, layout.cpuBackends(directory).size());
    Path nested = Files.createDirectory(directory.resolve("bin"));
    Files.createFile(nested.resolve("llama.dll"));
    assertEquals(nested, layout.findNestedFolder(directory).orElseThrow());
  }
}
