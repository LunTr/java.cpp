# Java.cpp

Java 21 + JNA 5.19.1，直接调用 llama.cpp b11669 C API 的面向对象封装。

工程保留一个根 Maven 项目。`src/Java_Cpp` 只存放必要封装源码，demo 与测试分别放在原工程的主源码和测试目录。原始 `llama.cpp/` 用作头文件与行为参考，构建过程直接使用官方 DLL。

```text
pom.xml                         唯一的工程 POM
src/Java_Cpp/io/github/yaulun/javacpp/
  *.java                        公开 API、参数、消息、资源对象
  exception/                    分层异常与错误码
  internal/                     JNA 接口、原生结构体、加载器、日志桥
src/main/java/org/example/Main.java              原 IDE 入口
src/main/java/io/github/yaulun/demo/              检查与对话演示
src/test/java/io/github/yaulun/                   单元测试、原生集成测试
src/assembly/demo.xml                            demo 打包清单
natives/                        本地运行依赖
gguf/                          本地模型（现有目录）
```

## 构建和运行

在根目录运行：

```powershell
.\mvnw.cmd clean verify
.\run-check.cmd
.\run-demo.cmd --interactive
```

`run-demo.cmd` 默认执行文档约定的四轮中文对话。单轮提问：

```powershell
.\run-demo.cmd --model "gguf\Qwen3.5-0.8B-Q8_0.gguf" --greedy --prompt "你好，请介绍一下自己。"
.\run-demo.cmd --ngl 0 --interactive
```

原来的 `org.example.Main.main` 也可在 IDEA 中直接运行，传入 `--check` 执行检查、传入 `--interactive` 启动对话。交互命令为 `/exit`、`/reset`、`/think on`、`/think off`。参数说明通过 `run-demo.cmd --help` 查看。

构建产物：

- `target/java-cpp-1.0.0-SNAPSHOT.jar`：仅包含封装类，使用时另加 JNA 依赖。
- `target/demo-all.jar`：包含 demo、封装和 JNA，可直接 `java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target/demo-all.jar --interactive`。

两种 JAR 均通过根 POM 生成。模型、DLL、上游 C++ 仓库和测试类单独存放。

## 运行依赖与路径

JDK 21 x64 为必要依赖；Maven Wrapper 自动准备 Maven 3.9.16。

```powershell
.\setup-natives.ps1 -Backend Cpu
.\setup-natives.ps1 -Backend Cuda
```

准备脚本下载官方 b11669 包，校验发布包 SHA-256 后解压。CUDA 模式使用 CUDA 12.4 运行库。默认优先选 `natives/llama-b11669-win-cuda-12.4-x64`，其次为 `natives/b11669-cpu`。原生目录优先级为 `--natives`、`-Djavacpp.natives`、`JAVACPP_NATIVES`、默认目录；模型优先级为 `--model`、模型目录设置、默认 `gguf`。

缺少本地模型且未指定 `--model` 时，demo 使用任务文档固定 revision 下载 Q8_0 或 Q4_K_M。下载器支持多源、续传、60 秒停滞超时、文件锁、大小与 SHA-256 校验及完成后改名。已有不同内容的下载目标会保存在 `.invalid-*` 文件中。

本地已有 GGUF 通过文件头和实际加载验证；检查报告记录其实算摘要。当前下载的 Q8 文件为 833592096 字节，含额外 MTP 张量，与任务文档旧 revision 的 811843840 字节不同，演示可直接使用当前本地文件；依赖检查对版本差异显示 WARN，并继续执行实际加载和推理检查。

## API 与生命周期

```java
LlamaRuntime.initialize(NativeConfig.builder().nativesDir(nativesPath).build());
try (LlamaModel model = LlamaModel.load(modelPath, ModelParams.defaults());
    LlamaContext context = model.newContext(ContextParams.defaults());
    Sampler sampler = Sampler.greedy(model.vocab())) {
  ChatSession session = new ChatSession(context, sampler, ChatTemplate.fromModel(model))
      .systemPrompt("你是一个简洁的中文助手。")
      .generationConfig(new GenerationConfig(128, null));
  ChatReply reply = session.send("你好", System.out::print);
}
```

调用方按 sampler、context、model 顺序释放资源；关闭模型会等待当前操作并关闭子上下文。重复关闭有效，关闭后的操作抛出 `IllegalStateException`。单个 context、sampler、session 会检测并发访问与回调重入。用户输出监听器异常直接传回调用方，失败后的 context 需先 `clear()`；session 下轮发送会自动清空。历史在生成成功后提交，异常与取消保留原历史。

公开 API 的状态字段为私有，参数和返回数据采用不可变对象；构造器按资源所有权控制可见性。JNA 类型留在 `internal`。该包的 C 函数和结构体成员保留上游名称，便于逐项核对 ABI。JNA 反射需要公开接口和结构体字段，demo 仅调用公开 Java API。

