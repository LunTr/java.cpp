package dev.javacpp.compat.python;

import static org.junit.jupiter.api.Assertions.*;

import dev.javacpp.python.PythonException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonMtmdContractTest {
  @TestFactory
  Stream<DynamicTest> matchesOriginalBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-mtmd-contracts.tsv"),
            "Run generate_mtmd_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "mtmd:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object result = drive(map(read(parts[1])));
                      ByteArrayOutputStream out = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(out), result);
                      String actual = Base64.getEncoder().encodeToString(out.toByteArray());
                      if (!parts[2].equals(actual)) {
                        java.nio.file.Files.write(
                            java.nio.file.Path.of(".verify/mtmd-" + i + ".b64"), actual.getBytes());
                        fail("mtmd " + i + " differs");
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

  private static Object drive(Map<String, Object> c) {
    List<Object> trace = new ArrayList<>();
    List<PythonWarning> caught = new ArrayList<>();
    PythonWarnings warnings = PythonWarnings.withAction(PythonWarningAction.ALWAYS, caught::add);
    PythonMtmdCpp.MtmdAccess access =
        new PythonMtmdCpp.MtmdAccess() {
          public int audioSampleRate(Object ctx) {
            trace.add(Arrays.asList("mtmd_get_audio_sample_rate", ctx));
            return ((Number) c.get("sample_rate")).intValue();
          }

          public Object initOptDefault() {
            trace.add(List.of("mtmd_helper_init_opt_default"));
            return Map.of("opt", "default");
          }

          public PythonMtmdCpp.BitmapWrapperResult bitmapInitFromFileWrapper(
              Object ctx, Object fname, boolean placeholder, Object opt) {
            trace.add(
                Arrays.asList(
                    "mtmd_helper_bitmap_init_from_file_wrapper", ctx, fname, placeholder, opt));
            return new PythonMtmdCpp.BitmapWrapperResult(c.get("bitmap"), null);
          }

          public PythonMtmdCpp.BitmapWrapperResult bitmapInitFromBufWrapper(
              Object ctx, Object buf, long length, boolean placeholder, Object opt) {
            trace.add(
                Arrays.asList(
                    "mtmd_helper_bitmap_init_from_buf_wrapper",
                    ctx,
                    buf,
                    length,
                    placeholder,
                    opt));
            return new PythonMtmdCpp.BitmapWrapperResult(c.get("bitmap"), null);
          }
        };
    PythonMtmdCpp mtmd = new PythonMtmdCpp(access, warnings);

    List<Object> results = new ArrayList<>();
    for (Object stepObj : list(c.get("ops"))) {
      List<Object> step = list(stepObj);
      String op = (String) step.get(0);
      caught.clear();
      try {
        switch (op) {
          case "bitrate" ->
              results.add(
                  mtmd.getAudioBitrate(
                      step.get(1),
                      new PythonWarningLocation("llama_cpp/mtmd_cpp.py", 562, "__main__", null)));
          case "fromfile" ->
              results.add(
                  mtmd.bitmapInitFromFile(
                      step.get(1),
                      step.get(2),
                      b(step.get(3)),
                      step.size() > 4 ? step.get(4) : null));
          case "frombuf" ->
              results.add(
                  mtmd.bitmapInitFromBuf(
                      step.get(1),
                      step.get(2),
                      ((Number) step.get(3)).longValue(),
                      b(step.get(4)),
                      step.size() > 5 ? step.get(5) : null));
          default -> throw new AssertionError("unknown op " + op);
        }
      } catch (PythonException e) {
        results.add(Map.of("error", e.type().pythonName(), "message", e.getMessage()));
      }
      for (PythonWarning w : caught) {
        trace.add(List.of("warn", w.category().pythonName(), w.message()));
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("results", results);
    out.put("trace", trace);
    return out;
  }
}
