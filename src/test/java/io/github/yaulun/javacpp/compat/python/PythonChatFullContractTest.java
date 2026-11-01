package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.dict;
import static org.junit.jupiter.api.Assertions.*;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonChatFullContractTest {
  @TestFactory
  Stream<DynamicTest> matchesOriginalBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-chatfull-contracts.tsv"),
            "Run generate_chatfull_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "chatfull:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object result = drive(map(read(parts[1])));
                      ByteArrayOutputStream out = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(out), result);
                      String actual = Base64.getEncoder().encodeToString(out.toByteArray());
                      if (!parts[2].equals(actual)) {
                        java.nio.file.Files.write(
                            java.nio.file.Path.of(".verify/actual-" + i + ".b64"), actual.getBytes());
                        fail("case " + i + " differs");
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

  private static int i(Object v) {
    return ((Number) v).intValue();
  }

  private record Handle(String name) {}

  private static Object show(Object v) {
    if (v instanceof Handle h) return h.name();
    if (v instanceof byte[] b) return "hex:" + HexFormat.of().formatHex(b);
    if (v instanceof List<?> l) return l.stream().map(PythonChatFullContractTest::show).toList();
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> o = new LinkedHashMap<>();
      for (var e : m.entrySet()) o.put(String.valueOf(e.getKey()), show(e.getValue()));
      return o;
    }
    return v;
  }

  private static final class Stubs
      implements PythonChatHandlersFull.JinjaEnv,
          PythonChatHandlersFull.MtmdPort,
          PythonChatHandlersFull.MediaLlama,
          PythonChatFormatHandlers.GrammarPort,
          PythonChatFormatHandlers.JinjaPort,
          PythonChatFormatHandlers.TokenizerPort {
    final Map<String, Object> kase;
    final List<Object> trace;
    final List<Object> prompts;
    final Iterator<Object> completions;
    final List<Runnable> callbacks = new ArrayList<>();

    @SuppressWarnings("unchecked")
    Stubs(Map<String, Object> kase, List<Object> trace) {
      this.kase = kase;
      this.trace = trace;
      this.prompts = new ArrayList<>(list(kase.getOrDefault("prompts", List.of())));
      this.completions = list(kase.getOrDefault("completions", List.of())).iterator();
    }

    // jinja env
    public void init(Map<String, Object> kw, List<Object> ext) {
      trace.add(List.of("env_init", kw.keySet().stream().sorted().toList()));
    }

    public void setFilter(String n, Object f) {}

    public void buildTemplate(String t) {
      trace.add(List.of("from_string", t.length()));
    }

    public String render(Map<String, Object> ctx) {
      trace.add(List.of("render", show(ctx)));
      if (prompts.isEmpty()) throw new IndexOutOfBoundsException("pop from empty list");
      return (String) prompts.remove(0);
    }

    public void envFromString(String t) {
      trace.add(List.of("env_init", List.of("autoescape", "undefined")));
      trace.add(List.of("from_string", t.length()));
    }

    // mtmd
    public Object contextParamsDefault() {
      trace.add(List.of("params_default"));
      return new LinkedHashMap<String, Object>();
    }

    public void suppress(boolean d) {
      trace.add(List.of("suppress_stdout_stderr", d));
    }

    public String flashAttnEnabled() {
      return "ENABLED";
    }

    public String flashAttnDisabled() {
      return "DISABLED";
    }

    public Object initFromFile(byte[] p, Object m, Object par) {
      trace.add(List.of("mtmd_init_from_file", "hex:" + HexFormat.of().formatHex(p), "model", "params"));
      return new Handle("mtmd_init_from_file");
    }

    public boolean supportVision(Object c) {
      trace.add(List.of("mtmd_support_vision", ((Handle) c).name()));
      return true;
    }

    public void free(Object c) {
      trace.add(List.of("mtmd_free", ((Handle) c).name()));
    }

    public Object defaultMarker() {
      return "<marker>".getBytes(StandardCharsets.UTF_8);
    }

    public Object bitmapInitFromBuf(Object c, byte[] d, int n, boolean s) {
      throw io.github.yaulun.javacpp.python.PythonValues.error(
          PythonErrorType.TYPE_ERROR,
          "unsupported operand type(s) for *: 'function' and 'int'");
    }

    public Object inputText() {
      return new Handle("input_text");
    }

    public Object chunksInit() {
      trace.add(List.of("mtmd_input_chunks_init"));
      return new Handle("mtmd_input_chunks_init");
    }

    public int mtmdTokenize(Object c, Object ch, Object t, Object b, int n) {
      trace.add(List.of("mtmd_tokenize", ((Handle) c).name(), ((Handle) ch).name(), "input_text", "bmap_arr", n));
      return 0;
    }

    public int chunksSize(Object c) {
      trace.add(List.of("mtmd_input_chunks_size", ((Handle) c).name()));
      return 0;
    }

    public Object chunksGet(Object c, int x) {
      return null;
    }

    public int chunkType(Object c) {
      return 0;
    }

    public int textChunkType() {
      return 0;
    }

    public int imageChunkType() {
      return 1;
    }

    public int audioChunkType() {
      return 2;
    }

    public List<Object> chunkTokensText(Object c) {
      return List.of();
    }

    public int chunkNTokens(Object c) {
      return 0;
    }

    public int evalChunkSingle(Object a, Object b, Object c, int p, int s, int n, boolean l, Object o) {
      return 0;
    }

    public void chunksFree(Object c) {
      trace.add(List.of("mtmd_input_chunks_free", ((Handle) c).name()));
    }

    public void bitmapFree(Object b) {
      trace.add(List.of("mtmd_bitmap_free", b instanceof Handle h ? h.name() : b));
    }

    public void printErr(Object v) {
      trace.add(List.of("print_err", show(v)));
    }

    // llama
    public Object createCompletion(Map<String, Object> kw) {
      trace.add(List.of("create_completion", show(kw)));
      Object next = completions.hasNext() ? completions.next() : null;
      if (next instanceof List<?> l && !l.isEmpty() && "STREAM".equals(l.get(0))) {
        return l.get(1);
      }
      return next;
    }

    public void envInit(List<String> keys, String template) {
      trace.add(List.of("env_init", keys));
      trace.add(List.of("from_string", template.length()));
    }

    public boolean verbose() {
      return (boolean) kase.getOrDefault("verbose", false);
    }

    public Object detokenize(List<Object> t) {
      trace.add(List.of("detokenize", t));
      return bytes(kase.get("detok"));
    }

    private static byte[] bytes(Object v) {
      return v == null ? "TXT".getBytes(StandardCharsets.UTF_8) : HexFormat.of().parseHex((String) v);
    }

    public Object tokenEos() {
      return kase.getOrDefault("eos", 2);
    }

    public Object tokenBos() {
      return kase.getOrDefault("bos", 1);
    }

    public Map<String, Object> metadata() {
      return map(kase.getOrDefault("metadata", Map.of()));
    }

    public int nThreads() {
      return 4;
    }

    public Object contextParamsFlashAttn() {
      return "ENABLED";
    }

    public Object model() {
      return new Handle("model");
    }

    public Object ctx() {
      return new Handle("ctx");
    }

    public void reset() {
      trace.add(List.of("reset"));
    }

    public void kvCacheClear() {
      trace.add(List.of("kv_cache_clear"));
    }

    public int nTokens() {
      return i(kase.getOrDefault("n_tokens", 0));
    }

    public void setNTokens(int n) {}

    public int nCtx() {
      return 4096;
    }

    public int nBatch() {
      return 512;
    }

    public void eval(List<Object> t) {
      trace.add(List.of("eval", t));
    }

    public Object inputIdsSlice(int n) {
      return List.of();
    }

    public void stackCallback(Runnable cb) {
      callbacks.add(cb);
    }

    public boolean pathExists(String p) {
      return true;
    }

    // grammar port
    public Object fromString(String g) {
      trace.add(List.of("from_string", g));
      return new Handle("grammar");
    }

    public Object fromString(String g, boolean v) {
      trace.add(List.of("from_string", g, Map.of("verbose", v)));
      return new Handle("grammar");
    }

    public Object fromJsonSchema(String s, boolean v) {
      trace.add(List.of("from_json_schema", s, Map.of("verbose", v)));
      if ((boolean) kase.getOrDefault("schemaFail", false))
        throw io.github.yaulun.javacpp.python.PythonValues.error(PythonErrorType.VALUE_ERROR, "bad schema");
      return new Handle("schema_grammar");
    }

    public Object jsonSchemaToGbnf(String j) {
      trace.add(List.of("json_schema_to_gbnf", j));
      return "gbnf:" + j.length();
    }

    public String jsonGbnf() {
      return "JSON_GBNF_TEXT";
    }

    public void printOut(Object v) {
      trace.add(List.of("print", String.valueOf(v)));
    }

    // tokenizer port
    public Object fromPretrained(Object p) {
      trace.add(List.of("from_pretrained", p));
      return new Handle("tokenizer");
    }

    public void useDefaultSystemPrompt(Object t, boolean v) {}

    public Object applyChatTemplate(Object t, Object m, Map<String, Object> kw) {
      return kase.getOrDefault("tokenizer", "T");
    }

    public Object eosToken(Object t) {
      return "</s>";
    }
  }

  private static Object capture(java.util.concurrent.Callable<Object> a) {
    try {
      return a.call();
    } catch (PythonException e) {
      return dict("error", e.type().pythonName(), "message", e.getMessage() == null ? "" : e.getMessage());
    } catch (Exception e) {
      return dict("error", e.getClass().getSimpleName(), "message", e.getMessage() == null ? "" : e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static Object run(Map<String, Object> kase, List<Object> step, Map<String, Object> box,
      Stubs stubs, List<Object> trace) {
    String name = (String) step.get(0);
    try {
      switch (name) {
        case "j2parse" -> {
          List<Object> t = trace;
          var p = new PythonChatHandlersFull.JinjaParser() {
            public void skip(int n) { t.add(List.of("skip", n)); }
            public Object parseStatements(Object e, boolean d) {
              t.add(List.of("parse_statements", e, d));
              return "nodes";
            }
          };
          return PythonChatHandlersFull.ignoreGenerationTagsParse(p);
        }
        case "j2init" -> {
          box.put("fmt", new PythonChatHandlersFull.Jinja2ChatFormatter(
              stubs, (String) step.get(1), "EOS", "BOS", (boolean) step.get(2), list(step.get(3))));
          return "ok";
        }
        case "j2call" -> {
          var r = ((PythonChatHandlersFull.Jinja2ChatFormatter) box.get("fmt")).call(map(step.get(1)));
          return show(r);
        }
        case "j2handler" -> {
          return ((PythonChatHandlersFull.Jinja2ChatFormatter) box.get("fmt")).toChatHandler();
        }
        case "fnV1" -> {
          return show(PythonChatHandlersFull.functionaryChatHandler(stubs, stubs, stubs, map(step.get(1))));
        }
        case "fnV2" -> {
          PythonChatHandlersFull.FunctionaryV2 v2 = (PythonChatHandlersFull.FunctionaryV2)
              box.computeIfAbsent("v2", k -> new PythonChatHandlersFull.FunctionaryV2(
                  "v2", stubs, stubs, stubs, new Handle("tokenizer")));
          return show(v2.handle(map(step.get(1))));
        }
        case "lvinit", "mtinit" -> {
          boolean lv = name.equals("lvinit");
          PythonChatHandlersFull.MediaHandler h = new PythonChatHandlersFull.MediaHandler();
          PythonChatHandlersFull.mediaInit(h, (String) step.get(1), (boolean) step.get(2),
              lv ? null : (Boolean) step.get(3), (boolean) kase.getOrDefault("exists", true));
          box.put("h", h);
          box.put("lv", lv);
          return "ok";
        }
        case "initCtx" -> {
          PythonChatHandlersFull.initMtmdContext(stubs, stubs,
              (PythonChatHandlersFull.MediaHandler) box.get("h"), (boolean) box.get("lv"));
          return "ok";
        }
        case "mtmdFree" -> {
          for (Runnable cb : stubs.callbacks) cb.run();
          return "ok";
        }
        case "loadImg" -> {
          return show(PythonChatHandlersFull.loadImage((String) step.get(1)));
        }
        case "loadImgStatic" -> {
          return show(PythonChatHandlersFull.loadImageStatic((String) step.get(1)));
        }
        case "bitmap" -> {
          return show(PythonChatHandlersFull.createBitmapFromBytes(stubs,
              (PythonChatHandlersFull.MediaHandler) box.get("h"),
              HexFormat.of().parseHex((String) step.get(1))));
        }
        case "getTmpl" -> {
          return PythonChatHandlersFull.getChatTemplate(stubs, "MTMDChatHandler");
        }
        case "lvCall" -> {
          return show(PythonChatHandlersFull.mediaCall(stubs, stubs, stubs,
              (PythonChatHandlersFull.MediaHandler) box.get("h"), "llava", "TPL",
              "A chat between a curious human and an artificial intelligence assistant. The assistant gives helpful, detailed, and polite answers to the human's questions.",
              map(step.get(1))));
        }
        case "mtCall" -> {
          return show(PythonChatHandlersFull.mediaCall(stubs, stubs, stubs,
              (PythonChatHandlersFull.MediaHandler) box.get("h"), "mtmd", null, null, map(step.get(1))));
        }
        case "chatml" -> {
          return show(PythonChatHandlersFull.chatmlFunctionCalling(stubs, stubs, stubs, "TPL", map(step.get(1))));
        }
        case "fromPre" -> {
          var hub = new PythonChatHandlersFull.HfHub() {
            public void validateRepoId(String r) {
              trace.add(List.of("validate", r));
            }

            public List<Object> ls(String r) {
              trace.add(List.of("ls", r));
              return list(kase.getOrDefault("files", List.of()));
            }

            public String download(Map<String, Object> kw) {
              trace.add(List.of("hf_hub_download", show(kw)));
              return (String) kase.getOrDefault("dl", "/dl/f");
            }
          };
          PythonChatHandlersFull.fromPretrained(hub, (String) step.get(1),
              (String) kase.get("pat"), step.size() > 3 ? step.get(3) : null,
              step.size() > 4 ? step.get(4) : "auto", step.size() > 5 ? step.get(5) : null,
              Map.of());
          return "fn";
        }
        default -> throw new AssertionError("unknown op " + name);
      }
    } catch (PythonException e) {
      return dict("error", e.type().pythonName(), "message", e.getMessage());
    } catch (RuntimeException e) {
      String ename = e instanceof IndexOutOfBoundsException ? "IndexError" : e.getClass().getSimpleName();
      return dict("error", ename,
          "message", e.getMessage() == null ? "" : e.getMessage());
    }
  }

  private static Object drive(Map<String, Object> kase) {
    List<Object> trace = new ArrayList<>();
    Stubs stubs = new Stubs(kase, trace);
    List<Object> results = new ArrayList<>();
    Map<String, Object> box = new HashMap<>();
    for (Object op : list(kase.get("ops"))) {
      List<Object> step = list(op);
      results.add(capture(() -> run(kase, step, box, stubs, trace)));
    }
    return dict("results", results, "trace", trace);
  }
}
