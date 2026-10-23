# Python 原版对照数据生成

所有测试数据放在 `src/test/resources/`，该目录由 `.gitignore` 忽略。测试输入、预期结果和 Base64 内容只保留本地；生成脚本、来源摘要及样例数量可以提交。维护规则见根目录 `AGENTS.md`。

固定原版为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。`--source-root` 指向包含原版 `llama_cpp/` 的目录；25 个文件均须通过 `docs/python-api-audit.json` 中的 SHA-256 / Git blob SHA-1 核验。本机已验证的源码目录为 `.tools/python-api-audit`。

生成环境：Anaconda CPython 3.13.9，NumPy 2.4.3。以下命令重建全部本地数据，随后按 Google Java Format 整理生成的模板常量和 Unicode 表：

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_all_contracts.py --source-root .tools/python-api-audit
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
```

`generate_bridge_contracts.py` 生成错误/警告数据；`generate_chat_contracts.py` 生成静态聊天和 CPython repr 数据；`generate_state_contracts.py` 生成缓存、状态和处理器数据。三组数据分别写入 `python-0.3.36-contracts.tsv`、`python-0.3.36-chat-contracts.tsv`、`python-0.3.36-state-contracts.tsv`。生成器执行原版函数体并保留来源核验，不使用 Java 输出制作预期结果。

`contract_wire.py` 定义聊天及状态数据共用的类型编码。各 `docs/python-*-contracts.json` 只保存来源、环境、计数和摘要。缓存和回调测试在一条数据中记录完整操作序列，便于核对顺序及发生错误后的状态。

更新函数映射后执行 `update_inventory.py` 同步 README 和机器清单。提交前执行 `git ls-files src/test/resources`，输出应为空；通过 `git check-ignore -v <测试数据路径>` 检查新增数据是否被忽略。

Python 仅用于维护阶段生成对照数据。Java 类库、打包产物及这些生成数据的 JUnit 验证均独立运行。
