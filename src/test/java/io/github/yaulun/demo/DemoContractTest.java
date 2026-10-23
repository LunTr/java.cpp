package io.github.yaulun.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoContractTest {
  @TempDir Path directory;

  @Test
  void validatesCliAndExitCodes() {
    assertThrows(
        CliArgs.UsageException.class,
        () -> CliArgs.parse(new String[] {"--unknown"}, Set.of(), Set.of()));
    assertThrows(
        CliArgs.UsageException.class,
        () -> CliArgs.parse(new String[] {"--model"}, Set.of(), Set.of("--model")));
    assertThrows(
        CliArgs.UsageException.class,
        () -> CliArgs.parse(new String[] {"--x", "--x"}, Set.of("--x"), Set.of()));
    CliArgs args = CliArgs.parse(new String[] {"--ngl", "-1"}, Set.of(), Set.of("--ngl"));
    assertEquals(-1, args.intValue("--ngl", 0, -1, 8));
    assertEquals(2, InferenceDemoMain.run(new String[] {"--max-tokens", "0"}));
    assertEquals(2, InferenceDemoMain.run(new String[] {"--quant", "Q2"}));
    assertEquals(2, InferenceDemoMain.run(new String[] {"--ctx", "abc"}));
    assertEquals(
        1,
        InferenceDemoMain.run(
            new String[] {"--model", directory.resolve("missing.gguf").toString()}));
    assertEquals(0, InferenceDemoMain.run(new String[] {"--help"}));
    assertEquals(2, DependencyCheckMain.run(new String[] {"--unknown"}));
  }

  @Test
  void readsNumericPeVersionAndComparesSegments() throws Exception {
    ByteBuffer bytes = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
    bytes.position(7);
    bytes.putInt(0xFEEF04BD).putInt(0x00010000).putInt((14 << 16) | 51).putInt((36247 << 16) | 2);
    Path file = directory.resolve("sample.dll");
    Files.write(file, bytes.array());
    assertEquals("14.51.36247.2", PeVersionReader.fileVersion(file).orElseThrow());
    assertTrue(PeVersionReader.compare("14.51", "14.9") > 0);
    assertEquals(0, PeVersionReader.compare("14.51", "14.51.0"));
  }

  @Test
  void demoSourcesUseOnlyPublicFacadeAndRemainOutsideLibrary() throws Exception {
    Path libraryRoot = Path.of("src/Java_Cpp");
    try (var listing = Files.list(libraryRoot)) {
      assertEquals(
          List.of("io"), listing.map(path -> path.getFileName().toString()).sorted().toList());
    }
    for (Path source :
        List.of(
            Path.of("src/main/java/io/github/yaulun/demo"),
            Path.of("src/main/java/io/github/yaulun/demo/download"))) {
      try (var files = Files.list(source)) {
        for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
          assertFalse(Files.readString(file).contains("javacpp.internal"), file.toString());
        }
      }
    }
  }
}
