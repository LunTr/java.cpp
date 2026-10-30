# Python 原版核对与错误桥接

核对日期：2026-10-23。原版基线为 llama-cpp-python 0.3.36，源码 commit `1652066e0af45f2313b339670ef9555e8a54e545`，vendor/llama.cpp 为 `0c1e57098bba43ac29e6e3b677cdceebdd22334f`。原版源码来自官方仓库，下载的 25 个 Python 文件逐个核对 Git blob SHA-1，并记录 SHA-256，清单保存在 [审计 JSON](python-api-audit.json)。

已加入 37 项静态聊天函数，以及本批 12 项 RAM 缓存、状态和处理器函数。最新完整构建共 11,452 项通过，详见 [聊天函数移植记录](PYTHON_CHAT_COMPATIBILITY.md) 和 [状态/缓存记录](PYTHON_STATE_COMPATIBILITY.md)。下文错误桥接的 300 条契约及其验证边界继续保留。测试数据统一在被忽略的 `src/test/resources/` 本地生成，步骤见 [生成说明](../tools/python_compat/README.md)。

## 数量与进度

| 项目 | 数量 |
|---|---:|
| 源码 def/async def 定义 | 786 |
| 去重函数/方法定义项 | 751 |
| 合并的重复同名定义 | 35 |
| 模块函数/类方法 | 702 |
| 嵌套函数 | 49 |
| 核心包 / 可选 server | 712 / 39 |
| 完成标记：同名绑定 / 独立能力 | 55 / 63 |
| 部分覆盖 | 60 |
| 无明确对应迁移项 | 573 |
| 尚未打勾（部分 + 无明确对应） | 633 |
| 完整函数行为、报错、警告一致性认证 | 0 |

计数按 `(文件, 限定函数名)` 去重，包括属性 getter、构造器、特殊方法、私有和嵌套函数。继承生成成员、lambda、赋值别名和导入重导出不重复计数。同一能力在底层 C 绑定、Python 内部包装、高层入口可能形成多个独立定义项，进度按定义项记录。README 尾部列出了全部 751 项，并逐项给出完成标记、Java 对应和已知差异。

`llama_cpp.py` 有 255 个公开 C API 函数和 1 个警告助手。当前 Java 声明了 57 个同名入口，其中 2 个涉及上下文 ABI 差异，列为部分覆盖，其余 55 个完成 JNA 绑定。另有 9 个 ggml 补充入口。高层 `Llama` 的 31 个普通公开命名成员中，5 个查询成员已覆盖；这 31 个成员包含 4 个属性 getter。

## 桥接接口

源码分两个包：`io.github.yaulun.javacpp.python` 为独立 CPython 动态类型层（`Py` 门面、Py* 协议接口、str/数值/切片/字节/Unicode 内建操作及 PythonException/ErrorType），仅依赖 java.*，可脱离 llama 适配层复用；`io.github.yaulun.javacpp.compat.python` 为 llama_cpp 适配层（Llama/completion/chat/Jinja/warnings/bridge），单向依赖前者。均位于 `src/Java_Cpp/io/github/yaulun/javacpp/`，沿用根 Maven 工程。核心 Java 异常保留诊断码、建议、日志和 cause；Python-facing 入口返回 `PythonException`，其 `type()` 为 Python 类别，`getMessage()` 为原版消息，`toString()` 为 Python 异常末行。空 AssertionError 格式为单独的类别名称。

```java
try (LlamaModel model = PythonErrorBridge.loadModel(modelPath, ModelParams.defaults());
    LlamaContext context = PythonErrorBridge.createContext(model, ContextParams.defaults())) {
  int[] tokens = PythonErrorBridge.tokenize(model.vocab(), promptBytes, true, false);
  PythonErrorBridge.decode(context, tokens);
} catch (PythonException error) {
  System.err.println(error.type().pythonName());
  System.err.println(error.getMessage());
  Throwable original = error.getCause();
}
```