## 测试与规范

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify -Pnative "-Djavacpp.natives=E:\JavaKotlin\java.cpp\natives\b11669-cpu" "-Djavacpp.model=E:\JavaKotlin\java.cpp\gguf\Qwen3.5-0.8B-Q8_0.gguf"
.\mvnw.cmd spotless:apply
```

普通测试覆盖参数、GGUF 文件头、UTF-8 和思考标签分片、封装边界、日志回调、命令行、PE 版本和本地 HTTP 续传。`native` profile 加入真实模型加载、分词、模板、四种停止原因、采样复现、历史回滚、记忆和生命周期测试。缺少原生目录或模型路径时，原生测试跳过。也可用 `JAVACPP_NATIVES` 与 `JAVACPP_MODELS` 配置测试路径。

`verify` 强制 Google Java Format；命名、权限和包依赖另有契约测试。源码采用 UTF-8，Java 缩进为两空格。实际验证结果见 [测试记录](docs/VALIDATION.md)，ABI 来源见 [依赖说明](THIRD-PARTY.md)。

Java.cpp 与 bytedeco/JavaCPP 为独立项目。

## Python llama_cpp 函数总表与迁移核对

核对日期：2026-10-23。基线为 [llama-cpp-python 0.3.36](https://pypi.org/project/llama-cpp-python/0.3.36/)，固定源码 commit 为 [`1652066e0af45f2313b339670ef9555e8a54e545`](https://github.com/abetlen/llama-cpp-python/tree/1652066e0af45f2313b339670ef9555e8a54e545)，其 vendor/llama.cpp commit 为 `0c1e57098bba43ac29e6e3b677cdceebdd22334f`。当前 Java 使用 b11669 / `620d2225cc436ab0b001e3200e590e3b286ef9ab`。

**核对结论：当前整体行为与 Python 原版仍有差异；751 项函数的完整一致性验收完成 0 项。已加入 Python 错误桥接层，并以原版分支生成的 300 条契约样例核对选定异常/警告及参数规则；这些样例覆盖具体场景，不能换算为整个函数已完全兼容。**

### 统计口径与当前进度

使用 Python AST 枚举该固定版本 `llama_cpp/` 下全部 25 个 Python 文件，包括可选 server、多模态、内部方法、属性 getter、特殊方法和嵌套函数。按“文件 + 限定函数名”去重：786 个 `def/async def` 定义合并为 **751 项**，35 处同名绑定占位/兼容包装重复定义合并列出，全部定义行号保存在审计 JSON 中。

751 项中，702 项为模块函数/类方法，49 项为嵌套函数；核心包 712 项，可选 server 39 项。隐式继承成员、lambda、导入重导出、赋值别名、运行时生成的 callable 和 C++ 函数均不重复计入。该总数表示源码函数定义项，运行时 callable 数随可选依赖及动态注册变化。

| 统计项 | 数量 |
|---|---:|
| Python 去重函数/方法总数 | 751 |
| 已有完整同名 JNA 绑定 | 55 |
| 已覆盖独立能力（含聊天、缓存、处理器及 Llama 辅助方法） | 79 |
| 本清单打勾项合计 | 134 / 751（17.84%） |
| 部分覆盖，保持未勾选 | 50 |
| 当前缺少明确对应迁移项 | 567 |
| 已通过 Python 全行为、报错、警告一致性验收 | **0** |

原版 `llama_cpp.py` 共 256 项，其中 255 个公开 C API 函数和 1 个警告辅助函数。Java 已声明其中 57 个同名入口；上下文结构相关 2 项列为部分覆盖，其余 55 项标记绑定完成。Java 另有 9 个 ggml 补充入口，单独提供设备枚举等能力，不增加 Python 迁移计数。

高层 `Llama` 有 31 个普通公开命名成员（含 4 个属性 getter），其中 15 项已有查询或窄范围辅助能力；构造、生成、聊天、分词等继续逐项对照。构造器、`__call__`、析构、私有辅助成员在下表另行完整列出。

勾选含义：`[x]` 表示该 C 入口绑定或窄范围能力已实现；**勾选不代表 Python 完全一致**。`[ ]` 表示部分覆盖或尚无明确迁移项，具体差异写在函数后。未单独对应的内部辅助动作可能由 Java/JNA 间接承担，未计入完成项。清单覆盖多个分层，同一能力可能出现在 C 入口、内部包装和高层 API；各项分别计数，不将它们解释为独立产品能力。差异证据和桥接用法见 [Python 兼容核对](docs/PYTHON_COMPATIBILITY.md)，机器可读清单见 [python-api-audit.json](docs/python-api-audit.json)。

### Java 错误到 Python 错误的桥接

桥接位于 `src/Java_Cpp/io/github/yaulun/javacpp/compat/python`，按调用操作及结构化 NativeFailure 映射异常类别和原版消息，并保留原 Java 异常作为 cause。模型加载、上下文创建已接入推理 demo。Java 调用方捕获 PythonException，通过 type() 区分类别，getMessage() 获取消息，toString() 获取 `ValueError: ...` 格式。

```java
try (LlamaModel model = PythonErrorBridge.loadModel(modelPath, ModelParams.defaults())) {
  // Continue with PythonErrorBridge.createContext/tokenize/decode/sample as needed.
} catch (PythonException failure) {
  System.err.println(failure); // Python category and original message.
}
GenerationConfig config = PythonErrorBridge.completionConfig(0, promptTokens, contextLength);
```

completionConfig 对 null、0、负数按剩余上下文处理，并截断过大的正数；桥接的原始 bytes 分词入口保留 NUL 等字节。PythonWarnings 支持重复 BOS 和旧符号警告原文、来源格式、默认弃用过滤及六种 action 的已测 registry 路径。未知错误保留原 Java 异常，translate 返回空 Optional，不猜测 Python 类型。原生版本、完整返回结构、未实现功能、完整 Python warnings 过滤器与 traceback 继续列为差异。

### 行为、报错与警告差异

| 场景 | Python 0.3.36 | 当前 Java |
|---|---|---|
| 默认模型/上下文 | `n_gpu_layers=0`，`n_ctx=512`，`flash_attn=False` | `gpuLayers=-1`，`contextLength=4096`，FlashAttention.AUTO |
| completion token 上限 | 默认 16；`None` 或非正数按剩余上下文处理 | 原 API 默认 512 且要求正数；桥接 completionConfig 对齐非正数和截断规则 |
| 高层分词/反分词 | bytes、List[int]，包含 special 和 prev_tokens 契约 | String、int[]；UTF-8 解码与 NUL 校验另行实现 |
| 生成与聊天 | 生成器、OpenAI 风格 dict、增量复用、stop/echo/logprobs/tools 等 | 回调、GenerationResult/ChatReply、整段 prefill、独立历史裁剪 |
| 缺失模型 | ValueError，Model path does not exist 路径消息 | 原 API 保留 E201；桥接提供相同 ValueError 类别及消息 |
| decode 返回错误 | RuntimeError，包含原始返回码 | 原 API 保留错误码；桥接用 NativeFailure 还原原版类别及消息 |
| 原生上下文结构 | 37 字段，Windows x64 160 字节 | 38 字段，168 字节，多出 moe_cache_size |
| 日志输出 | 全局 logger、verbose 和 stderr、按原始片段输出 | WARN 默认阈值、监听器、逐行缓存，demo 添加前缀；原生日志仍有差异 |
| Python 级警告 | 重复 BOS 的 RuntimeWarning、旧函数 DeprecationWarning 等 | 已有类别、原文、格式和过滤规则桥接，尚无完整 Python warnings 环境 |
| 内存/资源状态 | Python 句柄、ExitStack、析构和 Python 异常路径 | AutoCloseable、私有句柄、关闭/并发/重入校验 |

### 静态聊天函数逐项移植

本批移植 37 项：23 个静态 formatter、9 个共用辅助函数、1 个 GGUF 模板识别函数和 4 个 completion 转换函数。Java 入口为 `compat.python.PythonChatFormats`、`PythonChatCompletions`，返回 prompt/stop 或保留原版字段及 null 的可变 Map。流式转换采用惰性 Iterator，首个 role chunk 先于 choices 访问，终止 delta 为空字典。Gemma 的原版 debug 消息通过显式监听器接收。

固定原版完整函数体及 CPython repr 产生 10,376 条对照样例，比较 UTF-16 内容（包括未配对代理字符）、空值、角色过滤、异常类别/原文、字典顺序、debug 日志、数值和 Unicode 边界及出错前已产出的流式块。来源的 25 个文件均重新验证 Git blob SHA-1 / SHA-256，并用 AST 复核 786 个定义、751 个去重项。生成脚本已纳入版本控制，见 [本批验证记录](docs/PYTHON_CHAT_COMPATIBILITY.md)。

清单尚未打勾 617 项，其中部分覆盖 50 项、尚无对应实现 567 项。所有 751 项继续保留全输入一致性待验收状态。

### RAM 缓存、状态与处理器逐项移植

本批新增 12 项：RAM 缓存 6 项、最长 token 前缀 1 项、LlamaState 构造 1 项、logits/停止列表 2 项及最小 token 处理器 2 项。738 组原版完整函数体对照覆盖状态与缓存操作序列、任意精度容量/大小、空键、前缀并列、命中后排序、负索引、原地修改和列表回调时序。见 [本批记录](docs/PYTHON_STATE_COMPATIBILITY.md)。

### llama.py 状态访问与数值辅助方法（2026-10-28）

本轮迁移 8 项：`Llama._input_ids`、`_scores`、`eval_tokens`、`eval_logits`、`set_cache`、`set_seed`、`reset` 和 `logits_to_logprobs`，Java 入口为 `compat.python.PythonLlama`。它接收调用方已求值的 intc/single 缓冲，提供可写共享视图、按原版容量截取的独立 List 快照、缓存引用及任意精度种子赋值，并保留 reset 的状态更新和 recurrent/hybrid 内存回调顺序。

log-softmax 支持 single 向量及矩形二维数组的 axis 0/1/-1/-2；固定原版的 374 组对照覆盖切片、共享修改、快照、种子、重置分支与异常时序、数值边界。浮点对照采用绝对与相对 `4e-6` 容差。完整 `Llama` 构造、eval/generate 连接、Python deque 的后续变更操作、多维 NumPy 接口及数值警告列入后续范围。接口示例、8 项映射和验证结果见 [本轮记录](docs/PYTHON_LLAMA_COMPATIBILITY.md)。

### llama.py 与 LlamaTokenizer 分词链路（2026-10-28，第二轮）

本轮迁移 8 项：Llama 的 tokenize/detokenize/tokenizer，以及 LlamaTokenizer 的构造、tokenize/detokenize/encode/decode。入口为 `PythonLlama`、`PythonLlamaTokenizer`，支持原始 bytes、两层不同的 special 默认值、UTF-8 errors=ignore、自定义分词器及每次新建的模型包装器。原版 `324` 组对照核对参数、调用轨迹、引用和返回内容。

`PythonLlamaTokenizer.modelFromVocab(model.vocab())` 可接入现有模型；字节反分词保留原版 32 字节 piece 缓冲及 BOS 首空格规则。原生依赖继续锁定 b11669，固定 Python vendor revision 的全模型分词对照列入后续范围。8 项映射、用法和验证结果见 [分词迁移记录](docs/PYTHON_TOKENIZER_COMPATIBILITY.md)。

### 测试数据管理

`src/test/resources/` 全部由 `.gitignore` 忽略，测试数据及 Base64 样例保留本地。生成脚本、固定来源和小型摘要元数据纳入版本控制。清洁检出后先使用 [生成说明](tools/python_compat/README.md) 恢复五组数据，再执行 Maven 验证；`AGENTS.md` 已记录后续维护规则。

### 全量函数清单

#### `llama_cpp/__init__.py` — 0 项，勾选 0，部分 0

本文件没有显式函数/方法定义，导入、类型声明和库对象加载不增加函数数。

#### `llama_cpp/_ctypes_extensions.py` — 5 项，勾选 0，部分 1

- [ ] [`llama_cpp._ctypes_extensions.load_shared_library`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_ctypes_extensions.py#L26) — 部分覆盖 → `internal.NativeLoader.load`；Java 仅支持 Windows x64，目录选择、ABI 校验和异常契约不同。
- [ ] [`llama_cpp._ctypes_extensions.ctypes_function_for_shared_library`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_ctypes_extensions.py#L140) — 未迁移。
- [ ] [`llama_cpp._ctypes_extensions.ctypes_function_for_shared_library.ctypes_function`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_ctypes_extensions.py#L143) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._ctypes_extensions.ctypes_function_for_shared_library.ctypes_function.decorator`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_ctypes_extensions.py#L146) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._ctypes_extensions._byref`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_ctypes_extensions.py#L161) — 未迁移。

#### `llama_cpp/_ggml.py` — 0 项，勾选 0，部分 0

本文件没有显式函数/方法定义，导入、类型声明和库对象加载不增加函数数。

#### `llama_cpp/_internals.py` — 120 项，勾选 9，部分 29

