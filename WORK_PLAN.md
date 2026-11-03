# Java.cpp 实施状态

2026-11-03 `Llama.__init__` 清零：约 300 行固定原版初始化装配逐句移植至 `compat.python.PythonLlamaInit`（Deps 端口化 llama_cpp/internals/ctypes/os/chat_format 依赖；参数字段写入、ExitStack 进入顺序、tensor_split/kv_override ctypes 数组、mmap/mlock load_mode、seed/线程 falsy 默认、n_ctx=0 回读 n_ctx_train、embedding n_seq_max/kv_unified、LoRA init/callback/set_adapters 双失败原文、verbose sysinfo 打印、metadata 失败兜底、chat_template.*/chat_template.default/guess/llama-2 fallback、token id 链、recurrent/hybrid）。generate_init_contracts.py 以 AST 提取固定本体+桩端口生成 21 组对照，PythonInitContractTest 21/21 通过；桩含 FakeModel/FakeContext 属性链（n_vocab/token_*/n_ctx）与 ctypes `type*n` 乘法建模。清单：**315 绑定 / 436 已测 / 0 部分 / 0 未迁移**，751 项全部 checked；全输入一致性认证仍为 0。

2026-11-03 partial 续清 5 项：__setstate__/sample/generate/_create_completion/logit_bias_processor 由部分覆盖转为已测。generate_execution_contracts.py 新增 preset 预置采样器对照（非 tmp_sampler 路径，共 209 组）；update_inventory.py 将四项判定依据指向既有 execution/completion 固定原版对照。清单：315 绑定 / 435 已测 / 1 部分 / 0 未迁移；unchecked 仅剩 `Llama.__init__`（约 300 行原生参数装配，Jinja/动态参数/初始化失败态待整批处理）。全输入一致性认证仍为 0。

2026-11-03 清单清零批 16 项：15 项未迁移全部清零（missing=0）+ `Llama.__setstate__` 部分→已测。llama_cpp_ext.py 5 项经 `internal.LlamaExtBindings` 按 _ctypes_function_from_names 三候选名（plain/_Z/MSVC 修饰）解析 b11669 llama.dll 导出并接入 `NativeLibraries.ext`；llava_cpp.py 7 项经 `internal.LlavaLibrary`+`LlavaImageEmbed` 结构声明，`NativeLoader.loadLlava` 可选加载 llava.dll；server 3 项逐句移植——`LlamaProxy.load_llama_from_model_settings`（十种 chat_format handler/assert 原文、kv_overrides 解包、partial/from_pretrained 两路 39 键 kwargs 顺序、chat_template_kwargs truthy 门控与嵌套 `chat_handler_with_kwargs` 闭包、disk/ram cache）与 `__main__.main`（CONFIG_FILE 环境回退、yaml/json 双分支、except print+help+exit(1)、uvicorn.run kwargs）入 `PythonLlamaProxy`/`PythonServerCli`。generate_server_contracts.py 新增 21 组对照（共 102，PythonServerContractTest 102/102）；generate_llama_contracts.py 新增 setstate 委托对照（共 376）。生成器修：ctor.from_pretrained 改可调用 lambda。清单：315 绑定 / 431 已测 / 5 部分 / 0 未迁移；全输入一致性认证仍为 0。剩余 5 项部分覆盖均为 llama.py 深层项（__init__、sample、generate、_create_completion、logit_bias_processor），动态类型与初始化失败态继续核实。

2026-11-03 核验与修复：`generate_all_contracts.py` 全量重生成对照数据（helpers 批中 `transformers` 环境已安装导致 HFTokenizer 分支漂移，用 PYTHONPATH 遮蔽 stub 恢复原 ImportError 对照）；发现并恢复 4a7d3e4 丢失的全量 chat formatter 回放——拆出 `PythonChatFormatContractTest` 读取 `python-0.3.36-chat-contracts.tsv`，10,378 项全部通过。修 `generate_chat_contracts.py`（PythonUnicode 重新输出 public 修饰，对齐 10-30 包整理）与 `generate_llama_defaults.py`（补 PythonErrorType/PythonValues import）两个生成器模板。JDK21 完整 `clean verify -Pnative`：13,372 项通过、0 失败、0 跳过（含 9 项原生 + 6 项桥接集成），Spotless 208 文件全净，双 JAR 打包通过，日志 `.tools/server-app-final.log`。测试数据 Git 跟踪清单为空。server/app.py 17/17 清零状态维持。

