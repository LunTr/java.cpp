package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import io.github.yaulun.javacpp.python.PythonException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonGrammarContractTest {
  @TestFactory
  Stream<DynamicTest> matchesOriginalBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-grammar-contracts.tsv"),
            "Run generate_grammar_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "gram:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object result = drive(map(read(parts[1])));
                      ByteArrayOutputStream out = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(out), result);
                      String actual = Base64.getEncoder().encodeToString(out.toByteArray());
                      if (!parts[2].equals(actual)) {
                        java.nio.file.Files.write(
                            java.nio.file.Path.of(".verify/gram-" + i + ".b64"), actual.getBytes());
                        fail("gram " + i + " differs");
                      }
                    }));
  }

  private static Object read(String v) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(v))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object v) {
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object v) {
    return (List<Object>) v;
  }

  private static boolean b(Object v) {
    return Boolean.TRUE.equals(v);
  }

  private static long l(Object v) {
    return ((Number) v).longValue();
  }

  private static Long lng(Object v) {
    return v == null ? null : ((Number) v).longValue();
  }

  private static Object capture(java.util.concurrent.Callable<Object> a) {
    try {
      return a.call();
    } catch (PythonException e) {
      return dict("error", e.type().pythonName(), "message", e.getMessage());
    } catch (Exception e) {
      String name =
          e instanceof IndexOutOfBoundsException ? "IndexError" : e.getClass().getSimpleName();
      return dict("error", name, "message", e.getMessage() == null ? "" : e.getMessage());
    }
  }

  private static Object drive(Map<String, Object> kase) {
    List<Object> results = new ArrayList<>();
    Map<String, Object> box = new HashMap<>();
    for (Object op : list(kase.get("ops"))) {
      List<Object> step = list(op);
      String name = (String) step.get(0);
      results.add(
          capture(
              () -> {
                switch (name) {
                  case "ginit" -> {
                    return PythonLlamaGrammar.grammarFromString((String) step.get(1), true);
                  }
                  case "gstr" -> {
                    return PythonLlamaGrammar.grammarFromString(
                        (String) step.get(1), b(step.get(2)));
                  }
                  case "gfile" -> {
                    var port =
                        new PythonLlamaGrammar.FilePort() {
                          public String readAll(Object f) throws Exception {
                            if (b(kase.get("open_fail"))) throw new IOException("denied");
                            return (String) kase.getOrDefault("file_content", "");
                          }
                        };
                    return PythonLlamaGrammar.grammarFromFile(port, step.get(1), b(step.get(2)));
                  }
                  case "gschema" -> {
                    return PythonLlamaGrammar.grammarFromJsonSchema(
                        (String) step.get(1), true, null);
                  }
                  case "rep" -> {
                    return PythonLlamaGrammar.buildRepetition(
                        (String) step.get(1),
                        l(step.get(2)),
                        lng(step.get(3)),
                        step.size() > 4 ? (String) step.get(4) : null,
                        step.size() > 5 && b(step.get(5)));
                  }
                  case "optrep" -> {
                    return PythonLlamaGrammar.buildRepetition(
                        (String) step.get(1),
                        l(step.get(2)),
                        lng(step.get(3)),
                        step.size() > 4 ? (String) step.get(4) : null,
                        false);
                  }
                  case "notlit" -> {
                    var conv = conv(Map.of(), false, false, false);
                    return conv.notLiteral(
                        (String) step.get(1),
                        step.size() > 2 && b(step.get(2)),
                        step.size() > 3 && b(step.get(3)));
                  }
                  case "scinit" -> {
                    box.put(
                        "conv",
                        conv(map(step.get(1)), b(step.get(2)), b(step.get(3)), b(step.get(4))));
                    return "ok";
                  }
                  case "visit" -> {
                    return conv(box).visit(step.get(1), (String) step.get(2));
                  }
                  case "rrefs" -> {
                    return conv(box).resolveRefs(step.get(1), (String) step.get(2));
                  }
                  case "gbnf" -> {
                    return PythonLlamaGrammar.jsonSchemaToGbnf(
                        (String) step.get(1), step.size() > 2 ? list(step.get(2)) : null);
                  }
                  case "fmt" -> {
                    return conv(box).formatGrammar();
                  }
                  case "pat" -> {
                    return conv(box).visitPattern((String) step.get(1), (String) step.get(2));
                  }
                  case "addrule" -> {
                    return conv(box).addRule((String) step.get(1), (String) step.get(2));
                  }
                  case "builtin" -> {
                    var br =
                        new PythonLlamaGrammar.BuiltinRule(
                            (String) step.get(1),
                            step.size() > 2
                                ? list(step.get(2)).stream().map(String::valueOf).toList()
                                : List.of());
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("content", br.content);
                    d.put("deps", br.deps);
                    return d;
                  }
                  default -> throw new AssertionError("unknown op " + name);
                }
              }));
    }
    return dict("results", results, "trace", List.of());
  }

  private static PythonLlamaGrammar.SchemaConverter conv(Map<String, Object> box) {
    return (PythonLlamaGrammar.SchemaConverter)
        box.computeIfAbsent("conv", k -> conv(Map.of(), false, false, false));
  }

  private static PythonLlamaGrammar.SchemaConverter conv(
      Map<String, Object> propOrder, boolean fetch, boolean dotall, boolean raw) {
    Map<String, Integer> order = new LinkedHashMap<>();
    for (var e : propOrder.entrySet())
      order.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
    return new PythonLlamaGrammar.SchemaConverter(order, fetch, dotall, raw, null);
  }
}
