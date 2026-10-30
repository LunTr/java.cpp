package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.yaulun.javacpp.python.PythonException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonJinjaContractTest {
  @TestFactory
  Stream<DynamicTest> pinnedJinjaFormatter() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-jinja-contracts.tsv"),
            "Run generate_jinja_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "jinja:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      var arguments = map(PythonServicesContractTest.read(parts[0]));
                      if (i == 60)
                        java.nio.file.Files.writeString(
                            java.nio.file.Path.of(".tools/qwen-normalized.txt"),
                            PythonJinjaDialect.normalize((String) arguments.get("template")));
                      Object expected = PythonServicesContractTest.read(parts[1]);
                      Object actual;
                      try {
                        var renderer =
                            new PythonJinjaTemplate(
                                (String) arguments.get("template"), "<eos>", "<bos>");
                        actual = dict("output", renderer.render(map(arguments.get("bindings"))));
                      } catch (PythonException error) {
                        actual =
                            dict("error", error.type().pythonName(), "message", error.getMessage());
                      }
                      assertEquals(expected, actual, "case=" + i);
                    }));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