2026-11-02 server/app.py 端点批 6 项：create_app（CONFIG_FILE env→exists 检查→JSON 直读/yaml.safe_load+json.dumps 两分支、三个 model_validate 序列、双断言原文、FastAPI/CORS/router 调用顺序、disable_ping_events→空 bytes 工厂）、prepare_request_resources（proxy None→503、logit_bias tokens 转换、grammar 端口、min_tokens→MinTokensLogitsProcessor+LogitsProcessorList 写入/extend）、get_event_publisher（双锁 async with、send channel enter/close、逐块 json.dumps、is_disconnected 与 interrupt_requests+outer.locked() 两条取消路径、except 双 print+move_on_after+raise e、[DONE] 哨兵）、create_completion（prompt 列表归一化断言、copilot-codex 别名、exclude 五键、stream→memory stream+partial+ESR、非流式断连 400）、create_embedding（bound-method threadpool、proxy None→TypeError 原文）、create_chat_completion（exclude 四键、chat bound-method）。逐句移植至 compat.python.PythonServerApp，新增 HttpException(status_code+detail)/CancelledError 异常与 Request/SendChannel/ResourceBody/EndpointDeps/CreateAppDeps 端口；get_llama_proxy 重构为 enter/close 两段保持锁序不变。generate_server_contracts.py 新增 34 组对照（共 81 组，PythonServerContractTest 81/81 通过）。清单：303 绑定 / 427 已测 / 6 部分 / 15 未迁移；app.py 17/17 清零；全输入一致性认证仍为 0。详见 docs/PYTHON_SERVER_COMPATIBILITY.md。

2026-11-02 llama_cpp.py 收尾批 50 项（文件清零）：29 项 ctypes 声明经 internal.LlamaLibrary JNA 绑定（sampled_logits/candidates_ith 四项、vocab 词表八项、chat_builtin_templates、set_sampler 与 sampler_init/name/copy/chain_get/init_adaptive_p 含新增 96 字节 LlamaSamplerI、split_path/prefix、log_get、perf_context/perf_sampler 及新增 48/16 字节 LlamaPerfContextData/LlamaPerfSamplerData ByValue、opt_param_filter_all/opt_init/opt_epoch 及镜像 Python ctypes 的 40 字节 LlamaOptParams；50 个符号均在 b11669 llama.dll 导出表确认）；21 项弃用包装逐句移植 compat.python.PythonLlamaCpp——20 个 llama_token_* 包装（_warn_deprecated 原文后委托 _llama_* 同名原生绑定）与 llama_sampler_init_grammar_lazy（7 参透传，指向 grammar_lazy_patterns）。llamacpp 对照增至 46 组，PythonLlamaCppContractTest 46/46 通过；StructSelfTest 新增五项布局校验经原生验证。清单：303 绑定 / 421 已测 / 6 部分 / 21 未迁移；llama_cpp.py 219 绑定 / 37 覆盖 / 0 未迁移；全输入一致性认证仍为 0。详见 docs/PYTHON_LLAMACPP_COMPATIBILITY.md。

2026-11-02 llama_cpp.py 第三批 30 项：28 项 ctypes 声明经 internal.LlamaLibrary JNA 绑定（state/session 文件 IO 与 state_seq 系列含 ext 标志位、batch_get_one 按值与 batch_ext 六项及新增 24 字节 LlamaEmbd、threads/attn/warmup/synchronize 与 AbortCallback、sampled_ith 三项；30 个符号均在 b11669 llama.dll 导出表确认）；2 项弃用包装逐句移植 compat.python.PythonLlamaCpp（llama_load_session_file/llama_save_session_file，_warn_deprecated 原文后委托同名原生绑定）。llamacpp 对照增至 23 组，PythonLlamaCppContractTest 23/23 通过。清单：274 绑定 / 400 已测 / 6 部分 / 71 未迁移；全输入一致性认证仍为 0。详见 docs/PYTHON_LLAMACPP_COMPATIBILITY.md。

