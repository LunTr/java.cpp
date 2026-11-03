package dev.javacpp.compat.python;

import static dev.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.javacpp.python.PythonErrorType;
import dev.javacpp.python.PythonException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class PythonHelpersContractTest {
  @TestFactory
  Stream<DynamicTest> matchesPinnedHelperBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-helpers-contracts.tsv"),
            "Run generate_helpers_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            index ->
                DynamicTest.dynamicTest(
                    "helpers:" + index,
                    () -> {
                      String[] parts = lines.get(index).split("\t");
                      Object argument = read(parts[1]);
                      Object actual =
                          switch (parts[0]) {
                            case "suppress" -> runSuppress(argument);
                            case "singleton" -> runSingleton(argument);
                            case "base_tok" -> runBaseTokenizer(argument);
                            case "hf" -> runHf(argument);
                            case "pretrained" -> runPretrained(argument);
                            case "draft" -> runDraft(argument);
                            case "errfmt" -> runErrorFormat(argument);
                            case "route" -> runRoute(argument);
                            default -> throw new AssertionError(parts[0]);
                          };
                      var bytes = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(bytes), actual);
                      assertEquals(
                          parts[2],
                          Base64.getEncoder().encodeToString(bytes.toByteArray()),
                          "argument=" + argument + ", result=" + actual);
                    }));
  }

  private static Object read(String encoded) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))));
  }

  private static Object capture(Supplier<?> operation) {
    try {
      return operation.get();
    } catch (PythonException error) {
      return dict("error", error.type().pythonName(), "message", error.getMessage());
    }
  }

  // ---- suppress_stdout_stderr -------------------------------------------------

  private static final class FakeStream {
    final String name;
    Object _originalStdstreamCopy;

    FakeStream(String name) {
      this.name = name;
    }
  }

  private static final class FileObj implements PythonSuppressStdoutStderr.NullFile {
    final int fd;

    FileObj(int fd) {
      this.fd = fd;
    }

    public int fileno() {
      return fd;
    }

    @Override
    public String toString() {
      return "null" + fd;
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runSuppress(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> trace = new ArrayList<>();
    var os =
        new PythonSuppressStdoutStderr.Os() {
          public int dup(int n) {
            trace.add(List.of("dup", n));
            return 10 + n;
          }

          public void dup2(int source, int target) {
            trace.add(List.of("dup2", source, target));
          }

          public void close(int n) {
            trace.add(List.of("close", n));
          }
        };
    var sys =
        new PythonSuppressStdoutStderr.Sys() {
          Object out = new FakeStream((Boolean) argument.get("kernel") ? "KOUT" : "OUT");
          Object err = new FakeStream((Boolean) argument.get("kernel") ? "KERR" : "ERR");

          {
            if ((Boolean) argument.get("kernel")) {
              ((FakeStream) out)._originalStdstreamCopy = 101;
              ((FakeStream) err)._originalStdstreamCopy = 102;
            }
          }

          public Object stdout() {
            return out;
          }

          public void stdout(Object stream) {
            out = stream;
          }

          public Object stderr() {
            return err;
          }

          public void stderr(Object stream) {
            err = stream;
          }
        };
    var manager =
        new PythonSuppressStdoutStderr(
            (Boolean) argument.get("disable"), os, sys, new FileObj(30), new FileObj(31));
    Object enterResult = capture(() -> manager.enter() == manager ? "self" : "other");
    Object enterState = state(sys);
    Object exitResult =
        capture(
            () -> {
              manager.exit();
              return null;
            });
    Object exitState = state(sys);
    return dict(
        "enter", dict("result", enterResult, "state", enterState),
        "exit", dict("result", exitResult, "state", exitState),
        "trace", trace);
  }

  private static Object state(PythonSuppressStdoutStderr.Sys sys) {
    Object out = sys.stdout();
    Object err = sys.stderr();
    return dict(
        "stdout", out instanceof FakeStream stream ? stream.name : out.toString(),
        "stderr", err instanceof FakeStream stream ? stream.name : err.toString(),
        "out_copy",
            out instanceof FakeStream stream && stream._originalStdstreamCopy != null
                ? stream._originalStdstreamCopy
                : null,
        "err_copy",
            err instanceof FakeStream stream && stream._originalStdstreamCopy != null
                ? stream._originalStdstreamCopy
                : null);
  }

  // ---- MetaSingleton -----------------------------------------------------------

  private static Object runSingleton(Object input) {
    PythonSingleton.clearInstances();
    class A extends PythonSingleton {}
    class B extends PythonSingleton {}
    Supplier<PythonException> badInit =
        () -> {
          throw new PythonException(
              PythonErrorType.TYPE_ERROR,
              "Singleton.__init__() takes 1 positional argument but 2 were given",
              null);
        };
    Object cached =
        capture(
            () ->
                PythonSingleton.call(
                    A.class,
                    () -> {
                      throw badInit.get();
                    }));
    Object bad =
        capture(
            () ->
                PythonSingleton.call(
                    B.class,
                    () -> {
                      throw badInit.get();
                    }));
    Object first = PythonSingleton.call(A.class, A::new);
    Object second = PythonSingleton.call(A.class, A::new);
    Object third = PythonSingleton.call(B.class, B::new);
    return dict(
        "first_second_same",
        first == second,
        "ab_different",
        first != third,
        "cached_arg",
        cached,
        "bad_arg",
        bad);
  }

  // ---- BaseLlamaTokenizer -------------------------------------------------------

  private static final class TokenizeOnly extends PythonBaseLlamaTokenizer {
    @Override
    public int[] tokenize(byte[] text, boolean addBos, boolean special) {
      return new int[0];
    }

    @Override
    protected String pythonTypeName() {
      return "SubTok";
    }
  }

  private static class SuperTok extends PythonBaseLlamaTokenizer {
    @Override
    public int[] tokenize(byte[] text, boolean addBos, boolean special) {
      return super.tokenize(text, addBos, special);
    }

    @Override
    public byte[] detokenize(int[] tokens, int[] previousTokens, boolean special) {
      return super.detokenize(tokens, previousTokens, special);
    }

    @Override
    protected String pythonTypeName() {
      return "SubTok";
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runBaseTokenizer(Object input) {
    var argument = (Map<String, Object>) input;
    List<?> overrides = (List<?>) argument.get("overrides");
    String call = (String) argument.get("call");
    return capture(
        () -> {
          PythonBaseLlamaTokenizer tokenizer =
              switch (overrides.size()) {
                case 0 -> new PythonBaseLlamaTokenizer();
                case 1 -> new TokenizeOnly();
                default ->
                    call == null
                        ? new PythonBaseLlamaTokenizer() {
                          @Override
                          public int[] tokenize(byte[] text, boolean addBos, boolean special) {
                            return new int[0];
                          }

                          @Override
                          public byte[] detokenize(
                              int[] tokens, int[] previousTokens, boolean special) {
                            return new byte[0];
                          }
                        }
                        : new SuperTok();
              };
          if (call == null) return "created";
          if (call.equals("tokenize")) return tokenizer.tokenize(new byte[] {'x'}, true, true);
          return tokenizer.detokenize(new int[] {1}, null, false);
        });
  }

  // ---- LlamaHFTokenizer -----------------------------------------------------------

  private static final class StubHf implements PythonHfTokenizer.HfTokenizer {
    final List<Object> trace = new ArrayList<>();

    public int[] encode(String text, boolean addSpecialTokens) {
      trace.add(List.of("encode", text, addSpecialTokens));
      int[] out = new int[text.codePointCount(0, text.length()) + (addSpecialTokens ? 1 : 0)];
      int i = 0;
      for (int c : text.codePoints().toArray()) out[i++] = c;
      if (addSpecialTokens) out[i] = 7;
      return out;
    }

    public String decode(int[] tokens, boolean skipSpecialTokens) {
      List<Object> list = new ArrayList<>();
      for (int t : tokens) list.add(t);
      trace.add(List.of("decode", list, skipSpecialTokens));
      StringBuilder out = new StringBuilder("t");
      for (int t : tokens) if (!(skipSpecialTokens && t == 7)) out.append((char) t);
      return out.toString();
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runHf(Object input) {
    var argument = (Map<String, Object>) input;
    var hf = new StubHf();
    var inst = new PythonHfTokenizer(hf);
    List<Object> events = new ArrayList<>();
    for (Object item : (List<?>) argument.get("steps")) {
      List<?> step = (List<?>) item;
      Object result =
          capture(
              () -> {
                if (step.get(0).equals("tokenize")) {
                  String hex = (String) step.get(1);
                  byte[] text = new byte[hex.length() / 2];
                  for (int i = 0; i < text.length; i++)
                    text[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                  int[] out = inst.tokenize(text, (Boolean) step.get(2), (Boolean) step.get(3));
                  List<Object> list = new ArrayList<>();
                  for (int t : out) list.add(t);
                  return list;
                }
                int[] tokens = ints(step.get(1));
                int[] prev = step.get(2) == null ? null : ints(step.get(2));
                return hex(inst.detokenize(tokens, prev, (Boolean) step.get(3)));
              });
      events.add(dict("result", result, "trace", new ArrayList<>(hf.trace)));
      hf.trace.clear();
    }
    return events;
  }

  private static int[] ints(Object list) {
    List<?> items = (List<?>) list;
    int[] out = new int[items.size()];
    for (int i = 0; i < items.size(); i++) out[i] = ((Number) items.get(i)).intValue();
    return out;
  }

  private static String hex(byte[] bytes) {
    var out = new StringBuilder();
    for (byte b : bytes)
      out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static Object runPretrained(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> calls = new ArrayList<>();
    Object result =
        capture(
            () -> {
              var tokenizer =
                  PythonHfTokenizer.fromPretrained(
                      (String) argument.get("path"),
                      path -> {
                        if (!(Boolean) argument.get("installed"))
                          throw new PythonException(
                              PythonErrorType.IMPORT_ERROR, "no module", null);
                        calls.add(Map.of("pretrained_model_name_or_path", path));
                        return new StubHf();
                      });
              return tokenizer;
            });
    if (!(result instanceof Map)) {
      return dict("result", "created", "calls", calls, "made", "HF:" + argument.get("path"));
    }
    return dict("result", result, "calls", calls);
  }

  // ---- speculative ---------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Object runDraft(Object input) {
    var argument = (Map<String, Object>) input;
    if (argument.get("kind").equals("abstract")) {
      return dict("result", capture(() -> new PythonDraftModel().call(new int[0], Map.of())));
    }
    var inst =
        new PythonPromptLookupDecoding(
            ((Number) argument.get("ngram")).intValue(),
            ((Number) argument.get("pred")).intValue());
    int[] out = inst.call(ints(argument.get("ids")), Map.of());
    List<Object> list = new ArrayList<>();
    for (int t : out) list.add(t);
    return dict("result", list);
  }

  // ---- server errors ---------------------------------------------------------------

  private static final class StubBody implements PythonServerErrors.Body {
    private final PythonServerErrors.BodyKind kind;
    private final Object maxTokens;

    StubBody(PythonServerErrors.BodyKind kind, Object maxTokens) {
      this.kind = kind;
      this.maxTokens = maxTokens;
    }

    public PythonServerErrors.BodyKind kind() {
      return kind;
    }

    public Object maxTokens() {
      return maxTokens;
    }
  }

  private static PythonServerErrors.Body body(String kind, Object maxTokens) {
    if (kind == null) return null;
    var kindEnum =
        switch (kind) {
          case "chat" -> PythonServerErrors.BodyKind.CHAT_COMPLETION;
          case "completion" -> PythonServerErrors.BodyKind.COMPLETION;
          default -> PythonServerErrors.BodyKind.EMBEDDING;
        };
    return new StubBody(kindEnum, maxTokens);
  }

  /** Reparses a JSON payload the way custom_route_handler does. */
  private static PythonServerErrors.Body bodyFromPayload(Map<String, Object> payload) {
    if (payload.containsKey("messages")) return body("chat", payload.get("max_tokens"));
    if (payload.containsKey("prompt")) return body("completion", payload.get("max_tokens"));
    return body("embedding", null);
  }

  @SuppressWarnings("unchecked")
  private static Object runErrorFormat(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> writes = new ArrayList<>();
    var handler =
        new PythonServerErrors.RouteErrorHandler(
            request -> resp(), payload -> null, writes::add, () -> 0.0);
    String error = (String) argument.get("error");
    PythonServerErrors.Body body = body((String) argument.get("body"), argument.get("max_tokens"));
    Object wrapped = handler.errorMessageWrapper(new Exception(error), body);
    return dict("result", wrapped, "stderr", writes);
  }

  private static PythonServerErrors.Response resp() {
    Map<String, String> headers = new LinkedHashMap<>();
    return () -> headers;
  }

  @SuppressWarnings("unchecked")
  private static Object runRoute(Object input) {
    var argument = (Map<String, Object>) input;
    List<Object> writes = new ArrayList<>();
    double[] times = {1000.0, 1000.25};
    int[] tick = {0};
    var handler =
        new PythonServerErrors.RouteErrorHandler(
            request -> {
              String fail = (String) argument.get("fail");
              if ("http".equals(fail)) throw new PythonServerApp.HttpException(401, "unauthorized");
              if ("exc".equals(fail)) throw new RuntimeException((String) argument.get("error"));
              return resp();
            },
            PythonHelpersContractTest::bodyFromPayload,
            writes::add,
            () -> times[Math.min(tick[0]++, 1)]);
    var request =
        (PythonServerErrors.Request)
            () -> {
              if ((Boolean) argument.getOrDefault("badjson", false))
                throw new PythonException(PythonErrorType.VALUE_ERROR, "bad json", null);
              return (Map<String, Object>) argument.get("payload");
            };
    Object result =
        capture(
            () -> {
              try {
                var response = unchecked(() -> handler.customRouteHandler(request));
                if (response.payload() != null)
                  return dict(
                      "kind", "json",
                      "status", response.statusCode(),
                      "body", response.payload());
                return dict("kind", "resp", "headers", response.headers());
              } catch (PythonServerApp.HttpException http) {
                return dict("kind", "exc", "error", "HttpExc", "message", http.detail());
              }
            });
    if (result instanceof Map<?, ?> captured
        && captured.size() == 2
        && captured.containsKey("error")
        && captured.containsKey("message")) {
      result =
          dict("kind", "exc", "error", captured.get("error"), "message", captured.get("message"));
    }
    return dict("result", result);
  }

  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private static <T> T unchecked(ThrowingSupplier<T> operation) {
    try {
      return operation.get();
    } catch (PythonServerApp.HttpException | PythonException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }
}