`PythonErrorBridge.call(PythonCall, Supplier)` 和 `run(PythonCall, Runnable)` 可包装现有调用。`translate` 对已知分支返回映射结果，对未知分支返回空 Optional；call/run 将未知失败原样抛出。用户监听器异常、普通 IllegalArgumentException、NullPointerException、IllegalStateException 等保持原状。

推理 demo 的文件头检查、模型加载和上下文创建已接入桥接。依赖诊断程序继续使用 Java 原始异常，以便保留 E 码和本地诊断信息。

### 输入和正常路径适配

`completionConfig(maxTokens, promptTokens, contextLength)` 对齐原版 `_create_completion` 的三个分支：

- prompt token 数达到上下文容量时，抛出原版 ValueError 类别及消息。
- maxTokens 为 null、0 或负数时，采用剩余上下文容量。
- 正数超出剩余容量时，截断到剩余容量。

这里的 token 数对应一次新完整 prompt。CLI 参数检查和原来的 GenerationConfig 构造器保持各自的 Java 校验规则；应用若采用 Python 规则，应使用 completionConfig。

`PythonErrorBridge.tokenize` 接收原始 bytes 对应的 byte[]，包含 NUL 等字节，默认 add_bos=true、special=false；底层新增 Vocab.tokenizeBytes 并复制输入。原 String 分词入口继续执行已有 NUL 校验。`sample` 在空上下文上抛出无消息的 Python AssertionError。

## 完整 Java 错误码对应表

| Java 失败 | Python-facing 结果 | 条件和处理依据 |
|---|---|---|
| E101 平台 | 未映射 | Python loader 支持的平台与 Java 布局策略不同 |
| E102 目录 | 未映射 | Python os.add_dll_directory / 查找分支依赖实际路径与 OS 错误 |
| E103 文件 | 未映射 | Python loader 搜索候选名称；缺少原始加载阶段信息 |
| E104 加载 | 未映射 | JNA 与 ctypes 的原始 OS 文本和加载顺序不同 |
| E105 导出符号 | 未映射 | Python 动态绑定错误类型依赖导入阶段与符号 |
| E106 版本/ABI | 未映射 | Java 独立的版本锁和结构体检查 |
| E107 后端 | 未映射 | Java 增加了 CPU 后端完整性检查 |
| E201 模型路径 | ValueError | 原路径不存在时为 missing-path 消息；存在目录时为 failed-load 消息 |
| E202 文件读取 | 未映射 | Python 经原生读取，无法仅据 Java IO 错误确认相同 Python 分支 |
| E203/E204 GGUF 预检 | ValueError | 使用 Python 原生加载失败对应的消息；Java 提前校验仍会改变原生日志时机 |
| E205 加载 NULL | ValueError | 必须保留 native function=model_load_from_file、返回 NULL 的结构化证据 |
| E205 vocab NULL | ValueError | 必须保留 native function=model_get_vocab、返回 NULL 的证据 |
| E205 其他阶段 | 未映射 | 元数据等错误与上述原版分支不同 |
| E206 取消 | ValueError 或未映射 | 原生返回 NULL 且无 Java 回调异常时使用 failed-load；回调异常继续保留 |
| E301 context NULL | ValueError | 仅匹配 llama_init_from_model 返回 NULL；其他分配阶段保留 Java 失败 |
| E302 native decode | RuntimeError | 原生 llama_process 返回 1 时，恢复原版 decode-return-code 消息 |
| E302 completion 长度 | ValueError | 原始 promptTokens >= contextLength 的 completion 分支 |
| E302 Java 预检查 | 未映射 | 缺少原版 native 返回值，保留 Java 容量保护 |
| E303/E304 decode | RuntimeError | 需要真实 llama_process 返回码；内部位置/批次检查不猜测原版结果 |
| E401 tokenize | RuntimeError | 需要 llama_tokenize 重试后的负数返回值；原始 bytes 用 Python repr 格式化 |
| E401 其他字节转换 | 未映射 | tokenToPiece、detokenize、int32 极限和空词表的 Python 路径不同 |
| E402/E403 模板 | 未映射 | Python 的模板、Jinja、handler 回退行为尚未全量移植 |
| E501 sampler | 未映射 | 原版部分 NULL 路径没有同等 Python 异常检查，无法直接转换为 ValueError |