2026-11-02 llama_cpp.py 第二批 30 项：25 项 ctypes 声明经 internal.LlamaLibrary JNA 绑定（模型形状/元数据/quantize 按引用/adapter meta 与 alora/cvec/memory seq 访问器及 3 个弃用 C 符号 llama_get_state_size/copy_state_data/set_state_data；28 个符号均在 b11669 llama.dll 导出表确认）；5 项包装逐句移植 compat.python.PythonLlamaCpp——state 三个 `_warn_deprecated`+同名委托包装，以及两个无 C 符号的 Python 端兼容包装 llama_set_adapter_lora（单元素 ctypes 数组构造后调 llama_set_adapters_lora）与 llama_apply_adapter_cvec（透传 llama_set_adapter_cvec），inline warnings.warn 原文一致。llamacpp 对照增至 19 组，PythonLlamaCppContractTest 19/19 通过。清单：246 绑定 / 398 已测 / 6 部分 / 101 未迁移；全输入一致性认证仍为 0。详见 docs/PYTHON_LLAMACPP_COMPATIBILITY.md。

2026-11-02 llama_cpp.py 首批 30 项：22 项 ctypes 声明经 internal.LlamaLibrary JNA 绑定（quantize 默认参数经新增 64 字节 LlamaModelQuantizeParams ByValue 结构、flash_attn/load_mode/ftype 枚举名、backend_free、load_from_splits/file_ptr/save_to_file/init_from_user 及 SetTensorDataCallback、time_us、max_tensor_buft_overrides、supports_mmap/mlock/rpc、n_ctx_seq/n_ubatch/n_seq_max/n_rs_seq、get_model、rope_type、n_embd_inp；30 个符号均在 b11669 llama.dll 导出表确认）；8 项弃用包装逐句移植至 compat.python.PythonLlamaCpp（_warn_deprecated 原文后经 LlamaCppAccess 端口委托同名原生绑定）。generate_llamacpp_contracts.py 生成 12 组原版对照（正常委托/委托抛错/error/ignore 过滤器分支），PythonLlamaCppContractTest 回放全过。清单：221 绑定 / 393 已测 / 6 部分 / 131 未迁移；全输入一致性认证仍为 0。详见 docs/PYTHON_LLAMACPP_COMPATIBILITY.md。

2026-11-01 chatregistry 迁移：再完成 21 项——llama_cpp_ext 装饰器两项、chat_format 注册表七项与两个装饰器工厂、两个 Protocol 省略号方法体、strftime_now/tojson（新增 PythonJson）、chat_formatter_to_chat_completion_handler 及嵌套 handler、app.py 五个端点。生成器命名为 generate_chatregistry_contracts.py 以避免与既有 generate_chat_contracts.py（format_* 批次）冲突。generate 14 组原版对照，PythonChatContractTest 回放全过。清单：92 绑定 / 211 已测 / 6 部分 / 442 未迁移；全输入一致性认证仍为 0。详见 docs/PYTHON_CHATREGISTRY_COMPATIBILITY.md。

2026-11-01 server 迁移：再完成 20 项——server/cli.py 七个 argparse/pydantic 帮助函数（typing 注解端口、最小 JSON 解析器、add_argument 序列对照）、settings.set_dynamic_defaults、app.py 模块级六项（含 get_llama_proxy 双锁顺序）、model.py LlamaProxy 六项（默认加载/切换关闭/dump/validate/iter/free）。generate_server_contracts.py 生成 47 组原版对照，PythonServerContractTest 回放全过。清单：92 绑定 / 190 已测 / 6 部分 / 463 未迁移；全输入一致性认证仍为 0。详见 docs/PYTHON_SERVER_COMPATIBILITY.md。

2026-11-01 helpers 迁移：再完成 20 项——_utils 的 suppress_stdout_stderr 三项（os/sys/null 文件端口，fd dup/dup2/close 顺序与 ipykernel 流副本恢复）与 MetaSingleton/Singleton 两项（按类注册表，构造异常不注册）、llama_tokenizer 的 BaseLlamaTokenizer/LlamaHFTokenizer 六项（ABC/NIE、errors=ignore、prev_tokens 差分、from_pretrained 工厂）、llama_speculative 四项（ngram 滑窗查找）、server/errors 五项（格式化模板、stderr 分段、路由包装）。新增共享 `PythonAbstract` ABC 检查并重构 PythonLlamaCache 复用。generate_helpers_contracts.py 生成 31 组原版对照，PythonHelpersContractTest 回放全过。清单：92 绑定 / 170 已测 / 6 部分 / 483 未迁移；全输入一致性认证仍为 0。详见 docs/PYTHON_HELPERS_COMPATIBILITY.md。

