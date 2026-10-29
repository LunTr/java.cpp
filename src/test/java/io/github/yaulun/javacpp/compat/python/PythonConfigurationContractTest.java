package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonConfigurationContractTest {
  @TestFactory
  Stream<DynamicTest> originalConfigurationAndRepositoryBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-configuration-contracts.tsv"),
            "Run generate_configuration_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "configuration:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      PythonServicesContractTest.compare(
                          PythonServicesContractTest.read(parts[1]),
                          run(map(PythonServicesContractTest.read(parts[0]))),
                          "configuration:" + i);
                    }));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object v) {
    return (Map<String, Object>) v;
  }

  private static Object capture(Supplier<?> call) {
    try {
      return call.get();
    } catch (PythonException e) {
      return dict("error", e.type().pythonName(), "message", e.getMessage());
    }
  }

  private static Object run(Map<String, Object> arg) {
    List<Object> trace = new ArrayList<>();
    if (arg.get("kind").equals("config")) {
      var values = new LinkedHashMap<>(map(arg.get("values")));
      PythonLlama llama =
          new PythonLlama(
              new int[0],
              new float[0][],
              0,
              4,
              (boolean) values.get("_logits_all"),
              false,
              false,
              () -> null);
      llama.setSeed(BigInteger.valueOf(((Number) values.get("_seed")).longValue()));
      var configuration =
          new PythonLlamaConfiguration(
              llama,
              values,
              map(arg.get("model")),
              map(arg.get("context")),
              options -> trace.add(List.of("initialize", options)));
      Object result = capture(configuration::getState);
      Object restored =
          capture(
              () -> {
                configuration.setState(map(arg.get("restore")));
                return null;
              });
      return dict("result", result, "restored", restored, "trace", trace);
    }
    List<Map<String, Object>> requests = new ArrayList<>();
    PythonPretrained.Repository repository =
        !(boolean) arg.get("installed")
            ? null
            : new PythonPretrained.Repository() {
              public void validate(String repo) {
                trace.add(List.of("validate", repo));
              }

              public List<?> files(String repo) {
                trace.add(List.of("files", repo));
                return (List<?>) arg.get("files");
              }

              public String download(Map<String, Object> args) {
                requests.add(args);
                trace.add(List.of("download", new LinkedHashMap<>(args)));
                return "cached.gguf";
              }
            };
    Object result =
        capture(
            () ->
                PythonPretrained.fromPretrained(
                    map(arg.get("options")),
                    repository,
                    options -> {
                      trace.add(List.of("construct", options));
                      return options;
                    }));
    return dict(
        "result",
        result,
        "trace",
        trace,
        "requestFlags",
        requests.stream().map(request -> request.containsKey("local_files_only")).toList());
  }
}
