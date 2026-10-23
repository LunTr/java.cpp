package io.github.yaulun.demo;

import io.github.yaulun.demo.download.DownloadException;
import io.github.yaulun.demo.download.DownloadSource;
import io.github.yaulun.demo.download.ModelCatalog;
import io.github.yaulun.demo.download.ModelDownloader;
import io.github.yaulun.demo.download.ModelSpec;
import io.github.yaulun.javacpp.BackendDevice;
import io.github.yaulun.javacpp.ChatMessage;
import io.github.yaulun.javacpp.ChatReply;
import io.github.yaulun.javacpp.ChatSession;
import io.github.yaulun.javacpp.ChatTemplate;
import io.github.yaulun.javacpp.ContextParams;
import io.github.yaulun.javacpp.GenerationConfig;
import io.github.yaulun.javacpp.GgufHeader;
import io.github.yaulun.javacpp.JavaCpp;
import io.github.yaulun.javacpp.LlamaContext;
import io.github.yaulun.javacpp.LlamaModel;
import io.github.yaulun.javacpp.LlamaRuntime;
import io.github.yaulun.javacpp.LogLevel;
import io.github.yaulun.javacpp.ModelParams;
import io.github.yaulun.javacpp.NativeConfig;
import io.github.yaulun.javacpp.Sampler;
import io.github.yaulun.javacpp.ThinkingMode;
import io.github.yaulun.javacpp.TokenListener;
import io.github.yaulun.javacpp.compat.python.PythonCall;
import io.github.yaulun.javacpp.compat.python.PythonErrorBridge;
import io.github.yaulun.javacpp.compat.python.PythonException;
import io.github.yaulun.javacpp.exception.ContextOverflowException;
import io.github.yaulun.javacpp.exception.JavaCppException;
import io.github.yaulun.javacpp.exception.TemplateException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Local GGUF streaming chat demo using only the Java.cpp public API. */
public final class InferenceDemoMain {
  private static final Set<String> FLAGS =
      Set.of("--help", "--interactive", "--think", "--greedy", "--verbose");
  private static final Set<String> VALUED =
      Set.of(
          "--natives",
          "--model",
          "--ctx",
          "--batch",
          "--threads",
          "--max-tokens",
          "--ngl",
          "--seed",
          "--prompt",
          "--quant",
          "--source");
  private static final String SYSTEM_PROMPT =
      "你是一个乐于助人的中文助手，回答简洁准确。根据完整对话回答当前问题，记住用户提供的名字；回答姓名问题时直接给出用户的名字。";

  private InferenceDemoMain() {}