2026-10-30 lowlevel/缓存补齐：再完成 20 项部分覆盖迁移——llama_cache 的 BaseLlamaCache 4 个 abstractmethod（迁移 ABC 实例化规则：缺实现类构造抛原版排序 TypeError，基类方法体显式调用抛 NotImplementedError）与 LlamaDiskCache 6 项、_logger 的 llama_log_callback/set_verbose 2 项（新增 PythonLlamaLogger，级别映射/CONT 沿用/logger.level 门控/stderr 分写）、llama_cpp 的 _warn_deprecated（过滤器动作与调用方模块规则对照）和 llama_context_default_params/llama_init_from_model（b11669 168 字节结构对齐后列绑定）、llama_tokenizer 的 from_ggml_file、llama.py 的 ctx/model/__del__/free_lora_adapter。新增 generate_lowlevel_contracts.py 25 组原版对照 + 缓存 ABC 8 组；`ctx`/`model` 修正为关闭后仍返回句柄（原版语义），PythonBridgeIntegrationTest 断言同步。compat.python 包 JUnit 13,085 项通过、0 失败。清单更新为 92 项绑定、150 项已测能力、6 项部分覆盖、503 项未迁移；全输入一致性认证仍为 0。逐项状态见 README、docs/python-api-audit.json 和 docs/PYTHON_LOWLEVEL_COMPATIBILITY.md。

2026-10-30 internals 迁移：完成 `_internals.py` 全部 29 项部分覆盖函数的 Java 移植，新增 `PythonInternalsPort`/`PythonInternalsBatch`/`PythonInternalsNative`/`PythonExitStack` 端口层及 `PythonLlamaModel`/`PythonLlamaContext`/`PythonLlamaBatch`/`PythonInternalsSampler` 四个包装类；`internal` 新增 `LlamaBatch` 结构与 llama_batch_init/llama_batch_free/llama_decode/llama_sampler_chain_n/llama_sampler_chain_remove/llama_sampler_get_seed 六项绑定。新增 17 组固定原版追踪端口对照，全量 mvn test 13,088 项通过、0 失败。清单更新为 90 项绑定、132 项已测能力、26 项部分覆盖、503 项未迁移，未勾选 529 项；全输入一致性认证仍为 0。逐项状态见 README、docs/python-api-audit.json 和 docs/PYTHON_INTERNALS_COMPATIBILITY.md。

2026-10-29 Jinja2 动态类型续作：新增 `PythonStrings`（38 个 str 方法的 CPython 移植），`PythonJinjaDialect` 将 `x.method(args)` 重写为 `x|py_method(args)` 过滤器调用，`PythonJinjaTemplate` 注册全部 py_* 过滤器并以 Object[] 参数重载保留原始类型。修复两处 Jinja2 语义偏差：启用 LegacyOverrides 的 `useTrimmingForNotesAndExpressions` 与 `parseWhitespaceControlStrictly`，使 `{{-`/`-}}`/`{#-` 空白控制生效且 `{{ -x }}` 仍按一元负号解析；`keep_trailing_newline=false` 改为在 normalize 阶段对模板源码做换行归一（\r\n|\r→\n）并删除结尾一个换行，Jinjava 侧改为 keepTrailingNewline(true)，避免剥离表达式渲染产生的尾部换行。`Py.equal` 中 PyComparable 提前参与相等分派。`PythonStrings` 逐一对照 CPython 3.12：补齐四类参数个数错误格式（clinic `() takes at least/most`、manual `expected at least/most`、`str.X() takes exactly one argument`、`str.X() takes no arguments`）、逐方法错误消息（startswith 的 tuple 元素检查、replace 的 argument N、fill character 校验、slice indices、`can only join an iterable` 等）、`__index__` 协议转换（Py.index，显式 None 报错）、切片索引专属报错、code point 索引、isdigit/isnumeric 的 Unicode 精确码位表（No+Digit 35 段、Lo 数值 81 项）、title/istitle 的 cased 边界（数字与非 cased 字母均重置边界）、expandtabs(tabsize<=0) 删除制表符、capitalize/title 的 titlecase 首字符。自写 131 组用例与 CPython 3.12 实际执行逐字比对全部一致（值、异常类别与消息原文）。63 组 Jinja 契约全部通过，全量 mvn test 13,005 项通过、0 失败（含 15 项原生/桥接集成测试）；测试数据继续全部留在被忽略的 src/test/resources。动态类型契约生成器与对应 JUnit 仍未建立，机器清单与一致性认证暂不更新。

