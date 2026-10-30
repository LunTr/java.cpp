package io.github.yaulun.javacpp.compat.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yaulun.javacpp.ModelParams;
import io.github.yaulun.javacpp.exception.ContextOverflowException;
import io.github.yaulun.javacpp.exception.ErrorCode;
import io.github.yaulun.javacpp.exception.InferenceException;
import io.github.yaulun.javacpp.exception.JavaCppException;
import io.github.yaulun.javacpp.exception.ModelLoadException;
import io.github.yaulun.javacpp.exception.NativeFailure;
import io.github.yaulun.javacpp.exception.TokenizationException;
import io.github.yaulun.javacpp.python.PythonBytes;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

class PythonBridgeContractTest {
  @TempDir Path directory;

  @TestFactory
  Stream<DynamicTest> matchesExecutedUpstreamBranchesAndPythonFormatting() throws Exception {
    List<String> lines;
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    getClass().getResourceAsStream("/python-0.3.36-contracts.tsv"),
                    "Generate ignored fixtures with tools/python_compat/generate_all_contracts.py; see tools/python_compat/README.md"),
                StandardCharsets.UTF_8))) {
      lines = reader.lines().toList();
    }
    return lines.stream()
        .map(
            line -> {
              String[] columns = line.split("\t", -1);
              byte[] argument = Base64.getDecoder().decode(columns[1]);
              String expected =
                  new String(Base64.getDecoder().decode(columns[4]), StandardCharsets.UTF_8);
              return DynamicTest.dynamicTest(
                  columns[0] + ":" + columns[1] + ":" + columns[2],
                  () -> check(columns[0], argument, columns[2], columns[3], expected));
            });
  }

  private static void check(
      String kind, byte[] argument, String number, String category, String expected) {
    if (kind.equals("emptySample")) {
      PythonException failure =
          assertThrows(
              PythonException.class,
              () -> PythonErrorBridge.requireEvaluatedTokens(Integer.parseInt(number)));
      assertEquals(category, failure.type().pythonName());
      assertEquals(expected, failure.getMessage());
      assertEquals("AssertionError", failure.toString());
      return;
    }
    if (kind.equals("bytesRepr")) {
      assertEquals(expected, PythonBytes.repr(argument));
      return;
    }
    String text = new String(argument, StandardCharsets.UTF_8);
    if (kind.equals("maxTokens")) {
      Integer limit = number.equals("None") ? null : Integer.valueOf(number);
      assertEquals(
          Integer.parseInt(expected), PythonErrorBridge.completionConfig(limit, 5, 32).maxTokens());
      return;
    }
    if (kind.equals("warningFormat")) {
      PythonWarning warning =
          new PythonWarning(
              PythonErrorType.RUNTIME_WARNING,
              text,
              new PythonWarningLocation("origin.py", 7, "origin", "  x = call() \n"));
      assertEquals(expected, warning.format());
      return;
    }
    if (kind.equals("warningUnicodeFormat")) {
      var warning =
          new PythonWarning(
              PythonErrorType.RUNTIME_WARNING,
              "warning",
              new PythonWarningLocation("origin.py", 7, "origin", text + "x" + text));
      assertEquals(expected, warning.format());
      return;
    }
    if (kind.equals("warningAction")) {
      List<PythonWarning> emitted = new ArrayList<>();
      PythonWarnings warnings =
          PythonWarnings.withAction(
              PythonWarningAction.valueOf(text.toUpperCase(Locale.ROOT)), emitted::add);
      int failures = 0;
      for (PythonWarningLocation location :
          List.of(
              location("pkgA", 1), location("pkgA", 1), location("pkgA", 2), location("pkgB", 1))) {
        try {
          warnings.warn(new PythonWarning(PythonErrorType.RUNTIME_WARNING, "test", location));
        } catch (PythonException failure) {
          assertEquals(PythonErrorType.RUNTIME_WARNING, failure.type());
          failures++;
        }
      }
      assertEquals(Integer.parseInt(number), emitted.size());
      assertEquals(Integer.parseInt(expected), failures);
      return;
    }
    if (kind.equals("deprecated") || kind.equals("duplicateBos")) {
      List<PythonWarning> emitted = new ArrayList<>();
      PythonWarnings warnings = PythonWarnings.withAction(PythonWarningAction.ALWAYS, emitted::add);
      if (kind.equals("deprecated")) {
        String[] values = text.split("\t");
        warnings.deprecated(values[0], values[1], location("llama_cpp.llama_cpp", 1));
      } else {
        warnings.duplicateBos(text, location("llama_cpp.llama", 1));
      }
      assertEquals(1, emitted.size());
      assertEquals(category, emitted.getFirst().category().pythonName());
      assertEquals(expected, emitted.getFirst().message());
      return;
    }
    if (kind.equals("overflow")) {
      int count = Integer.parseInt(number);
      PythonException direct =
          assertThrows(
              PythonException.class, () -> PythonErrorBridge.completionConfig(1, count, 32));
      assertEquals(category, direct.type().pythonName());
      assertEquals(expected, direct.getMessage());
      var original = new ContextOverflowException(count, 32);
      PythonException translated =
          PythonErrorBridge.translate(original, PythonCall.completion(count, 32)).orElseThrow();
      assertEquals(expected, translated.getMessage());
      assertSame(original, translated.getCause());
      return;
    }
    JavaCppException original;
    PythonCall call;
    switch (kind) {
      case "missingModel" -> {
        original =
            new ModelLoadException(ErrorCode.E201, "original evidence", List.of("native log"));
        call = PythonCall.modelLoad(text);
      }
      case "nullModel", "nullVocab" -> {
        String function =
            kind.equals("nullModel") ? "llama_model_load_from_file" : "llama_model_get_vocab";
        original =
            new ModelLoadException(
                ErrorCode.E205,
                "original evidence",
                List.of(),
                null,
                new NativeFailure(function, 0));
        call = PythonCall.modelLoad(text);
      }
      case "contextNull" -> {
        original =
            new InferenceException(
                ErrorCode.E301,
                "original evidence",
                List.of(),
                null,
                new NativeFailure("llama_init_from_model", 0));
        call = PythonCall.contextCreation();
      }
      case "decode" -> {
        int code = Integer.parseInt(number);
        original =
            code == 1
                ? new ContextOverflowException(1, 0, new NativeFailure("llama_process", code))
                : new InferenceException(
                    code == 2 ? ErrorCode.E304 : ErrorCode.E303,
                    "original evidence",
                    List.of(),
                    null,
                    new NativeFailure("llama_process", code));
        call = PythonCall.decode();
      }
      case "tokenize" -> {
        original =
            new TokenizationException(
                ErrorCode.E401,
                "original evidence",
                List.of(),
                null,
                new NativeFailure("llama_tokenize", Integer.parseInt(number)));
        call = PythonCall.tokenize(argument);
      }
      default -> throw new AssertionError("Unexpected fixture kind: " + kind);
    }
    PythonException translated = PythonErrorBridge.translate(original, call).orElseThrow();
    assertEquals(category, translated.type().pythonName());
    assertEquals(expected, translated.getMessage());
    assertEquals(category + ": " + expected, translated.toString());
    assertSame(original, translated.getCause());
    assertSame(translated, PythonErrorBridge.translate(translated, call).orElseThrow());
  }

  @Test
  void preservesUnknownFailuresAndRequiresStructuredNativeEvidence() {
    RuntimeException callback = new IllegalArgumentException("user callback");
    assertTrue(PythonErrorBridge.translate(callback, PythonCall.decode()).isEmpty());
    assertSame(
        callback,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PythonErrorBridge.call(
                    PythonCall.decode(),
                    () -> {
                      throw callback;
                    })));
    var capacity = new ContextOverflowException(17, 16);
    assertTrue(PythonErrorBridge.translate(capacity, PythonCall.decode()).isEmpty());
    assertTrue(
        PythonErrorBridge.translate(
                new InferenceException(ErrorCode.E301, "batch allocation failed", List.of()),
                PythonCall.contextCreation())
            .isEmpty());
    var cancelled =
        new ModelLoadException(
            ErrorCode.E206,
            "callback failed",
            List.of(),
            callback,
            new NativeFailure("llama_model_load_from_file", 0));
    assertTrue(
        PythonErrorBridge.translate(cancelled, PythonCall.modelLoad("model.gguf")).isEmpty());
  }

  @Test
  void retainsOriginalBytesAndHandlesDefaultDeprecationFilters() {
    byte[] bytes = {65};
    PythonCall call = PythonCall.tokenize(bytes);
    bytes[0] = 66;
    var original =
        new TokenizationException(
            ErrorCode.E401, "failure", List.of(), null, new NativeFailure("llama_tokenize", -1));
    assertTrue(
        PythonErrorBridge.translate(original, call).orElseThrow().getMessage().contains("b'A'"));
    List<PythonWarning> emitted = new ArrayList<>();
    PythonWarnings warnings = PythonWarnings.defaults(emitted::add);
    warnings.deprecated("old", "use new", location("package", 1));
    assertTrue(emitted.isEmpty());
    warnings.deprecated("old", "use new", location("__main__", 1));
    warnings.deprecated("old", "use new", location("__main__", 1));
    assertEquals(1, emitted.size());
    warnings.clearRegistry();
    warnings.deprecated("old", "use new", location("__main__", 1));
    assertEquals(2, emitted.size());
  }

  @Test
  void mapsRealFilesystemPrechecksIncludingExistingDirectories() throws Exception {
    Path missing = directory.resolve("missing.gguf");
    PythonException missingError =
        assertThrows(
            PythonException.class,
            () -> PythonErrorBridge.loadModel(missing, ModelParams.defaults()));
    assertEquals(PythonErrorType.VALUE_ERROR, missingError.type());
    assertEquals("Model path does not exist: " + missing, missingError.getMessage());
    assertEquals(ErrorCode.E201, ((ModelLoadException) missingError.getCause()).code());
    PythonException folderError =
        assertThrows(
            PythonException.class,
            () -> PythonErrorBridge.loadModel(directory, ModelParams.defaults()));
    assertEquals("Failed to load model from file: " + directory, folderError.getMessage());
    Path html = directory.resolve("page.gguf");
    Files.writeString(html, "<html>download page</html>");
    PythonException fileError =
        assertThrows(
            PythonException.class, () -> PythonErrorBridge.loadModel(html, ModelParams.defaults()));
    assertEquals("Failed to load model from file: " + html, fileError.getMessage());
  }

  private static PythonWarningLocation location(String module, int line) {
    return new PythonWarningLocation(module + ".py", line, module, null);
  }
}