NativeFailure 独立保存原生函数名和返回值，桥接不从用户可见的字符串中解析状态码。InferenceException、TokenizationException、ModelLoadException 等保留已有构造方法与消息格式。

## 警告桥接

```java
PythonWarnings warnings = PythonWarnings.defaults(w -> System.err.print(w.format()));
PythonWarningLocation origin = new PythonWarningLocation(
    "llama_cpp/llama_cpp.py", 1440, "llama_cpp.llama_cpp", null);
warnings.deprecated("llama_load_model_from_file", "use llama_model_load_from_file instead", origin);
```

`deprecated` 使用原版旧符号警告文本，`duplicateBos` 使用原版重复 BOS 文本。类别为 DeprecationWarning 和 RuntimeWarning。来源支持文件、行号、模块与可选源码行；格式与给定来源的 warnings.formatwarning 对照，包含 Python Unicode 空白裁剪规则。

默认策略抑制导入模块中的弃用警告，`__main__` 中的弃用警告按默认重复抑制规则处理。withAction 提供 default、always、ignore、error、module、once；error 策略抛出对应警告类别的 PythonException。registry 为实例所有并按模块记录；once 路径按当前 CPython 3.13.9、显式 module registry 的实际行为对照。任意 regex 过滤规则、外部 Python warning registry、warn_explicit(registry=None) 全局路径、自动 Python stacklevel/traceback 与安装路径均未移植。来源由调用方显式提供。

Java 的 NativeLogBridge 继续服务原生日志。它的严重级别映射、逐行缓存、监听器和 demo 前缀，与原版 `_logger.py` 的全局 logger/stderr 方案仍有差异；PythonWarnings 负责 Python 层警告。

## 验证方法和边界

契约基准使用本机 Anaconda CPython 3.13.9。300 条 golden 样例来自以下执行：

- 从固定原版源码提取模型/context 初始化、tokenize、decode、sample 的函数体，对原生调用提供明确返回值桩，执行真实 Python 分支并记录类别及消息。
- 执行 `_create_completion` 的原始长度判断、非正数规则和截断语句。
- 执行原版弃用与重复 BOS 警告调用，记录实际类别和消息。
- 使用 CPython bytes repr 和 warnings.formatwarning 生成字节、引号、控制字符、中文和 Unicode 空白样例。
- 使用 CPython warning registry 测试六种 action，记录发出与转成异常的数量。

Java 动态测试读取这些固定样例，逐字比对类别、消息和格式。另有真实文件系统预检、未知错误原样传播、cause 保留、bytes 复制与默认警告过滤测试。原生测试验证真实模型取消加载、原始 bytes/NUL 分词、空上下文采样及正常推理；原有九项真实模型集成测试继续通过。

错误桥接阶段共 335 项测试通过，其中桥接契约/单元测试 303 项、桥接原生测试 2 项、原工程测试 30 项。聊天函数测试 10,378 项，本批增加状态/缓存/处理器测试 739 项，最新共 11,452 项通过。原生测试参数为 b11669 CPU 包和现有 Qwen3.5-0.8B Q8 GGUF。构建执行 Google 格式检查。

上述原版源码分支使用 native 桩，未安装或运行完整 Python 原生模型包。原版 vendor 的上下文结构为 37 字段/160 字节，Java 为 38 字段/168 字节，二者使用不同原生 commit。新桥接不改变这项 ABI 差异。完整输出内容、采样序列、OpenAI 响应结构、全部参数、缺少的函数和全部异常/警告时序尚未达到全量一致。

## 来源

- [官方发布版本](https://pypi.org/project/llama-cpp-python/0.3.36/)。
- [原版高层 API](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py)。
- [原版内部包装](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_internals.py)。
- [原版原生绑定与弃用警告](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama_cpp.py)。
- [原版日志处理](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/_logger.py)。
- [契约生成记录](python-bridge-contracts.json) 与测试数据 `src/test/resources/python-0.3.36-contracts.tsv`。