2026-10-30 包整理：CPython 动态协议层整体迁入新包 `dev.javacpp.python`（Py 门面、8 个 Py* 协议接口、PythonStrings/Slices/Numbers/Values/Bytes/Unicode、PythonException/ErrorType 共 17 个文件），与 llama 适配层 `compat.python` 平级。新包仅依赖 java.*，可直接服务其他 Java 项目；39 处调用方补齐 import，原包私有辅助类与 PythonException 三参构造、PythonNumbers 成员放开为 public。全量 mvn test 13,005 项通过、0 失败。

2026-10-29 本轮补齐 from_pretrained 的 Windows 字符串/provider 路径：修正无效字符范围的 fnmatch 处理、仓库外文件路径的 ValueError 原文（包含路径 repr），并令缓存下载参数使用独立 Map，避免后一次调用修改首次下载请求。新增 12 组固定原版对照并在全部仓库样例中比较请求参数保留状态；配置/仓库共 89 组通过。机器清单与 README 同步为 llama.py 39/49（79.59%）已测窄范围能力、10 项部分实现。完整 verify -Pnative 为 12,942 项通过、0 失败、0 跳过；包含工作区同步进行的缓存工作。全行为一致认证继续为 0，测试数据 Git 跟踪清单为空。

2026-10-29 缓存续作：核对 llama_cache.py 全部 18 项定义，新增 PythonLlamaCache 基类和 PythonLlamaDiskCache 的 6 项存储端口控制流程，Llama.setCache 接入共同基类。修正 RAM cache_size 的列表推导先于 sum 的异常优先顺序。新增 114 组固定原版缓存对照，状态对照增至 739 组。同步已有补充映射及仓库匹配复核后，全清单为 84 项绑定、98 项局部能力覆盖、59 项部分覆盖、510 项未迁移，未勾选 569 项；全输入一致性认证仍为 0 项。最终 clean verify -Pnative 为 12,942 项通过，0 失败、0 错误、0 跳过，格式和两个 JAR 打包通过，日志 .tools/cache-full-verify.log。历史段落计数保留其对应轮次，最新逐项状态见 README、docs/python-api-audit.json 和 docs/PYTHON_CACHE_COMPATIBILITY.md。

2026-10-29 机器映射同步：已将新增实现及 services/completion/configuration 对照证据纳入生成器，更新 README、python-api-audit.json 与全文件映射。llama.py 当前为 38 项已测窄范围能力、11 项部分实现、0 项缺少对应实现；全部 49 项的全行为一致验收继续进行。原版未充分覆盖的构造、资源释放/自动析构、completion 缓存与动态类型、配置重建、仓库 provider 和回调视图路径保留部分实现标记。全项目为 84 项绑定、95 项已测能力，合计 179/751；全行为认证保持 0。使用者核实的最近完整验证为 12,814 项通过，0 失败、0 跳过。本次仅同步元数据和文档，重生成幂等、49 项集合与 Git 忽略检查通过。

2026-10-29 本轮补充（实现状态待逐项复核后更新机器清单）：新增完整采样策略、custom logits 回调、embedding 批处理、completion 的 stop/echo/logprobs/流式状态机、chat/OpenAI 转发、配置快照/重建及 from_pretrained 离线仓库端口。新增 PythonNativeLlama 构造入口，按 Python 默认值直接设置 C 参数，并接入 LoRA 资源清理、Cleaner 和关闭后的 ctx/model 空值。删除关闭后重新安装清理动作的额外限制，保留已有 draft 空切片修复。

