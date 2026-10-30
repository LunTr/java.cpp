package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import io.github.yaulun.javacpp.python.PythonValues;

class PythonTokenizerContractTest {
  @TestFactory
  Stream<DynamicTest> matchesOriginalTokenizerBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-tokenizer-contracts.tsv"),
            "Run generate_tokenizer_contracts.py --source-root .tools/python-api-audit")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "tokenizer:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object result = execute(map(read(parts[0])));
                      ByteArrayOutputStream output = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(output), result);
                      assertEquals(
                          parts[1], Base64.getEncoder().encodeToString(output.toByteArray()));
                    }));
  }

  private static Object read(String value) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static int[] ids(Object value) {
    return value == null
        ? null
        : ((List<?>) value).stream().mapToInt(n -> ((Number) n).intValue()).toArray();
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  private static Object execute(Map<String, Object> arg) {
    List<Object> trace = new ArrayList<>();
    int[] tokens = ids(arg.get("tokens")), previous = ids(arg.get("previous"));
    byte[] output = HexFormat.of().parseHex((String) arg.get("output"));
    byte[] text = HexFormat.of().parseHex((String) arg.get("bytes"));
    List<?> flags = (List<?>) arg.get("flags");
    boolean addBos = (boolean) flags.get(0), special = (boolean) flags.get(1);
    PythonTokenizerModel model =
        new PythonTokenizerModel() {
          public int[] tokenize(byte[] bytes, boolean bos, boolean specials) {
            trace.add(List.of("modelTokens", hex(bytes), bos, specials));
            return tokens;
          }

          public byte[] detokenize(int[] values, boolean specials) {
            trace.add(List.of("modelBytes", values == tokens, specials));
            return output;
          }
        };
    PythonLlama llama =
        new PythonLlama(new int[0], new float[0][], 0, 8, false, false, false, () -> null);
    llama.setTokenizerModel(model);
    PythonLlamaTokenizer wrapper = llama.tokenizer();
    List<Object> results = new ArrayList<>();
    results.add(llama.tokenize(text) == tokens);
    results.add(wrapper.tokenize(text) == tokens);
    results.add(llama.tokenize(text, addBos, special) == tokens);
    results.add(wrapper.encode((String) arg.get("text"), addBos, special) == tokens);
    results.add(wrapper.encode((String) arg.get("text")) == tokens);
    results.add(hex(llama.detokenize(tokens)));
    results.add(hex(llama.detokenize(tokens, previous, special)));
    results.add(hex(wrapper.detokenize(tokens, previous, special)));
    results.add(wrapper.decode(tokens));
    llama.setTokenizer(
        new PythonTokenizer() {
          public int[] tokenize(byte[] bytes, boolean bos, boolean specials) {
            trace.add(List.of("customTokens", hex(bytes), bos, specials));
            return tokens;
          }

          public byte[] detokenize(int[] values, int[] prev, boolean specials) {
            trace.add(List.of("customBytes", values == tokens, prev == previous, specials));
            return output;
          }
        });
    results.add(llama.tokenize(text) == tokens);
    results.add(hex(llama.detokenize(tokens, previous, special)));
    results.add(llama.tokenizer() != llama.tokenizer());
    results.add(llama.tokenizer().tokenize(text) == tokens);
    llama.setTokenizerModel(
        new PythonTokenizerModel() {
          public int[] tokenize(byte[] bytes, boolean bos, boolean specials) {
            throw new AssertionError();
          }

          public byte[] detokenize(int[] values, boolean specials) {
            throw new AssertionError();
          }
        });
    results.add(wrapper.tokenize(text) == tokens);
    return dict("results", results, "trace", trace);
  }
}
