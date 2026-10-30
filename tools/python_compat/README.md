# Python 原版对照数据生成

2026-10-30 ctypes 补充：`generate_ctypes_contracts.py` 执行固定原版 `_ctypes_extensions.py` 全部 5 个定义项，在显式 os/sys/ctypes/pathlib 端口替身上生成 66 组对照，覆盖全部平台分支、环境变量写入、依赖加载、functools.wraps 属性复制与参数绑定报错原文；`byref` 对照使用真实 `ctypes.byref` 并核对实际指针偏移。Java 入口为 `PythonCtypesExtensions`，真实 OS 加载效果由 Host 端口承担。详见 [ctypes 迁移记录](../../docs/PYTHON_CTYPES_COMPATIBILITY.md)。

2026-10-29 缓存补充：`generate_cache_contracts.py` 执行固定原版 BaseLlamaCache/LlamaDiskCache 完整函数体，生成 114 组数据，其中 110 组使用显式磁盘存储替身核对控制流程、异常时序和 stderr 分片，4 组核对基础类容量与空前缀。实际 SQLite/pickle 行为继续验收。`generate_all_contracts.py` 已接入；数据位于被忽略的 `src/test/resources/python-0.3.36-cache-contracts.tsv`，元数据见 `docs/python-cache-contracts.json`。状态数据新增属性收集/求和异常优先顺序反例，总数为 739 组。详见 [缓存记录](../../docs/PYTHON_CACHE_COMPATIBILITY.md)。

2026-10-29 新增：generate_llama_defaults.py 从固定源码生成 Java 默认参数表；generate_services_contracts.py、generate_completion_contracts.py、generate_configuration_contracts.py 分别生成 235、140、74 组本地数据。generate_all_contracts.py 已接入四个脚本。数据仍放在被忽略的 src/test/resources，元数据为 docs/python-services-contracts.json、python-completion-contracts.json、python-configuration-contracts.json。

所有测试数据放在 `src/test/resources/`，该目录由 `.gitignore` 忽略。测试输入、预期结果和 Base64 内容只保留本地；生成脚本、来源摘要及样例数量可以提交。维护规则见根目录 `AGENTS.md`。

固定原版为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。`--source-root` 指向包含原版 `llama_cpp/` 的目录；25 个文件均须通过 `docs/python-api-audit.json` 中的 SHA-256 / Git blob SHA-1 核验。本机已验证的源码目录为 `.tools/python-api-audit`。

生成环境：Anaconda CPython 3.13.9，NumPy 2.4.3。以下命令重建全部本地数据，随后按 Google Java Format 整理生成的模板常量和 Unicode 表：

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_all_contracts.py --source-root .tools/python-api-audit
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
```

`generate_bridge_contracts.py` 生成错误/警告数据；`generate_chat_contracts.py` 生成静态聊天和 CPython repr 数据；`generate_state_contracts.py` 生成缓存、状态和处理器数据；`generate_llama_contracts.py` 生成首轮 8 项 Llama 状态访问、重置和 log-softmax 数据；`generate_tokenizer_contracts.py` 生成第二轮 8 项分词链路数据；`generate_execution_contracts.py` 生成求值、状态、采样和生成器执行数据。六组数据分别写入 `python-0.3.36-contracts.tsv`、`python-0.3.36-chat-contracts.tsv`、`python-0.3.36-state-contracts.tsv`、`python-0.3.36-llama-contracts.tsv`、`python-0.3.36-tokenizer-contracts.tsv`、`python-0.3.36-execution-contracts.tsv`。生成器执行原版函数体并保留来源核验，预期结果来源为 Python 原版。

单独恢复首轮状态数据：

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_llama_contracts.py --source-root .tools/python-api-audit
.\mvnw.cmd "-Dtest=PythonLlamaContractTest" test
```

本轮生成器通过 AST 保留 8 项原版完整函数体，用显式测试替身记录内存查询/清理顺序。log-softmax 输入采用 NumPy single，比较绝对和相对 `4e-6` 容差，生成时屏蔽数值 RuntimeWarning。测试替身及警告范围见 [迁移记录](../../docs/PYTHON_LLAMA_COMPATIBILITY.md)。

`contract_wire.py` 定义聊天及状态数据共用的类型编码。各 `docs/python-*-contracts.json` 只保存来源、环境、计数和摘要。缓存和回调测试在一条数据中记录完整操作序列，便于核对顺序及发生错误后的状态。

更新函数映射后执行 `update_inventory.py` 同步 README 和机器清单。提交前执行 `git ls-files src/test/resources`，输出应为空；通过 `git check-ignore -v <测试数据路径>` 检查新增数据是否被忽略。

Python 仅用于维护阶段生成对照数据。Java 类库、打包产物及这些生成数据的 JUnit 验证均独立运行。

单独恢复第二轮分词数据：

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_tokenizer_contracts.py --source-root .tools/python-api-audit
.\mvnw.cmd "-Dtest=PythonTokenizerContractTest" test
```

324 组对照与显式 codec 测试替身说明见 [分词迁移记录](../../docs/PYTHON_TOKENIZER_COMPATIBILITY.md)。原生集成测试复用其中由 Python 产生的 UTF-8 字节作为往返输入。

恢复第三轮执行数据并更新全文件映射：

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_execution_contracts.py --source-root .tools/python-api-audit
& E:/soft/Anaconda/python.exe tools/python_compat/update_inventory.py
& E:/soft/Anaconda/python.exe tools/python_compat/map_llama_functions.py --source-root .tools/python-api-audit
.\mvnw.cmd "-Dtest=PythonExecutionContractTest" test
```

206 组对照比较执行结果、异常消息、错误后的状态和回调时序。map_llama_functions.py 校验 llama.py 的全部 49 个定义项、4 个嵌套函数与 23 处显式异常/警告站点，生成文档和 JSON；其中实现状态来自审计清单。全文件映射在更新 inventory 后生成，详见 [执行链路记录](../../docs/PYTHON_EXECUTION_COMPATIBILITY.md)。