新增固定原版对照：services 235 组、completion 140 组、configuration/repository 74 组，共 449 组；分词器调用轨迹、错误前流式块及随机种子也已核对。补充真实模型采样、embedding、构造、配置重建和 completion 测试。构造流程的完整 Jinja2 语义、动态参数类型转换和初始化中途失败的状态仍需继续核实；当前机器清单继续保留原状态，避免将新增代码直接计为完整一致。

更新：2026-10-28。

用户确认采用现有单工程结构。`src/Java_Cpp` 为封装源码根目录，仅包含必要迁移项；demo 位于 `src/main/java/dev/javacpp/demo`，测试位于 `src/test/java`。根目录保留唯一 POM，通过 build-helper 添加封装源码根目录。根 JAR 仅打包类库；独立 assembly 生成 demo JAR。

## 已实现

- M1：Windows x64 DLL 加载、导出符号核对、版本锁与结构体自检。
- M2–M3：设备枚举、显存信息、逐行日志和最近诊断；回调重入保护。
- M4–M5：GGUF 预检、模型元数据、取消加载、词表和 UTF-8 流式解码。
- M6–M7：分块推理、四种停止原因、采样链、种子与重置、资源关闭和并发检测。
- M8：模板识别、思考过滤、多轮历史裁剪、异常和取消时的历史事务。
- M9：E1xx–E5xx 类型化异常、建议、原生日志与异常原因。
- M10：main、C01–C14 检查、四轮脚本、交互命令、本地模型入口与固定 revision 下载器。
- M11：普通单元测试、真实模型集成测试、Google 格式验证、公开 API 与 demo 依赖边界检查。

## 核验约定

绑定按本地 `git show b11669:include/llama.h` 与 `ggml/include` 核对。设备注册的 count/get 来自 ggml.dll，设备属性来自 ggml-base.dll，已经官方 CPU 包验证。即使上游参考 checkout 更新，Java 绑定继续锁定 b11669。升级版本时同步修改结构体、符号与版本常量，并运行原生测试。

模型当前文件包含额外 MTP 张量，采用实算大小与摘要记录。下载器的目录、固定 revision 和期望摘要独立于用户现有文件。完整 C01–C14 结果写入 check-report.txt，发布用验证摘要写入 docs/VALIDATION.md。

## 资源与锁顺序

运行时与日志回调保留到 JVM 退出。模型操作取得模型生命周期读锁，然后 context 操作取得其独占锁，生成再取得 sampler 锁。关闭模型取得生命周期写锁，等待操作结束后释放子 context 和模型。原生回调会在取得运行时/模型锁之前检查重入，用户 token 回调重入 context、sampler、session 或关闭模型均被拦截。

## 后续可选范围

增量 KV、多模态、HTTP 服务、嵌入和其他平台沿用原任务文档中的选做范围。

## Python 全量函数复刻进度

用户要求以固定 Python 原版的输出、异常和警告为逐项复刻标准。最新清单为 751 项：62 项同名绑定、85 项独立能力实现、48 项部分覆盖、556 项尚无对应实现。604 项尚未打勾，全输入一致性认证继续为 0 项。

静态聊天模块已移植 37 项，本批新增 RAM 缓存、状态和处理器 12 项，分别有 10,376 条及 738 组原版/CPython 对照。完整 `verify -Pnative` 为 11,452 项通过、0 跳过。记录见 `docs/PYTHON_CHAT_COMPATIBILITY.md` 和 `docs/PYTHON_STATE_COMPATIBILITY.md`，生成器位于 `tools/python_compat`。继续工作优先处理同原生 revision 的完整 Python 推理基准、Jinja2/chat handler、生成与缓存复用，并保持机器清单与 README 同步。

2026-10-28 轮迁移 llama.py 的 8 项状态访问和数值辅助方法，入口 `compat.python.PythonLlama`，374 组固定原版对照。包括共享 buffer 视图、独立快照、缓存/种子赋值、reset 的 recurrent/hybrid 回调顺序及 single log-softmax。完整 `verify -Pnative` 为 11,827 项通过、0 跳过，格式和 JAR 打包通过。接口形态、浮点容差和后续 eval/generate 连接范围见 `docs/PYTHON_LLAMA_COMPATIBILITY.md`。后续继续以约 8 项函数为一轮推进。