  /** Starts chat; exit code 0 means success, 1 runtime failure and 2 invalid arguments. */
  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String[] args) {
    boolean verbose = Arrays.asList(args).contains("--verbose");
    try {
      CliArgs options = CliArgs.parse(args, FLAGS, VALUED);
      if (options.has("--help")) {
        printUsage();
        return 0;
      }
      int length = options.intValue("--ctx", 4096, 16, 262144);
      int batch = options.intValue("--batch", 512, 1, 8192);
      int threads = options.intValue("--threads", ContextParams.defaults().threads(), 1, 1024);
      int maxTokens = options.intValue("--max-tokens", 512, 1, Integer.MAX_VALUE);
      int gpuLayers = options.intValue("--ngl", -1, -1, Integer.MAX_VALUE);
      long seed = options.longValue("--seed", Sampler.RANDOM, 0, Sampler.RANDOM);
      ModelSpec spec = ModelCatalog.byQuant(options.value("--quant").orElse("Q8_0"));
      List<DownloadSource> sources = DownloadSource.parse(options.value("--source").orElse("auto"));
      if (options.has("--prompt") && options.has("--interactive")) {
        throw new CliArgs.UsageException("Choose --prompt or --interactive");
      }
      DemoPaths paths = DemoPaths.detect();
      Path natives = options.value("--natives").map(Path::of).orElse(paths.nativesDir());
      Path model =
          options.value("--model").map(Path::of).orElse(paths.modelsDir().resolve(spec.fileName()));
      // Existing local weights belong to the user's download; only the downloader manages .part
      // files.
      if (!Files.isRegularFile(model) && !options.has("--model")) {
        model =
            new ModelDownloader(ModelDownloader.defaultClient(), System.err)
                .ensure(spec, paths.modelsDir(), sources);
      }
      Path localModel = model;
      GgufHeader header =
          PythonErrorBridge.call(
              PythonCall.modelLoad(localModel), () -> GgufHeader.read(localModel));
      System.out.println(JavaCpp.banner());
      System.out.println(
          "GGUF v" + header.version() + " | " + header.fileSize() + " bytes | " + model);
      LlamaRuntime runtime =
          LlamaRuntime.initialize(
              NativeConfig.builder()
                  .nativesDir(natives)
                  .logLevel(verbose ? LogLevel.INFO : LogLevel.WARN)
                  .logListener((level, line) -> System.err.println("[llama " + level + "] " + line))
                  .build());
      System.out.println(runtime.versions());
      for (BackendDevice device : runtime.devices()) {
        System.out.println(
            "设备: " + device.name() + " | " + device.type() + " | " + device.description());
      }
      if (!runtime.hasGpu() && gpuLayers != 0) {
        System.out.println("当前使用 CPU；可运行 run-check.cmd 查看设备。");
      }
      try (LlamaModel loaded =
              PythonErrorBridge.loadModel(
                  model, ModelParams.builder().gpuLayers(gpuLayers).build());
          LlamaContext context =
              PythonErrorBridge.createContext(
                  loaded,
                  ContextParams.builder()
                      .contextLength(length)
                      .batchSize(batch)
                      .threads(threads)
                      .threadsBatch(threads)
                      .build());
          Sampler sampler =
              options.has("--greedy")
                  ? Sampler.greedy(loaded.vocab())
                  : Sampler.builder()
                      .temperature(1.0f)
                      .topP(1.0f)
                      .topK(20)
                      .minP(0)
                      .presencePenalty(2.0f)
                      .seed(seed)
                      .build(loaded.vocab())) {
        System.out.println("模型: " + loaded.info().summary());
        ChatTemplate template = chooseTemplate(loaded);
        ChatSession session =
            new ChatSession(context, sampler, template)
                .systemPrompt(SYSTEM_PROMPT)
                .thinking(options.has("--think") ? ThinkingMode.ON : ThinkingMode.OFF)
                .generationConfig(
                    new GenerationConfig(maxTokens, () -> Thread.currentThread().isInterrupted()));
        if (options.has("--prompt")) {
          ask(session, options.value("--prompt").orElseThrow());
        } else if (options.has("--interactive")) {
          runInteractive(session);
        } else {
          runScript(session);
        }
      }
      return 0;
    } catch (CliArgs.UsageException e) {
      System.err.println("参数错误: " + e.getMessage());
      printUsage();
      return 2;
    } catch (PythonException e) {
      return failure("本地推理", e, verbose);
    } catch (JavaCppException e) {
      return failure("本地推理", e, verbose);
    } catch (DownloadException e) {
      return failure("模型下载", e, verbose);
    } catch (IllegalArgumentException | NullPointerException e) {
      System.err.println("参数错误: " + e.getMessage());
      printUsage();
      return 2;
    } catch (IllegalStateException e) {
      return failure("对象状态", e, verbose);
    } catch (IOException | UncheckedIOException | SecurityException e) {
      return failure("文件或控制台", e, verbose);
    } catch (LinkageError e) {
      return failure("原生依赖", e, verbose);
    } catch (RuntimeException e) {
      return failure("演示程序", e, true);
    }
  }

  private static ChatTemplate chooseTemplate(LlamaModel model) {
    try {
      ChatTemplate template = ChatTemplate.fromModel(model);
      template.apply(List.of(ChatMessage.user("hello")), true);
      return template;
    } catch (TemplateException e) {
      System.err.println(e.getMessage() + "\n使用内置 chatml 模板。");
      return ChatTemplate.named("chatml");
    }
  }

  private static void runScript(ChatSession session) {
    List<String> prompts =
        List.of(
            "你好！请用两三句话介绍一下你自己。",
            "用三点说明 Java 和 C++ 在内存管理上的区别。",
            "我叫小明，请记住我的名字。",
            "我叫什么名字？请只回答上文中我的名字。");
    ChatReply reply = null;
    for (String prompt : prompts) {
      reply = ask(session, prompt);
    }
    System.out.println(
        reply != null && reply.content().contains("小明") ? "记忆测试通过" : "记忆测试未命中，可用 --greedy 重试。");
  }

  private static void runInteractive(ChatSession session) throws IOException {
    System.out.println("命令: /exit /reset /think on /think off");
    String line;
    while ((line = ConsoleSupport.readLine("你> ")) != null) {
      String command = line.strip();
      if (command.equals("/exit")) {
        return;
      }
      if (command.isBlank()) {
        continue;
      }
      try {
        switch (command) {
          case "/reset" -> {
            session.reset();
            System.out.println("历史已清空。");
          }
          case "/think on" -> {
            session.thinking(ThinkingMode.ON);
            System.out.println("思考已开启。");
          }
          case "/think off" -> {
            session.thinking(ThinkingMode.OFF);
            System.out.println("思考已关闭。");
          }
          default -> ask(session, line);
        }
      } catch (ContextOverflowException e) {
        System.err.println(e.getMessage());
      } catch (JavaCppException | IllegalArgumentException | IllegalStateException e) {
        System.err.println(e.getMessage());
      }
    }
  }

  private static ChatReply ask(ChatSession session, String prompt) {
    System.out.println("用户: " + prompt);
    System.out.print("助手: ");
    ChatReply reply =
        session.send(
            prompt,
            new TokenListener() {
              private boolean reasoningStarted;

              @Override
              public void onToken(String piece) {
                if (reasoningStarted) {
                  System.out.print("\n助手: ");
                  reasoningStarted = false;
                }
                System.out.print(piece);
                System.out.flush();
              }

              @Override
              public void onReasoning(String piece) {
                if (!reasoningStarted) {
                  System.out.print("[思考] ");
                  reasoningStarted = true;
                }
                System.out.print(piece);
                System.out.flush();
              }
            });
    System.out.println("\n  " + reply.stats().summary());
    if (reply.droppedTurns() > 0) {
      System.out.println("已裁剪旧轮次: " + reply.droppedTurns());
    }
    return reply;
  }

  private static int failure(String stage, Throwable failure, boolean verbose) {
    System.err.println(
        failure instanceof PythonException
            ? failure.toString()
            : stage + ": " + failure.getMessage());
    if (verbose) {
      failure.printStackTrace(System.err);
    }
    System.err.println("运行 run-check.cmd 查看依赖检查报告。");
    return 1;
  }

  private static void printUsage() {
    System.out.println(
        "Usage: run-demo.cmd [--model path] [--natives dir] [--interactive | --prompt text]");
    System.out.println(
        "  --ngl N (-1 auto, 0 CPU) --ctx 16..262144 --max-tokens N --batch 1..8192");
    System.out.println("  --threads N --greedy --seed 0..4294967295 --think --verbose --help");
    System.out.println("  --quant Q8_0|Q4_K_M --source auto|modelscope|hf-mirror|huggingface");
  }
}
