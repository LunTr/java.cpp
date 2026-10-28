# Java.cpp 实施状态

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