2026-10-28 第二轮迁移 Llama/LlamaTokenizer 的 8 项分词接口；324 组原版对照及 1 项新增真实模型测试通过。完整 `verify -Pnative` 为 12,152 项通过，0 跳过，格式和 JAR 打包通过。分词开关、UTF-8 ignore、prev_tokens、自定义分词器和 Vocab 接入见 `docs/PYTHON_TOKENIZER_COMPATIBILITY.md`。

2026-10-28 第三轮按全文件范围建立 llama.py 的 49 项函数与异常映射，包含 4 个嵌套函数、23 处显式异常/断言/警告。新增求值/状态/清理及原生查询，sample/generate 状态机已有工厂注入下的对照，完整参数策略继续列为部分实现。206 组原版对照及新增原生求值、保存恢复、继续生成测试通过；完整 verify -Pnative 为 12,359 项通过，0 跳过。文件内 28 项窄范围实现、9 项部分实现、12 项待实现，具体目标和异常要求见 `docs/PYTHON_LLAMA_MAPPING.md`。映射中的待实现目标保持原计数。

## 测试数据维护规则

测试数据及 Base64 样例文件统一保存在被 `.gitignore` 忽略的 `src/test/resources/`。旧桥接 TSV 已从 Git 索引移除，本地保留。生成脚本、来源、计数及摘要可以提交；根目录 `AGENTS.md` 记录后续规则。清洁检出先按 `tools/python_compat/README.md` 生成六组数据，再执行 Maven 测试；提交前确认 `git ls-files src/test/resources` 输出为空。

2026-10-28 核实补充：全文件 49 项为定义映射，仍有 9 项部分实现和 12 项待实现。发现并修正 generate 的 draft 输入赋值额外长度限制，按 NumPy 保留单元素向空切片广播行为。固定原版执行对照由 206 增至 207 组；新增反例在修改前失败、修改后通过。全函数输入输出一致性仍待继续核实。

2026-10-29 最终核验：补充实现后的完整 verify -Pnative、Google Java Format 和两个 JAR 打包均成功，测试数据 Git 跟踪清单为空。完整日志保留在被忽略的 .tools/llama-supplement-final.log。

2026-10-29 更新后复核：检查 5427fc3 新增实现，复现并修正 PythonPretrained 字符组 glob 转换对字面方括号的处理，新增 3 组固定原版执行对照（修改前均触发 Java 正则语法错误，修改后通过）。configuration/repository 对照增至 77 组。完整 verify -Pnative：12,814 项通过，0 失败、0 错误、0 跳过，格式和打包通过，日志 .tools/review-updated.log。机器映射仍为补全前状态；完整 Jinja2、构造失败中间状态、动态类型及同原生版本的端到端 Python 推理对照继续待核实。

2026-11-01 第六轮迁移  剩余 53 项： 17 项访问器/free 闭包、 31 项（KV 序列操作、state、encode、线程数、logits/embeddings 指针、timings、14 项 deprecated NotImplementedError）、 4 项与 。 新增 15 个 b11669 JNA 绑定，/ 对应扩展。internals 固定原版对照增至 24 组；生成器修正 CPython 循环 GC 跨用例漂移（gc.disable + 逐用例 collect）。 24/24 通过。清单：107 绑定、264 已测、6 部分、374 未迁移。剩余  未覆盖约 29 项（LlamaTokenDataArray、LlamaSamplingContext、CustomSampler、LlamaSampler 其余 add_*/accept/clone 等）。

2026-11-01 第六轮迁移 _internals.py 剩余 53 项：LlamaModel 17 项访问器/free 闭包、LlamaContext 31 项（KV 序列操作、state、encode、线程数、logits/embeddings 指针、timings、14 项 deprecated NotImplementedError）、LlamaBatch 4 项与 normalize_embedding。LlamaLibrary 新增 15 个 b11669 JNA 绑定，PythonInternalsPort/PythonNativeInternals 对应扩展。internals 固定原版对照增至 24 组；生成器修正 CPython 循环 GC 跨用例漂移（gc.disable + 逐用例 collect）。PythonInternalsContractTest 24/24 通过。清单：107 绑定、264 已测、6 部分、374 未迁移。剩余 _internals.py 未覆盖约 29 项（LlamaTokenDataArray、LlamaSamplingContext、CustomSampler、LlamaSampler 其余 add_*/accept/clone 等）。

