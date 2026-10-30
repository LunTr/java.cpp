# Java.cpp 实施状态

2026-10-29 Jinja2 动态类型续作：新增 `PythonStrings`（38 个 str 方法的 CPython 移植），`PythonJinjaDialect` 将 `x.method(args)` 重写为 `x|py_method(args)` 过滤器调用，`PythonJinjaTemplate` 注册全部 py_* 过滤器并以 Object[] 参数重载保留原始类型。修复两处 Jinja2 语义偏差：启用 LegacyOverrides 的 `useTrimmingForNotesAndExpressions` 与 `parseWhitespaceControlStrictly`，使 `{{-`/`-}}`/`{#-` 空白控制生效且 `{{ -x }}` 仍按一元负号解析；`keep_trailing_newline=false` 改为在 normalize 阶段对模板源码做换行归一（\r\n|\r→\n）并删除结尾一个换行，Jinjava 侧改为 keepTrailingNewline(true)，避免剥离表达式渲染产生的尾部换行。`Py.equal` 中 PyComparable 提前参与相等分派。`PythonStrings` 逐一对照 CPython 3.12：补齐四类参数个数错误格式（clinic `() takes at least/most`、manual `expected at least/most`、`str.X() takes exactly one argument`、`str.X() takes no arguments`）、逐方法错误消息（startswith 的 tuple 元素检查、replace 的 argument N、fill character 校验、slice indices、`can only join an iterable` 等）、`__index__` 协议转换（Py.index，显式 None 报错）、切片索引专属报错、code point 索引、isdigit/isnumeric 的 Unicode 精确码位表（No+Digit 35 段、Lo 数值 81 项）、title/istitle 的 cased 边界（数字与非 cased 字母均重置边界）、expandtabs(tabsize<=0) 删除制表符、capitalize/title 的 titlecase 首字符。自写 131 组用例与 CPython 3.12 实际执行逐字比对全部一致（值、异常类别与消息原文）。63 组 Jinja 契约全部通过，全量 mvn test 13,005 项通过、0 失败（含 15 项原生/桥接集成测试）；测试数据继续全部留在被忽略的 src/test/resources。动态类型契约生成器与对应 JUnit 仍未建立，机器清单与一致性认证暂不更新。

2026-10-30 包整理：CPython 动态协议层整体迁入新包 `io.github.yaulun.javacpp.python`（Py 门面、8 个 Py* 协议接口、PythonStrings/Slices/Numbers/Values/Bytes/Unicode、PythonException/ErrorType 共 17 个文件），与 llama 适配层 `compat.python` 平级。新包仅依赖 java.*，可直接服务其他 Java 项目；39 处调用方补齐 import，原包私有辅助类与 PythonException 三参构造、PythonNumbers 成员放开为 public。全量 mvn test 13,005 项通过、0 失败。

2026-10-29 本轮补齐 from_pretrained 的 Windows 字符串/provider 路径：修正无效字符范围的 fnmatch 处理、仓库外文件路径的 ValueError 原文（包含路径 repr），并令缓存下载参数使用独立 Map，避免后一次调用修改首次下载请求。新增 12 组固定原版对照并在全部仓库样例中比较请求参数保留状态；配置/仓库共 89 组通过。机器清单与 README 同步为 llama.py 39/49（79.59%）已测窄范围能力、10 项部分实现。完整 verify -Pnative 为 12,942 项通过、0 失败、0 跳过；包含工作区同步进行的缓存工作。全行为一致认证继续为 0，测试数据 Git 跟踪清单为空。

2026-10-29 缓存续作：核对 llama_cache.py 全部 18 项定义，新增 PythonLlamaCache 基类和 PythonLlamaDiskCache 的 6 项存储端口控制流程，Llama.setCache 接入共同基类。修正 RAM cache_size 的列表推导先于 sum 的异常优先顺序。新增 114 组固定原版缓存对照，状态对照增至 739 组。同步已有补充映射及仓库匹配复核后，全清单为 84 项绑定、98 项局部能力覆盖、59 项部分覆盖、510 项未迁移，未勾选 569 项；全输入一致性认证仍为 0 项。最终 clean verify -Pnative 为 12,942 项通过，0 失败、0 错误、0 跳过，格式和两个 JAR 打包通过，日志 .tools/cache-full-verify.log。历史段落计数保留其对应轮次，最新逐项状态见 README、docs/python-api-audit.json 和 docs/PYTHON_CACHE_COMPATIBILITY.md。

2026-10-29 机器映射同步：已将新增实现及 services/completion/configuration 对照证据纳入生成器，更新 README、python-api-audit.json 与全文件映射。llama.py 当前为 38 项已测窄范围能力、11 项部分实现、0 项缺少对应实现；全部 49 项的全行为一致验收继续进行。原版未充分覆盖的构造、资源释放/自动析构、completion 缓存与动态类型、配置重建、仓库 provider 和回调视图路径保留部分实现标记。全项目为 84 项绑定、95 项已测能力，合计 179/751；全行为认证保持 0。使用者核实的最近完整验证为 12,814 项通过，0 失败、0 跳过。本次仅同步元数据和文档，重生成幂等、49 项集合与 Git 忽略检查通过。

2026-10-29 本轮补充（实现状态待逐项复核后更新机器清单）：新增完整采样策略、custom logits 回调、embedding 批处理、completion 的 stop/echo/logprobs/流式状态机、chat/OpenAI 转发、配置快照/重建及 from_pretrained 离线仓库端口。新增 PythonNativeLlama 构造入口，按 Python 默认值直接设置 C 参数，并接入 LoRA 资源清理、Cleaner 和关闭后的 ctx/model 空值。删除关闭后重新安装清理动作的额外限制，保留已有 draft 空切片修复。

新增固定原版对照：services 235 组、completion 140 组、configuration/repository 74 组，共 449 组；分词器调用轨迹、错误前流式块及随机种子也已核对。补充真实模型采样、embedding、构造、配置重建和 completion 测试。构造流程的完整 Jinja2 语义、动态参数类型转换和初始化中途失败的状态仍需继续核实；当前机器清单继续保留原状态，避免将新增代码直接计为完整一致。

更新：2026-10-28。

用户确认采用现有单工程结构。`src/Java_Cpp` 为封装源码根目录，仅包含必要迁移项；demo 位于 `src/main/java/io/github/yaulun/demo`，测试位于 `src/test/java`。根目录保留唯一 POM，通过 build-helper 添加封装源码根目录。根 JAR 仅打包类库；独立 assembly 生成 demo JAR。

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