- [ ] [`llama_cpp._internals.LlamaModel.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L36) — 部分覆盖 → `LlamaModel.load`；Python 参数结构与 Java Builder 范围、错误类别和校验顺序不同。
- [ ] [`llama_cpp._internals.LlamaModel.__init__.free_model`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L72) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._internals.LlamaModel.close`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L80) — 部分覆盖 → `LlamaModel.close`；Java 关闭状态、并发和重入契约另行实现。
- [ ] [`llama_cpp._internals.LlamaModel.__del__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L88) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.vocab_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L91) — 未迁移。
- [x] [`llama_cpp._internals.LlamaModel.n_vocab`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L94) — 能力已覆盖 → `Vocab.size`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [x] [`llama_cpp._internals.LlamaModel.n_ctx_train`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L97) — 能力已覆盖 → `LlamaModel.info().contextTrain`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [x] [`llama_cpp._internals.LlamaModel.n_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L100) — 能力已覆盖 → `LlamaModel.info().embedding`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [ ] [`llama_cpp._internals.LlamaModel.rope_freq_scale_train`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L103) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.desc`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L106) — 部分覆盖 → `LlamaModel.info().description`；Python 固定 1024 字节缓冲；Java 可按返回长度扩容。
- [x] [`llama_cpp._internals.LlamaModel.size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L111) — 能力已覆盖 → `LlamaModel.info().sizeBytes`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [x] [`llama_cpp._internals.LlamaModel.n_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L114) — 能力已覆盖 → `LlamaModel.info().parameters`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [ ] [`llama_cpp._internals.LlamaModel.get_tensor`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L117) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_get_text`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L122) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_get_score`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L125) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_get_attr`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L128) — 未迁移。
- [x] [`llama_cpp._internals.LlamaModel.token_bos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L133) — 能力已覆盖 → `Vocab.bos`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [x] [`llama_cpp._internals.LlamaModel.token_eos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L136) — 能力已覆盖 → `Vocab.eos`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [ ] [`llama_cpp._internals.LlamaModel.token_cls`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L139) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_sep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L142) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_nl`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L145) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_prefix`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L148) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_middle`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L151) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_suffix`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L154) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.token_eot`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L157) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.add_bos_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L160) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.add_eos_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L163) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaModel.tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L168) — 部分覆盖 → `Vocab.tokenize`；输入字节契约、初始容量及错误类型/文本不同。
- [ ] [`llama_cpp._internals.LlamaModel.token_to_piece`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L186) — 部分覆盖 → `Vocab.tokenToBytes`；Python 固定返回 32 字节缓冲；Java 返回有效长度并按需扩容。
- [ ] [`llama_cpp._internals.LlamaModel.detokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L191) — 部分覆盖 → `Vocab.detokenize`；Python 逐 token 拼接 bytes 并处理 BOS 首空格；Java 调用原生 detokenize 后解码。
- [ ] [`llama_cpp._internals.LlamaModel.metadata`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L210) — 部分覆盖 → `LlamaModel.metadata`；Python 可变 dict；Java 不可修改 Map，读取缓冲和错误处理不同。
- [ ] [`llama_cpp._internals.LlamaModel.default_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L242) — 部分覆盖 → `ModelParams.defaults / internal.LlamaLibrary.llama_model_default_params`；高层配置仅开放部分字段且有 Java 默认值；底层同名绑定另列。
- [ ] [`llama_cpp._internals.LlamaContext.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L251) — 部分覆盖 → `LlamaModel.newContext`；Python 上下文结构为 160 字节；Java b11669 结构为 168 字节，参数范围不同。
- [ ] [`llama_cpp._internals.LlamaContext.__init__.free_ctx`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L272) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._internals.LlamaContext.close`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L282) — 部分覆盖 → `LlamaContext.close`；Java 有状态及线程保护，错误契约未对齐。
- [ ] [`llama_cpp._internals.LlamaContext.__del__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L285) — 未迁移。
- [x] [`llama_cpp._internals.LlamaContext.n_ctx`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L288) — 能力已覆盖 → `LlamaContext.contextLength`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [ ] [`llama_cpp._internals.LlamaContext.pooling_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L291) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.kv_cache_clear`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L294) — 部分覆盖 → `LlamaContext.clear`；Python memory=None 时直接返回；Java 创建时要求 memory 可用。
- [ ] [`llama_cpp._internals.LlamaContext.kv_cache_seq_rm`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L300) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.kv_cache_seq_cp`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L305) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.kv_cache_seq_keep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L309) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.kv_cache_seq_shift`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L313) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.get_state_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L317) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.decode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L328) — 部分覆盖 → `LlamaContext.decode`；Python 传 LlamaBatch 并用 llama_decode；Java 传 token 数组并用 llama_process，错误映射不同。
- [ ] [`llama_cpp._internals.LlamaContext.encode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L336) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.set_n_threads`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L344) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.get_logits`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L347) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.get_logits_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L350) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.get_embeddings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L353) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.get_embeddings_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L356) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.get_embeddings_seq`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L359) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.set_rng_seed`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L364) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_repetition_penalties`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L369) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_softmax`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L382) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_top_k`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L387) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_top_p`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L392) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_min_p`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L397) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_typical`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L402) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_temp`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L409) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_grammar`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L412) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_token_mirostat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L417) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_token_mirostat_v2`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L429) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_token_greedy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L440) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.sample_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L445) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.grammar_accept_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L451) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.reset_timings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L456) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.print_timings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L459) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaContext.default_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L464) — 部分覆盖 → `ContextParams.defaults`；默认值与可配置字段不同；底层结构 ABI 也有差异。
- [ ] [`llama_cpp._internals.LlamaBatch.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L470) — 部分覆盖 → `LlamaContext 创建的 batch_ext`；Java 内部拥有文本单序列批次，缺少 Python 通用 LlamaBatch 对象。
- [ ] [`llama_cpp._internals.LlamaBatch.__init__.free_batch`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L487) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._internals.LlamaBatch.close`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L495) — 部分覆盖 → `LlamaContext.close`；批次随 Java context 释放，缺少独立批次所有权与 close API。
- [ ] [`llama_cpp._internals.LlamaBatch.__del__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L498) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaBatch.n_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L501) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaBatch.reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L504) — 部分覆盖 → `LlamaContext.decode 内部 batch_ext_clear`；缺少 Python 批次对象上的独立 reset API。
- [ ] [`llama_cpp._internals.LlamaBatch.set_batch`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L507) — 部分覆盖 → `LlamaContext.decode`；仅支持单序列文本 token，缺少 logits_all 和通用 batch 契约。
- [ ] [`llama_cpp._internals.LlamaBatch.add_sequence`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L518) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaTokenDataArray.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L533) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaTokenDataArray.copy_logits`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L550) — 未迁移。
- [ ] [`llama_cpp._internals.normalize_embedding`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L561) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSamplingContext.reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L607) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSamplingContext.cp`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L613) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSamplingContext.last`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L622) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSamplingContext.prev_str`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L628) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSamplingContext.sample`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L631) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSamplingContext.accept`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L642) — 未迁移。
- [ ] [`llama_cpp._internals.CustomSampler.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L647) — 未迁移。
- [ ] [`llama_cpp._internals.CustomSampler.__init__.apply_wrapper`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L650) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._internals.CustomSampler.__init__.free_wrapper`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L656) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._internals.CustomSampler.get_sampler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L673) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L678) — 部分覆盖 → `Sampler.Builder.build`；Java 一次建完整链；Python 先建空链再逐项添加。
- [ ] [`llama_cpp._internals.LlamaSampler.__init__.free_sampler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L684) — 未迁移；嵌套函数。
- [ ] [`llama_cpp._internals.LlamaSampler.close`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L694) — 部分覆盖 → `Sampler.close`；自定义采样器移除与线程状态契约未覆盖。
- [ ] [`llama_cpp._internals.LlamaSampler.__del__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L697) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_greedy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L700) — 部分覆盖 → `Sampler.Builder.greedy`；采样能力存在，Python 建链后添加的顺序、时机与参数契约尚未完整迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_dist`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L704) — 部分覆盖 → `Sampler.Builder.seed`；采样能力存在，Python 建链后添加的顺序、时机与参数契约尚未完整迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_softmax`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L708) — 部分覆盖 → `compat.python.PythonWarnings.deprecated`；原版只发出弃用警告；桥接可生成相同类别和文本，缺少原方法入口及完整警告环境。
- [ ] [`llama_cpp._internals.LlamaSampler.add_top_k`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L715) — 部分覆盖 → `Sampler.Builder.topK`；采样能力存在，Python 建链后添加的顺序、时机与参数契约尚未完整迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_top_p`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L719) — 部分覆盖 → `Sampler.Builder.topP`；采样能力存在，Python 建链后添加的顺序、时机与参数契约尚未完整迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_min_p`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L723) — 部分覆盖 → `Sampler.Builder.minP`；采样能力存在，Python 建链后添加的顺序、时机与参数契约尚未完整迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_typical`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L727) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_temp`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L731) — 部分覆盖 → `Sampler.Builder.temperature`；采样能力存在，Python 建链后添加的顺序、时机与参数契约尚未完整迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_temp_ext`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L735) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_xtc`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L739) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_top_n_sigma`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L743) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_mirostat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L747) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_mirostat_v2`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L751) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_grammar`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L755) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_grammar_lazy_patterns`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L761) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_penalties`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L787) — 部分覆盖 → `Sampler.Builder.repeatPenalty / frequencyPenalty / presencePenalty / penaltyLastN`；采样能力存在，Python 建链后添加的顺序、时机与参数契约尚未完整迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_dry`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L804) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_logit_bias`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L829) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_infill`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L841) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.add_custom`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L845) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.get_seed`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L856) — 部分覆盖 → `Sampler.seed`；Python 查询原生实际种子；Java 返回创建时配置值，随机种子场景不同。
- [ ] [`llama_cpp._internals.LlamaSampler.sample`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L859) — 部分覆盖 → `LlamaContext.sample`；Python 可指定 logits idx；Java 固定使用 -1，并有额外状态校验。
- [ ] [`llama_cpp._internals.LlamaSampler.accept`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L862) — 未迁移。
- [x] [`llama_cpp._internals.LlamaSampler.reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L865) — 能力已覆盖 → `Sampler.reset`；对应查询或采样器重置能力已覆盖；资源状态检查与 Python 完全一致性尚待对照验收。
- [ ] [`llama_cpp._internals.LlamaSampler.clone`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L868) — 未迁移。
- [ ] [`llama_cpp._internals.LlamaSampler.clone.free_sampler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py#L882) — 未迁移；嵌套函数。

#### `llama_cpp/_logger.py` — 2 项，勾选 0，部分 2

- [ ] [`llama_cpp._logger.llama_log_callback`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_logger.py#L31) — 部分覆盖 → `internal.NativeLogBridge.invoke`；级别映射、逐行拼接、stderr/监听器、回调异常处理与输出前缀不同。
- [ ] [`llama_cpp._logger.set_verbose`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_logger.py#L49) — 部分覆盖 → `NativeConfig.Builder.logLevel / logListener`；Python 可动态切换全局 logger；Java 在初始化时配置。

#### `llama_cpp/_utils.py` — 5 项，勾选 0，部分 0

- [ ] [`llama_cpp._utils.suppress_stdout_stderr.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_utils.py#L20) — 未迁移。
- [ ] [`llama_cpp._utils.suppress_stdout_stderr.__enter__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_utils.py#L24) — 未迁移。
- [ ] [`llama_cpp._utils.suppress_stdout_stderr.__exit__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_utils.py#L60) — 未迁移。
- [ ] [`llama_cpp._utils.MetaSingleton.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_utils.py#L87) — 未迁移。
- [ ] [`llama_cpp._utils.Singleton.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_utils.py#L98) — 未迁移。

#### `llama_cpp/llama.py` — 49 项，勾选 22，部分 11

- [ ] [`llama_cpp.llama.Llama.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L60) — 部分覆盖 → `LlamaRuntime / LlamaModel / LlamaContext / Sampler`；构造参数、默认值、可选能力、所有权和错误格式均有差异。
- [ ] [`llama_cpp.llama.Llama.__init__.free_lora_adapter`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L446) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama.Llama.ctx`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L576) — 部分覆盖 → `LlamaContext`；Java 隐藏原生指针，Python 公开底层句柄属性。
- [ ] [`llama_cpp.llama.Llama.model`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L580) — 部分覆盖 → `LlamaModel`；Java 隐藏原生指针，Python 公开底层句柄属性。
- [x] [`llama_cpp.llama.Llama._input_ids`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L584) — 能力已覆盖 → `compat.python.PythonLlama.inputIds`；intc token 前缀以 IntBuffer 提供可写共享视图，保留负切片及截断规则；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [x] [`llama_cpp.llama.Llama._scores`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L588) — 能力已覆盖 → `compat.python.PythonLlama.scores`；single scores 前缀以 FloatBuffer 行视图提供可写共享数据；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [x] [`llama_cpp.llama.Llama.eval_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L592) — 能力已覆盖 → `compat.python.PythonLlama.evalTokens`；按原版 deque 的 maxlen 保留末尾 token，Java 返回独立 List 快照；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [x] [`llama_cpp.llama.Llama.eval_logits`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L596) — 能力已覆盖 → `compat.python.PythonLlama.evalLogits`；按 logits_all 选择行数，Java 返回独立二维 List 快照；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [x] [`llama_cpp.llama.Llama.tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L602) — 能力已覆盖 → `compat.python.PythonLlama.tokenize`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [x] [`llama_cpp.llama.Llama.detokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L620) — 能力已覆盖 → `compat.python.PythonLlama.detokenize`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [x] [`llama_cpp.llama.Llama.set_cache`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L640) — 能力已覆盖 → `compat.python.PythonLlama.setCache`；附加或清除 PythonLlamaRamCache 引用，保留当前游标和待求值标记；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [x] [`llama_cpp.llama.Llama.set_seed`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L648) — 能力已覆盖 → `compat.python.PythonLlama.setSeed`；BigInteger 保留原版整数赋值语义，采样器由调用方管理；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [x] [`llama_cpp.llama.Llama.reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L656) — 能力已覆盖 → `compat.python.PythonLlama.reset`；先更新游标及待求值标记，再通过调用方内存适配回调处理 recurrent/hybrid 分支；已测回调顺序及异常后状态；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [ ] [`llama_cpp.llama.Llama.eval`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L666) — 部分覆盖 → `LlamaContext.decode`；缺少 Python input_ids、scores、logits_all 和增量状态契约。
- [ ] [`llama_cpp.llama.Llama._init_sampler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L704) — 未迁移。
- [ ] [`llama_cpp.llama.Llama._init_sampler.apply_func`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L726) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama.Llama.sample`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L792) — 部分覆盖 → `LlamaContext.sample / Sampler.Builder`；缺少 typical、mirostat、grammar、logits_processor 等参数。
- [ ] [`llama_cpp.llama.Llama.generate`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L854) — 部分覆盖 → `LlamaContext.generate`；Python 为可 send 注入 token 的生成器；Java 为回调和结果对象，停止及重置契约不同。
- [ ] [`llama_cpp.llama.Llama.create_embedding`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1027) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.embed`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1067) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.embed.decode_batch`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1115) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama.Llama._create_completion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1195) — 未迁移。
- [ ] [`llama_cpp.llama.Llama._create_completion.logit_bias_processor`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1324) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama.Llama.create_completion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1815) — 部分覆盖 → `LlamaContext.generate`；仅覆盖基础文本生成；缺少 OpenAI 字典、stop、echo、suffix、logprobs 等。
- [ ] [`llama_cpp.llama.Llama.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1912) — 部分覆盖 → `LlamaContext.generate`；Python 委托完整 completion API；Java 无同等入口及返回结构。
- [ ] [`llama_cpp.llama.Llama.create_chat_completion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2004) — 部分覆盖 → `ChatSession.send`；缺少完整 messages 参数、tools、function_call、response_format、流式响应结构。
- [ ] [`llama_cpp.llama.Llama.create_chat_completion_openai_v1`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2107) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.__getstate__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2143) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.__setstate__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2211) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.save_state`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2214) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.load_state`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2244) — 未迁移。
- [x] [`llama_cpp.llama.Llama.n_ctx`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2263) — 能力已覆盖 → `LlamaContext.contextLength`；查询能力已覆盖；仅对有效且打开的资源确认对应，关闭后行为与跨版本结果未完成 Python 对照验收。
- [x] [`llama_cpp.llama.Llama.n_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2267) — 能力已覆盖 → `LlamaModel.info().embedding`；查询能力已覆盖；仅对有效且打开的资源确认对应，关闭后行为与跨版本结果未完成 Python 对照验收。
- [x] [`llama_cpp.llama.Llama.n_vocab`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2271) — 能力已覆盖 → `Vocab.size`；查询能力已覆盖；仅对有效且打开的资源确认对应，关闭后行为与跨版本结果未完成 Python 对照验收。
- [x] [`llama_cpp.llama.Llama.tokenizer`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2275) — 能力已覆盖 → `compat.python.PythonLlama.tokenizer`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [x] [`llama_cpp.llama.Llama.token_eos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2279) — 能力已覆盖 → `Vocab.eos`；查询能力已覆盖；仅对有效且打开的资源确认对应，关闭后行为与跨版本结果未完成 Python 对照验收。
- [x] [`llama_cpp.llama.Llama.token_bos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2283) — 能力已覆盖 → `Vocab.bos`；查询能力已覆盖；仅对有效且打开的资源确认对应，关闭后行为与跨版本结果未完成 Python 对照验收。
- [ ] [`llama_cpp.llama.Llama.token_nl`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2287) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.pooling_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2291) — 未迁移。
- [ ] [`llama_cpp.llama.Llama.close`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2295) — 部分覆盖 → `LlamaModel.close / LlamaContext.close / Sampler.close`；Java 资源分离且有并发/重入检查；生命周期错误文本有差异。
- [ ] [`llama_cpp.llama.Llama.__del__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2299) — 未迁移。
- [x] [`llama_cpp.llama.Llama.logits_to_logprobs`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2303) — 能力已覆盖 → `compat.python.PythonLlama.logitsToLogprobs`；single 向量及二维矩阵 axis 0/1/-1/-2 已对照，数值采用绝对及相对 4e-6 容差；NumPy 警告和更高维数组待迁移；固定原版完整函数体已有场景对照，全输入一致性继续验收。
- [x] [`llama_cpp.llama.Llama.longest_token_prefix`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2321) — 能力已覆盖 → `compat.python.PythonTokens.longestTokenPrefix`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [ ] [`llama_cpp.llama.Llama.from_pretrained`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2331) — 部分覆盖 → `demo.download.ModelDownloader.ensure`；仅有 demo 固定模型下载，通用 Hugging Face 仓库、匹配和参数契约缺失。
- [x] [`llama_cpp.llama.LlamaState.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2466) — 能力已覆盖 → `compat.python.PythonLlamaState`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama.LogitsProcessorList.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2489) — 能力已覆盖 → `compat.python.PythonLogitsProcessorList.apply`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama.StoppingCriteriaList.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2501) — 能力已覆盖 → `compat.python.PythonStoppingCriteriaList.test`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama.MinTokensLogitsProcessor.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2508) — 能力已覆盖 → `compat.python.PythonMinTokensLogitsProcessor`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama.MinTokensLogitsProcessor.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2513) — 能力已覆盖 → `compat.python.PythonMinTokensLogitsProcessor.apply`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。

#### `llama_cpp/llama_cache.py` — 18 项，勾选 6，部分 0

- [ ] [`llama_cpp.llama_cache.BaseLlamaCache.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L20) — 未迁移。
- [ ] [`llama_cpp.llama_cache.BaseLlamaCache.cache_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L25) — 未迁移。
- [ ] [`llama_cpp.llama_cache.BaseLlamaCache._find_longest_prefix_key`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L28) — 未迁移。
- [ ] [`llama_cpp.llama_cache.BaseLlamaCache.__getitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L35) — 未迁移。
- [ ] [`llama_cpp.llama_cache.BaseLlamaCache.__contains__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L39) — 未迁移。
- [ ] [`llama_cpp.llama_cache.BaseLlamaCache.__setitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L43) — 未迁移。
- [x] [`llama_cpp.llama_cache.LlamaRAMCache.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L52) — 能力已覆盖 → `compat.python.PythonLlamaRamCache`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama_cache.LlamaRAMCache.cache_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L60) — 能力已覆盖 → `compat.python.PythonLlamaRamCache.cacheSize`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama_cache.LlamaRAMCache._find_longest_prefix_key`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L63) — 能力已覆盖 → `compat.python.PythonLlamaRamCache.findLongestPrefixKey`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama_cache.LlamaRAMCache.__getitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L79) — 能力已覆盖 → `compat.python.PythonLlamaRamCache.get`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama_cache.LlamaRAMCache.__contains__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L88) — 能力已覆盖 → `compat.python.PythonLlamaRamCache.contains`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [x] [`llama_cpp.llama_cache.LlamaRAMCache.__setitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L91) — 能力已覆盖 → `compat.python.PythonLlamaRamCache.put`；固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收。
- [ ] [`llama_cpp.llama_cache.LlamaDiskCache.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L107) — 未迁移。
- [ ] [`llama_cpp.llama_cache.LlamaDiskCache.cache_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L114) — 未迁移。
- [ ] [`llama_cpp.llama_cache.LlamaDiskCache._find_longest_prefix_key`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L117) — 未迁移。
- [ ] [`llama_cpp.llama_cache.LlamaDiskCache.__getitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L130) — 未迁移。
- [ ] [`llama_cpp.llama_cache.LlamaDiskCache.__contains__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L141) — 未迁移。
- [ ] [`llama_cpp.llama_cache.LlamaDiskCache.__setitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cache.py#L144) — 未迁移。

#### `llama_cpp/llama_chat_format.py` — 111 项，勾选 37，部分 0

- [ ] [`llama_cpp.llama_chat_format.LlamaChatCompletionHandler.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L68) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.LlamaChatCompletionHandlerRegistry.register_chat_completion_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L119) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.LlamaChatCompletionHandlerRegistry.unregister_chat_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L131) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.LlamaChatCompletionHandlerRegistry.get_chat_completion_handler_by_name`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L137) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.get_chat_completion_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L149) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.register_chat_completion_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L155) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.register_chat_completion_handler.decorator`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L156) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.ChatFormatter.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L186) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.IgnoreGenerationTags.parse`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L200) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L204) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.strftime_now`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L238) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.tojson`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L242) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L257) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.__call__.raise_exception`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L267) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.__call__.stop_on_last_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L287) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.Jinja2ChatFormatter.to_chat_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L301) — 未迁移。
- [x] [`llama_cpp.llama_chat_format._convert_text_completion_logprobs_to_chat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L305) — 能力已覆盖 → `compat.python.PythonChatCompletions.convertTextCompletionLogprobsToChat`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._convert_text_completion_to_chat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L334) — 能力已覆盖 → `compat.python.PythonChatCompletions.convertTextCompletionToChat`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._convert_text_completion_chunks_to_chat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L360) — 能力已覆盖 → `compat.python.PythonChatCompletions.convertTextCompletionChunksToChat`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._convert_completion_to_chat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L405) — 能力已覆盖 → `compat.python.PythonChatCompletions.convertCompletionToChat`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [ ] [`llama_cpp.llama_chat_format._convert_completion_to_chat_function`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L424) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format._convert_completion_to_chat_function._stream_response_to_function_stream`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L477) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.chat_formatter_to_chat_completion_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L609) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.chat_formatter_to_chat_completion_handler.chat_completion_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L612) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.hf_autotokenizer_to_chat_formatter`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L759) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.hf_autotokenizer_to_chat_formatter.format_autotokenizer`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L769) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.hf_autotokenizer_to_chat_completion_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L786) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.hf_tokenizer_config_to_chat_formatter`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L793) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.hf_tokenizer_config_to_chat_formatter.format_tokenizer_config`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L816) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.hf_tokenizer_config_to_chat_completion_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L842) — 未迁移。
- [x] [`llama_cpp.llama_chat_format.guess_chat_format_from_gguf_metadata`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L852) — 能力已覆盖 → `compat.python.PythonChatFormats.guessChatFormatFromGgufMetadata`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._get_system_message`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L875) — 能力已覆盖 → `compat.python.PythonChatFormats.getSystemMessage`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._map_roles`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L885) — 能力已覆盖 → `compat.python.PythonChatFormats.mapRoles`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._format_llama2`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L901) — 能力已覆盖 → `compat.python.PythonChatFormats.formatLlama2Prompt`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._format_add_colon_single`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L918) — 能力已覆盖 → `compat.python.PythonChatFormats.formatAddColonSingle`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._format_add_colon_two`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L931) — 能力已覆盖 → `compat.python.PythonChatFormats.formatAddColonTwo`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._format_no_colon_single`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L945) — 能力已覆盖 → `compat.python.PythonChatFormats.formatNoColonSingle`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._format_add_colon_space_single`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L958) — 能力已覆盖 → `compat.python.PythonChatFormats.formatAddColonSpaceSingle`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._format_chatml`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L971) — 能力已覆盖 → `compat.python.PythonChatFormats.formatChatmlPrompt`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format._format_chatglm3`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L984) — 能力已覆盖 → `compat.python.PythonChatFormats.formatChatglm3Prompt`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [ ] [`llama_cpp.llama_chat_format._grammar_for_json`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L999) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format._grammar_for_json_schema`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1005) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format._grammar_for_response_format`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1017) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.register_chat_format`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1035) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.register_chat_format.decorator`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1036) — 未迁移；嵌套函数。
- [x] [`llama_cpp.llama_chat_format.format_llama2`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1049) — 能力已覆盖 → `compat.python.PythonChatFormats.formatLlama2`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_llama3`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1066) — 能力已覆盖 → `compat.python.PythonChatFormats.formatLlama3`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_alpaca`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1083) — 能力已覆盖 → `compat.python.PythonChatFormats.formatAlpaca`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_qwen`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1097) — 能力已覆盖 → `compat.python.PythonChatFormats.formatQwen`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1114) — 能力已覆盖 → `compat.python.PythonChatFormats.formatVicuna`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_oasst_llama`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1130) — 能力已覆盖 → `compat.python.PythonChatFormats.formatOasstLlama`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_baichuan2`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1146) — 能力已覆盖 → `compat.python.PythonChatFormats.formatBaichuan2`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_baichuan`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1162) — 能力已覆盖 → `compat.python.PythonChatFormats.formatBaichuan`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_openbuddy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1178) — 能力已覆盖 → `compat.python.PythonChatFormats.formatOpenbuddy`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_redpajama_incite`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1200) — 能力已覆盖 → `compat.python.PythonChatFormats.formatRedpajamaIncite`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_snoozy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1216) — 能力已覆盖 → `compat.python.PythonChatFormats.formatSnoozy`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_phind`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1238) — 能力已覆盖 → `compat.python.PythonChatFormats.formatPhind`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_intel`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1252) — 能力已覆盖 → `compat.python.PythonChatFormats.formatIntel`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_open_orca`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1266) — 能力已覆盖 → `compat.python.PythonChatFormats.formatOpenOrca`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_mistrallite`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1293) — 能力已覆盖 → `compat.python.PythonChatFormats.formatMistrallite`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_zephyr`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1309) — 能力已覆盖 → `compat.python.PythonChatFormats.formatZephyr`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_pygmalion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1326) — 能力已覆盖 → `compat.python.PythonChatFormats.formatPygmalion`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_chatml`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1342) — 能力已覆盖 → `compat.python.PythonChatFormats.formatChatml`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_mistral_instruct`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1359) — 能力已覆盖 → `compat.python.PythonChatFormats.formatMistralInstruct`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_chatglm3`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1380) — 能力已覆盖 → `compat.python.PythonChatFormats.formatChatglm3`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_openchat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1397) — 能力已覆盖 → `compat.python.PythonChatFormats.formatOpenchat`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_saiga`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1417) — 能力已覆盖 → `compat.python.PythonChatFormats.formatSaiga`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [x] [`llama_cpp.llama_chat_format.format_gemma`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1439) — 能力已覆盖 → `compat.python.PythonChatFormats.formatGemma`；逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收。
- [ ] [`llama_cpp.llama_chat_format.functionary_chat_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1460) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.functionary_chat_handler.generate_type_definition`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1490) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_chat_handler.generate_shared_definitions`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1523) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_chat_handler.generate_schema_from_functions`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1540) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_chat_handler.prepare_messages_for_inference`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1574) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_chat_handler.prepare_messages_for_inference.message_to_str`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1624) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1823) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler.generate_type_definition`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1874) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler.generate_shared_definitions`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1907) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler.generate_schema_from_functions`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1924) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler.prepare_messages_for_inference`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L1958) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler.get_grammar`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2074) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler.create_completion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2107) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.functionary_v1_v2_chat_handler.generate_streaming`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2139) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2775) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler._init_mtmd_context`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2786) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler._init_mtmd_context.mtmd_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2820) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler.load_image`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2828) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler._create_bitmap_from_bytes`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2831) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L2850) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler._load_image`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3125) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler.get_image_urls`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3140) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler.split_text_on_image_urls`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3159) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler.split_text_on_image_urls.find_first`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3162) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.Llava15ChatHandler.from_pretrained`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3185) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3270) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._init_mtmd_context`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3284) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._init_mtmd_context.mtmd_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3315) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler.load_image`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3323) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._create_bitmap_from_bytes`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3326) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._get_chat_template`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3343) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._get_template_messages`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3351) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._convert_message_for_template`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3362) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._convert_content_part_for_template`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3377) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._decode_token_piece`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3386) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._postprocess_template_text`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3391) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3401) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler.__call__.raise_exception`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3456) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler._load_image`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3660) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler.get_image_urls`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3674) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.MTMDChatHandler.from_pretrained`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L3693) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.Qwen25VLChatHandler.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L4103) — 未迁移。
- [ ] [`llama_cpp.llama_chat_format.chatml_function_calling`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_chat_format.py#L4132) — 未迁移。

#### `llama_cpp/llama_cpp.py` — 256 项，勾选 55，部分 6

- [ ] [`llama_cpp.llama_cpp._warn_deprecated`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L48) — 部分覆盖 → `compat.python.PythonWarnings.deprecated`；类别与消息有原版契约对照；过滤器和 Python 调用栈/来源仍需限定配置。
- [x] [`llama_cpp.llama_cpp.llama_version`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1275) — 绑定完成 → `internal.LlamaLibrary.llama_version`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_default_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1287) — 绑定完成 → `internal.LlamaLibrary.llama_model_default_params`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_context_default_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1298) — 部分覆盖 → `internal.LlamaLibrary.llama_context_default_params`；上下文结构 160/168 字节，含 moe_cache_size 的字段布局有差异。
- [x] [`llama_cpp.llama_cpp.llama_sampler_chain_default_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1309) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_chain_default_params`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_quantize_default_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1320) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_flash_attn_type_name`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1327) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_load_mode_name`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1334) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_load_mode_from_str`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1341) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_ftype_name`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1349) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_backend_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1362) — 绑定完成 → `internal.LlamaLibrary.llama_backend_init`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_backend_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1392) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_numa_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1405) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_load_model_from_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1429) — 未迁移；同名定义行 1429/1437。
- [x] [`llama_cpp.llama_cpp.llama_model_load_from_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1458) — 绑定完成 → `internal.LlamaLibrary.llama_model_load_from_file`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_load_from_splits`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1480) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_load_from_file_ptr`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1500) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_save_to_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1519) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_free_model`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1531) — 未迁移；同名定义行 1531/1537。
- [x] [`llama_cpp.llama_cpp.llama_model_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1548) — 绑定完成 → `internal.LlamaLibrary.llama_model_free`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_init_from_user`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1570) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_init_from_model`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1589) — 部分覆盖 → `internal.LlamaLibrary.llama_init_from_model`；传入的上下文参数结构 ABI 不同。
- [ ] [`llama_cpp.llama_cpp.llama_new_context_with_model`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1603) — 未迁移；同名定义行 1603/1611。
- [x] [`llama_cpp.llama_cpp.llama_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1628) — 绑定完成 → `internal.LlamaLibrary.llama_free`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_time_us`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1639) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_max_devices`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1644) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_max_parallel_sequences`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1649) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_max_tensor_buft_overrides`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1654) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_supports_mmap`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1661) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_supports_mlock`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1666) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_supports_gpu_offload`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1671) — 绑定完成 → `internal.LlamaLibrary.llama_supports_gpu_offload`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_supports_rpc`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1676) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_n_ctx`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1681) — 绑定完成 → `internal.LlamaLibrary.llama_n_ctx`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_n_ctx_seq`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1686) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_n_batch`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1693) — 绑定完成 → `internal.LlamaLibrary.llama_n_batch`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_n_ubatch`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1698) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_n_seq_max`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1703) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_n_rs_seq`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1708) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_n_ctx_train`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1713) — 未迁移；同名定义行 1713/1719。
- [ ] [`llama_cpp.llama_cpp.llama_n_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1726) — 未迁移；同名定义行 1726/1732。
- [ ] [`llama_cpp.llama_cpp.llama_n_layer`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1739) — 未迁移；同名定义行 1739/1745。
- [ ] [`llama_cpp.llama_cpp.llama_n_head`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1752) — 未迁移；同名定义行 1752/1758。
- [ ] [`llama_cpp.llama_cpp.llama_n_vocab`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1765) — 未迁移；同名定义行 1765/1771。
- [ ] [`llama_cpp.llama_cpp.llama_get_model`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1778) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_get_memory`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1783) — 绑定完成 → `internal.LlamaLibrary.llama_get_memory`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_pooling_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1790) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_model_get_vocab`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1795) — 绑定完成 → `internal.LlamaLibrary.llama_model_get_vocab`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_rope_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1800) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_model_n_ctx_train`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1805) — 绑定完成 → `internal.LlamaLibrary.llama_model_n_ctx_train`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_n_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1810) — 绑定完成 → `internal.LlamaLibrary.llama_model_n_embd`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_n_embd_inp`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1815) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_n_embd_out`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1822) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_model_n_layer`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1829) — 绑定完成 → `internal.LlamaLibrary.llama_model_n_layer`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_n_layer_nextn`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1834) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_n_head`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1839) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_n_head_kv`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1844) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_n_swa`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1849) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_rope_freq_scale_train`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1857) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_n_cls_out`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1864) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_cls_label`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1875) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1882) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_vocab_n_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1887) — 绑定完成 → `internal.LlamaLibrary.llama_vocab_n_tokens`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_meta_val_str`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1909) — 绑定完成 → `internal.LlamaLibrary.llama_model_meta_val_str`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_meta_count`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1923) — 绑定完成 → `internal.LlamaLibrary.llama_model_meta_count`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_meta_key_str`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1931) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_model_meta_key_by_index`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1948) — 绑定完成 → `internal.LlamaLibrary.llama_model_meta_key_by_index`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_meta_val_str_by_index`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1971) — 绑定完成 → `internal.LlamaLibrary.llama_model_meta_val_str_by_index`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_desc`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L1989) — 绑定完成 → `internal.LlamaLibrary.llama_model_desc`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_ftype`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2002) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_model_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2010) — 绑定完成 → `internal.LlamaLibrary.llama_model_size`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_chat_template`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2023) — 绑定完成 → `internal.LlamaLibrary.llama_model_chat_template`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_model_n_params`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2034) — 绑定完成 → `internal.LlamaLibrary.llama_model_n_params`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_has_encoder`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2042) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_has_decoder`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2050) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_decoder_start_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2061) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_is_recurrent`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2071) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_model_is_hybrid`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2079) — 绑定完成 → `internal.LlamaLibrary.llama_model_is_hybrid`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_model_is_diffusion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2087) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_model_quantize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2106) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_lora_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2130) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_lora_init_from_file_ptr`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2144) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_meta_val_str`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2163) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_meta_count`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2179) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_meta_key_by_index`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2196) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_meta_val_str_by_index`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2219) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_lora_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2238) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_get_alora_n_invocation_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2248) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_adapter_get_alora_invocation_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2261) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_adapters_lora`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2287) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_adapter_lora`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2299) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_adapter_cvec`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2337) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_apply_adapter_cvec`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2356) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_memory_clear`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2388) — 绑定完成 → `internal.LlamaLibrary.llama_memory_clear`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_memory_seq_rm`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2414) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_memory_seq_cp`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2451) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_memory_seq_keep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2470) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_memory_seq_add`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2495) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_memory_seq_div`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2529) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_memory_seq_pos_min`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2553) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_memory_seq_pos_max`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2571) — 绑定完成 → `internal.LlamaLibrary.llama_memory_seq_pos_max`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_memory_can_shift`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2582) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_get_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2597) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_state_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2607) — 未迁移；同名定义行 2607/2615。
- [ ] [`llama_cpp.llama_cpp.llama_state_get_data`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2636) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_copy_state_data`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2660) — 未迁移；同名定义行 2660/2670。
- [ ] [`llama_cpp.llama_cpp.llama_state_set_data`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2688) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_state_data`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2708) — 未迁移；同名定义行 2708/2718。
- [ ] [`llama_cpp.llama_cpp.llama_state_load_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2743) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_load_session_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2771) — 未迁移；同名定义行 2771/2784。
- [ ] [`llama_cpp.llama_cpp.llama_state_save_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2813) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_save_session_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2838) — 未迁移；同名定义行 2838/2850。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_get_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2870) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_get_data`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2891) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_set_data`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2921) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_save_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2949) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_load_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L2979) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_get_size_ext`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3017) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_get_data_ext`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3042) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_state_seq_set_data_ext`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3069) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_batch_get_one`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3101) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_batch_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3127) — 部分覆盖 → `internal.LlamaLibrary.llama_batch_ext_init`；新版批次由 context 创建，参数和句柄类型不同。
- [ ] [`llama_cpp.llama_cpp.llama_batch_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3146) — 部分覆盖 → `internal.LlamaLibrary.llama_batch_ext_free`；释放新版 batch_ext，缺少旧 llama_batch 接口。
- [ ] [`llama_cpp.llama_cpp.llama_encode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3161) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_decode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3187) — 部分覆盖 → `LlamaContext.decode / internal.LlamaLibrary.llama_process`；能力通过新 process 接口实现，缺少 Python llama_batch 接口及同名绑定。
- [x] [`llama_cpp.llama_cpp.llama_batch_ext_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3234) — 绑定完成 → `internal.LlamaLibrary.llama_batch_ext_init`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_batch_ext_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3239) — 绑定完成 → `internal.LlamaLibrary.llama_batch_ext_free`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_batch_ext_clear`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3244) — 绑定完成 → `internal.LlamaLibrary.llama_batch_ext_clear`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_batch_ext_add`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3260) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_batch_ext_add_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3270) — 绑定完成 → `internal.LlamaLibrary.llama_batch_ext_add_token`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_batch_ext_add_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3281) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_batch_ext_add_seq`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3297) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_batch_ext_set_embd_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3313) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_batch_ext_set_embd_state`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3331) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_batch_ext_set_output_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3347) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_batch_ext_set_output_logits`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3363) — 绑定完成 → `internal.LlamaLibrary.llama_batch_ext_set_output_logits`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_batch_ext_set_pos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3381) — 绑定完成 → `internal.LlamaLibrary.llama_batch_ext_set_pos`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_process`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3399) — 绑定完成 → `internal.LlamaLibrary.llama_process`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_set_n_threads`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3417) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_n_threads`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3433) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_n_threads_batch`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3441) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_embeddings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3450) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_causal_attn`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3460) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_causal_attn`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3469) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_warmup`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3478) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_abort_callback`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3491) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_synchronize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3506) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_logits`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3523) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_logits_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3545) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_embeddings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3564) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_embeddings_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3581) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_embeddings_seq`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3599) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_sampled_token_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3617) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_sampled_probs_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3631) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_sampled_probs_count_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3644) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_sampled_logits_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3658) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_sampled_logits_count_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3671) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_sampled_candidates_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3685) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_get_sampled_candidates_count_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3698) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_get_text`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3714) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_get_score`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3723) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_get_attr`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3732) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_vocab_is_eog`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3742) — 绑定完成 → `internal.LlamaLibrary.llama_vocab_is_eog`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_is_control`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3752) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_vocab_bos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3762) — 绑定完成 → `internal.LlamaLibrary.llama_vocab_bos`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_vocab_eos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3769) — 绑定完成 → `internal.LlamaLibrary.llama_vocab_eos`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_eot`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3776) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_sep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3783) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_nl`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3790) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_pad`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3797) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_mask`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3804) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_get_add_bos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3815) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_get_add_eos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3824) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_get_add_sep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3833) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_get_suppress_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3843) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_fim_pre`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3856) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_fim_suf`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3865) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_fim_mid`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3874) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_fim_pad`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3883) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_fim_rep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3892) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_fim_sep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3901) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_token_get_text`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3911) — 未迁移；同名定义行 3911/3919。
- [ ] [`llama_cpp.llama_cpp.llama_token_get_score`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3932) — 未迁移；同名定义行 3932/3940。
- [ ] [`llama_cpp.llama_cpp.llama_token_get_attr`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3953) — 未迁移；同名定义行 3953/3961。
- [ ] [`llama_cpp.llama_cpp.llama_token_is_eog`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3974) — 未迁移；同名定义行 3974/3982。
- [ ] [`llama_cpp.llama_cpp.llama_token_is_control`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L3993) — 未迁移；同名定义行 3993/4001。
- [ ] [`llama_cpp.llama_cpp.llama_token_bos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4017) — 未迁移；同名定义行 4017/4023。
- [ ] [`llama_cpp.llama_cpp.llama_token_eos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4034) — 未迁移；同名定义行 4034/4040。
- [ ] [`llama_cpp.llama_cpp.llama_token_eot`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4051) — 未迁移；同名定义行 4051/4057。
- [ ] [`llama_cpp.llama_cpp.llama_token_cls`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4068) — 未迁移；同名定义行 4068/4074。
- [ ] [`llama_cpp.llama_cpp.llama_token_sep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4085) — 未迁移；同名定义行 4085/4091。
- [ ] [`llama_cpp.llama_cpp.llama_token_nl`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4102) — 未迁移；同名定义行 4102/4108。
- [ ] [`llama_cpp.llama_cpp.llama_token_pad`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4119) — 未迁移；同名定义行 4119/4125。
- [ ] [`llama_cpp.llama_cpp.llama_add_bos_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4136) — 未迁移；同名定义行 4136/4142。
- [ ] [`llama_cpp.llama_cpp.llama_add_eos_token`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4153) — 未迁移；同名定义行 4153/4159。
- [ ] [`llama_cpp.llama_cpp.llama_token_fim_pre`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4170) — 未迁移；同名定义行 4170/4176。
- [ ] [`llama_cpp.llama_cpp.llama_token_fim_suf`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4187) — 未迁移；同名定义行 4187/4193。
- [ ] [`llama_cpp.llama_cpp.llama_token_fim_mid`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4204) — 未迁移；同名定义行 4204/4210。
- [ ] [`llama_cpp.llama_cpp.llama_token_fim_pad`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4221) — 未迁移；同名定义行 4221/4227。
- [ ] [`llama_cpp.llama_cpp.llama_token_fim_rep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4238) — 未迁移；同名定义行 4238/4244。
- [ ] [`llama_cpp.llama_cpp.llama_token_fim_sep`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4255) — 未迁移；同名定义行 4255/4261。
- [ ] [`llama_cpp.llama_cpp.llama_vocab_cls`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4274) — 未迁移；同名定义行 4274/4280。
- [x] [`llama_cpp.llama_cpp.llama_tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4321) — 绑定完成 → `internal.LlamaLibrary.llama_tokenize`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_token_to_piece`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4373) — 绑定完成 → `internal.LlamaLibrary.llama_token_to_piece`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_detokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4424) — 绑定完成 → `internal.LlamaLibrary.llama_detokenize`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_chat_apply_template`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4481) — 绑定完成 → `internal.LlamaLibrary.llama_chat_apply_template`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_chat_builtin_templates`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4516) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_set_sampler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4643) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4657) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_name`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4668) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_accept`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4677) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_apply`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4686) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_sampler_reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4697) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_reset`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_clone`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4706) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_copy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4715) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_sampler_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4725) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_free`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_sampler_chain_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4738) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_chain_init`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_sampler_chain_add`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4750) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_chain_add`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_chain_get`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4759) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_chain_n`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4770) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_chain_remove`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4780) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_sampler_init_greedy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4790) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_init_greedy`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_sampler_init_dist`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4795) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_init_dist`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_sampler_init_top_k`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4802) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_init_top_k`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_sampler_init_top_p`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4812) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_init_top_p`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [x] [`llama_cpp.llama_cpp.llama_sampler_init_min_p`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4822) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_init_min_p`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_typical`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4832) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_sampler_init_temp`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4838) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_init_temp`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_temp_ext`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4848) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_xtc`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4860) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_top_n_sigma`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4872) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_mirostat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4887) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_mirostat_v2`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4902) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_grammar`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4917) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_grammar_lazy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L4944) — 未迁移；同名定义行 4944/4959。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_grammar_lazy_patterns`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5006) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_sampler_init_penalties`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5036) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_init_penalties`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_dry`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5068) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_adaptive_p`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5089) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_logit_bias`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5105) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_init_infill`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5117) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_sampler_get_seed`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5127) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_sampler_sample`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5138) — 绑定完成 → `internal.LlamaLibrary.llama_sampler_sample`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_split_path`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5155) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_split_prefix`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5174) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_print_system_info`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5189) — 绑定完成 → `internal.LlamaLibrary.llama_print_system_info`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_log_get`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5201) — 未迁移。
- [x] [`llama_cpp.llama_cpp.llama_log_set`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5216) — 绑定完成 → `internal.LlamaLibrary.llama_log_set`；同名 C 入口的 JNA 绑定已实现；Python 类型适配、原生版本和全输入行为尚未对照验收。
- [ ] [`llama_cpp.llama_cpp.llama_perf_context`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5273) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_perf_context_print`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5282) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_perf_context_reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5291) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_perf_sampler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5301) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_perf_sampler_print`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5310) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_perf_sampler_reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5319) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_opt_param_filter_all`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5340) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_opt_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5374) — 未迁移。
- [ ] [`llama_cpp.llama_cpp.llama_opt_epoch`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py#L5400) — 未迁移。

#### `llama_cpp/llama_cpp_ext.py` — 7 项，勾选 0，部分 0

- [ ] [`llama_cpp.llama_cpp_ext._ctypes_function_from_names`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp_ext.py#L19) — 未迁移。
- [ ] [`llama_cpp.llama_cpp_ext._ctypes_function_from_names.decorator`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp_ext.py#L26) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_cpp_ext.llama_set_embeddings_nextn`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp_ext.py#L55) — 未迁移。
- [ ] [`llama_cpp.llama_cpp_ext.llama_set_nextn_layer_offset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp_ext.py#L75) — 未迁移。
- [ ] [`llama_cpp.llama_cpp_ext.llama_get_embeddings_nextn`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp_ext.py#L94) — 未迁移。
- [ ] [`llama_cpp.llama_cpp_ext.llama_get_embeddings_nextn_ith`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp_ext.py#L112) — 未迁移。
- [ ] [`llama_cpp.llama_cpp_ext.llama_get_ctx_other`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp_ext.py#L131) — 未迁移。

#### `llama_cpp/llama_grammar.py` — 29 项，勾选 0，部分 0

- [ ] [`llama_cpp.llama_grammar.LlamaGrammar.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L20) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.LlamaGrammar.from_string`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L25) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.LlamaGrammar.from_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L29) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.LlamaGrammar.from_json_schema`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L46) — 未迁移。
- [ ] [`llama_cpp.llama_grammar._build_repetition`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L254) — 未迁移。
- [ ] [`llama_cpp.llama_grammar._build_repetition.opt_repetitions`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L273) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.BuiltinRule.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L311) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L381) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._format_literal`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L392) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.not_literal`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L398) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.not_literal.recurse`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L407) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._add_rule`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L424) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.resolve_refs`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L439) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.resolve_refs.visit`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L446) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._generate_union_rule`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L492) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._visit_pattern`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L500) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._visit_pattern.to_rule`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L522) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._visit_pattern.transform`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L526) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._visit_pattern.transform.get_dot`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L541) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._visit_pattern.transform.join_seq`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L549) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._resolve_ref`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L685) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._generate_constant_rule`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L694) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.visit`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L697) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.visit.add_component`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L747) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._add_primitive`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L846) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._build_object_rule`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L856) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter._build_object_rule.get_recursive_refs`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L906) — 未迁移；嵌套函数。
- [ ] [`llama_cpp.llama_grammar.SchemaConverter.format_grammar`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L937) — 未迁移。
- [ ] [`llama_cpp.llama_grammar.json_schema_to_gbnf`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_grammar.py#L944) — 未迁移。

#### `llama_cpp/llama_speculative.py` — 4 项，勾选 0，部分 0

- [ ] [`llama_cpp.llama_speculative.LlamaDraftModel.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_speculative.py#L11) — 未迁移。
- [ ] [`llama_cpp.llama_speculative.LlamaPromptLookupDecoding.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_speculative.py#L20) — 未迁移。
- [ ] [`llama_cpp.llama_speculative.LlamaPromptLookupDecoding.find_candidate_pred_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_speculative.py#L25) — 未迁移。
- [ ] [`llama_cpp.llama_speculative.LlamaPromptLookupDecoding.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_speculative.py#L57) — 未迁移。

#### `llama_cpp/llama_tokenizer.py` — 12 项，勾选 5，部分 1

- [ ] [`llama_cpp.llama_tokenizer.BaseLlamaTokenizer.tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L16) — 未迁移。
- [ ] [`llama_cpp.llama_tokenizer.BaseLlamaTokenizer.detokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L29) — 未迁移。
- [x] [`llama_cpp.llama_tokenizer.LlamaTokenizer.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L46) — 能力已覆盖 → `compat.python.PythonLlamaTokenizer`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [x] [`llama_cpp.llama_tokenizer.LlamaTokenizer.tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L49) — 能力已覆盖 → `compat.python.PythonLlamaTokenizer.tokenize`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [x] [`llama_cpp.llama_tokenizer.LlamaTokenizer.detokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L54) — 能力已覆盖 → `compat.python.PythonLlamaTokenizer.detokenize`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [x] [`llama_cpp.llama_tokenizer.LlamaTokenizer.encode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L62) — 能力已覆盖 → `compat.python.PythonLlamaTokenizer.encode`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [x] [`llama_cpp.llama_tokenizer.LlamaTokenizer.decode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L69) — 能力已覆盖 → `compat.python.PythonLlamaTokenizer.decode`；固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照。
- [ ] [`llama_cpp.llama_tokenizer.LlamaTokenizer.from_ggml_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L73) — 部分覆盖 → `LlamaModel.load(ModelParams.vocabOnly=true)`；仅词表加载能力存在，工厂返回类型与所有权不同。
- [ ] [`llama_cpp.llama_tokenizer.LlamaHFTokenizer.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L78) — 未迁移。
- [ ] [`llama_cpp.llama_tokenizer.LlamaHFTokenizer.tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L81) — 未迁移。
- [ ] [`llama_cpp.llama_tokenizer.LlamaHFTokenizer.detokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L88) — 未迁移。
- [ ] [`llama_cpp.llama_tokenizer.LlamaHFTokenizer.from_pretrained`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_tokenizer.py#L109) — 未迁移。

#### `llama_cpp/llama_types.py` — 0 项，勾选 0，部分 0

本文件没有显式函数/方法定义，导入、类型声明和库对象加载不增加函数数。

#### `llama_cpp/llava_cpp.py` — 7 项，勾选 0，部分 0

- [ ] [`llama_cpp.llava_cpp.llava_validate_embed_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llava_cpp.py#L78) — 未迁移。
- [ ] [`llama_cpp.llava_cpp.llava_image_embed_make_with_bytes`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llava_cpp.py#L90) — 未迁移。
- [ ] [`llama_cpp.llava_cpp.llava_image_embed_make_with_filename`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llava_cpp.py#L106) — 未迁移。
- [ ] [`llama_cpp.llava_cpp.llava_image_embed_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llava_cpp.py#L114) — 未迁移。
- [ ] [`llama_cpp.llava_cpp.llava_eval_image_embed`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llava_cpp.py#L129) — 未迁移。
- [ ] [`llama_cpp.llava_cpp.clip_model_load`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llava_cpp.py#L146) — 未迁移。
- [ ] [`llama_cpp.llava_cpp.clip_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llava_cpp.py#L154) — 未迁移。

#### `llama_cpp/mtmd_cpp.py` — 87 项，勾选 0，部分 0

- [ ] [`llama_cpp.mtmd_cpp.mtmd_default_marker`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L480) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_context_params_default`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L487) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_init_from_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L500) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L512) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_decode_use_non_causal`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L521) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_decode_use_mrope`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L530) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_support_vision`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L537) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_support_audio`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L544) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_get_audio_sample_rate`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L551) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_get_marker`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L558) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_get_audio_bitrate`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L564) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L577) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_init_from_audio`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L591) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L602) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_get_nx`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L607) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_get_ny`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L614) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_get_data`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L621) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_get_n_bytes`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L628) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_is_audio`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L635) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_get_id`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L642) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_set_id`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L649) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_set_mergeable`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L657) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_bitmap_init_lazy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L671) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunks_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L684) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunks_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L689) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunks_size`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L694) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunks_get`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L703) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L724) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_tokenize_from_parts`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L756) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_get_n_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L770) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_get_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L775) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_get_tokens_text`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L784) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_get_tokens_image`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L795) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_get_id`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L802) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_get_n_pos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L813) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_copy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L822) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L829) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_get_placeholder`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L841) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_save`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L859) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_input_chunk_load`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L877) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_image_tokens_get_n_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L890) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_image_tokens_get_nx`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L898) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_image_tokens_get_ny`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L906) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_image_tokens_get_id`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L913) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_image_tokens_get_n_pos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L924) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_image_tokens_get_decoder_pos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L936) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_encode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L952) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_encode_chunk`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L963) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_get_output_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L970) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_batch_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L977) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_batch_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L984) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_batch_add_chunk`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L993) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_batch_encode`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1004) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_batch_get_output_embd`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1015) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_get_cap_from_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1026) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_gen_audio_get_info`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1037) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_gen_inp_default`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1049) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_gen_audio_process`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1063) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_test_create_input_chunks`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1075) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_support_video`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1091) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_init_opt_default`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1098) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_bitmap_init_from_file_wrapper`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1113) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_bitmap_init_from_file`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1124) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_bitmap_init_from_buf_wrapper`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1149) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_bitmap_init_from_buf`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1159) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_get_n_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1177) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_get_n_pos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1186) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_image_get_decoder_pos`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1198) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_eval_chunks`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1230) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_eval_chunk_single`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1265) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_decode_image_chunk`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1304) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_video_init_params_default`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1325) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_video_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1339) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_video_init_from_buf`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1358) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_video_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1371) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_video_get_info`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1382) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_video_read_next`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1399) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_model_can_chat`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1416) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_gen_audio_init`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1433) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_gen_audio_free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1448) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_gen_audio_reset`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1457) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_gen_audio_set_input`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1468) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_gen_audio_step_prompt`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1487) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_gen_audio_step_gen`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1517) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_gen_audio_get_output`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1548) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_log_set`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1566) — 未迁移。
- [ ] [`llama_cpp.mtmd_cpp.mtmd_helper_log_set`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/mtmd_cpp.py#L1577) — 未迁移。

#### `llama_cpp/server/__init__.py` — 0 项，勾选 0，部分 0

本文件没有显式函数/方法定义，导入、类型声明和库对象加载不增加函数数。

#### `llama_cpp/server/__main__.py` — 1 项，勾选 0，部分 0

- [ ] [`llama_cpp.server.__main__.main`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/__main__.py#L43) — 未迁移。

#### `llama_cpp/server/app.py` — 17 项，勾选 0，部分 0

- [ ] [`llama_cpp.server.app.set_server_settings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L53) — 未迁移。
- [ ] [`llama_cpp.server.app.get_server_settings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L58) — 未迁移。
- [ ] [`llama_cpp.server.app.set_llama_proxy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L68) — 未迁移。
- [ ] [`llama_cpp.server.app.get_llama_proxy`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L73) — 未迁移。
- [ ] [`llama_cpp.server.app.set_ping_message_factory`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L95) — 未迁移。
- [ ] [`llama_cpp.server.app.create_app`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L100) — 未迁移。
- [ ] [`llama_cpp.server.app.prepare_request_resources`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L158) — 未迁移。
- [ ] [`llama_cpp.server.app.get_event_publisher`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L191) — 未迁移。
- [ ] [`llama_cpp.server.app._logit_bias_tokens_to_input_ids`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L225) — 未迁移。
- [ ] [`llama_cpp.server.app.authenticate`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L241) — 未迁移。
- [ ] [`llama_cpp.server.app.create_completion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L303) — 未迁移。
- [ ] [`llama_cpp.server.app.create_embedding`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L366) — 未迁移。
- [ ] [`llama_cpp.server.app.create_chat_completion`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L408) — 未迁移。
- [ ] [`llama_cpp.server.app.get_models`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L535) — 未迁移。
- [ ] [`llama_cpp.server.app.tokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L561) — 未迁移。
- [ ] [`llama_cpp.server.app.count_query_tokens`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L576) — 未迁移。
- [ ] [`llama_cpp.server.app.detokenize`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/app.py#L591) — 未迁移。

#### `llama_cpp/server/cli.py` — 7 项，勾选 0，部分 0

- [ ] [`llama_cpp.server.cli._get_base_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/cli.py#L11) — 未迁移。
- [ ] [`llama_cpp.server.cli._contains_list_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/cli.py#L33) — 未迁移。
- [ ] [`llama_cpp.server.cli._contains_dict_type`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/cli.py#L44) — 未迁移。
- [ ] [`llama_cpp.server.cli._parse_bool_arg`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/cli.py#L55) — 未迁移。
- [ ] [`llama_cpp.server.cli._parse_json_object_arg`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/cli.py#L72) — 未迁移。
- [ ] [`llama_cpp.server.cli.add_args_from_model`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/cli.py#L82) — 未迁移。
- [ ] [`llama_cpp.server.cli.parse_model_from_args`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/cli.py#L121) — 未迁移。

#### `llama_cpp/server/errors.py` — 5 项，勾选 0，部分 0

- [ ] [`llama_cpp.server.errors.ErrorResponseFormatters.context_length_exceeded`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/errors.py#L48) — 未迁移。
- [ ] [`llama_cpp.server.errors.ErrorResponseFormatters.model_not_found`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/errors.py#L86) — 未迁移。
- [ ] [`llama_cpp.server.errors.RouteErrorHandler.error_message_wrapper`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/errors.py#L125) — 未迁移。
- [ ] [`llama_cpp.server.errors.RouteErrorHandler.get_route_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/errors.py#L162) — 未迁移。
- [ ] [`llama_cpp.server.errors.RouteErrorHandler.get_route_handler.custom_route_handler`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/errors.py#L170) — 未迁移；嵌套函数。

#### `llama_cpp/server/model.py` — 8 项，勾选 0，部分 0

- [ ] [`llama_cpp.server.model.LlamaProxy.__init__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L15) — 未迁移。
- [ ] [`llama_cpp.server.model.LlamaProxy.__call__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L36) — 未迁移。
- [ ] [`llama_cpp.server.model.LlamaProxy.__getitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L56) — 未迁移。
- [ ] [`llama_cpp.server.model.LlamaProxy.__setitem__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L59) — 未迁移。
- [ ] [`llama_cpp.server.model.LlamaProxy.__iter__`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L64) — 未迁移。
- [ ] [`llama_cpp.server.model.LlamaProxy.free`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L68) — 未迁移。
- [ ] [`llama_cpp.server.model.LlamaProxy.load_llama_from_model_settings`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L74) — 未迁移。
- [ ] [`llama_cpp.server.model.LlamaProxy.load_llama_from_model_settings.chat_handler_with_kwargs`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/model.py#L328) — 未迁移；嵌套函数。

#### `llama_cpp/server/settings.py` — 1 项，勾选 0，部分 0

- [ ] [`llama_cpp.server.settings.ModelSettings.set_dynamic_defaults`](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/server/settings.py#L195) — 未迁移。

#### `llama_cpp/server/types.py` — 0 项，勾选 0，部分 0

本文件没有显式函数/方法定义，导入、类型声明和库对象加载不增加函数数。