2026-11-01 第七轮收尾 _internals.py 剩余 29 项，至此该文件 120 项全覆盖：LlamaTokenDataArray 2 项（candidatesData/tokenDataArray/candidatesStore/candidatesAssign 端口）、LlamaSamplingContext 6 项（reset 重绑+grammar.reset、cp 共享标量复制列表、prev_str[-n:]、sample NIE、accept）、CustomSampler 4 项（sampler_i 六槽位、literal 0 可选槽、iface/ctx 指针装配）、LlamaSampler 17 项（其余 add_*、add_custom、accept、free_sampler/__del__、clone 及 clone.free_sampler）。LlamaLibrary 新增 8 个 b11669 JNA 绑定及 LlamaSamplerApply 回调、NativeSamplerI/NativeLlamaSampler 结构。internals 对照增至 31 组，PythonInternalsContractTest 31/31 通过。清单：115 绑定、293 已测、6 部分、337 未迁移。

2026-11-01 第八轮 llama_chat_format.py 剩余 31 项：grammar 三助手、HF autotokenizer/tokenizer_config formatter+handler、_convert_completion_to_chat_function+流式嵌套、functionary v1/v1v2 嵌套 helper（type_definition/shared_definitions/schema_from_functions/prepare_messages/message_to_str/get_grammar）、媒体消息辅助与 Qwen25VL __call__。新增 PythonChatFormatHandlers（Grammar/Tokenizer/Jinja/QwenLlama 端口），generate_chathandlers_contracts.py 21 组对照，PythonChatHandlersContractTest 21/21 通过。清单：115 绑定、324 已测、6 部分、306 未迁移。
2026-11-01 第十一轮 mtmd_cpp.py 首批 30 项：mtmd.h 核心 API ctypes 声明经 internal.MtmdLibrary JNA 绑定（context_params/input_text 结构与 sched_eval/progress/lazy 回调按 pinned 布局；NativeLoader.loadMtmd 可选加载 mtmd.dll），弃用包装 mtmd_get_audio_bitrate 逐句移植（DeprecationWarning 原文+委托调用）。generate_mtmd_contracts.py 2 组对照，PythonMtmdContractTest 2/2 通过。清单：144 绑定、383 已测、6 部分、218 未迁移。
2026-11-01 第十二轮 mtmd_cpp.py 第二批 35 项：tokenize_from_parts、chunk/image_tokens 访问器（size_t 出参 LongByReference、DecoderPos/Caps/GenAudioInfo/GenInp/GenOut/InitOpt/BitmapWrapper ByValue 结构）、encode/batch/gen_audio/helper 共 33 项 JNA 绑定；helper_bitmap_init_from_file/_buf 实体包装逐句移植（opt None 默认化 + wrapper .bitmap）。mtmd 契约增至 4 组，PythonMtmdContractTest 4/4 通过。清单：177 绑定、385 已测、6 部分、183 未迁移；mtmd_cpp.py 累计 65/87。
2026-11-01 第十三轮 mtmd_cpp.py 收尾 22 项全部为 JNA 绑定：eval_chunks/eval_chunk_single/decode_image_chunk（PostDecodeCallback+HelperEmbdBatch）、video_* 六项（VideoInitParams/VideoInfo ByValue）、gen_audio helper 八项（GenAudioInp 按引用、多类型出参 ByReference）、model_can_chat、log_set/helper_log_set。mtmd_cpp.py 87/87 清零。清单：199 绑定、385 已测、6 部分、161 未迁移。
- 第九轮： 收尾 29 项（Jinja2ChatFormatter、functionary 外层+流式、Llava15/MTMD 生命周期与 __call__、chatml_function_calling），24 组契约全过；该文件已清零。covered 353/missing 277。
- 第十轮： 全部 29 项（LlamaGrammar、_build_repetition、完整 SchemaConverter、json_schema_to_gbnf），16 组契约全过；该文件清零。covered 382/missing 248。
