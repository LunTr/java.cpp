# llama_cache.py 缓存逐项记录

日期：2026-10-29。固定 Python 基线：llama-cpp-python 0.3.36，commit `1652066e0af45f2313b339670ef9555e8a54e545`。本轮在既有工作区变更上继续实现，保留已有配置文件匹配修正及映射更新。

## 覆盖数量

README 旧清单为 751 项中勾选 147 项、部分覆盖 48 项、缺少对应实现 556 项。同步此前 services/configuration/native 入口、仓库匹配补充及本轮缓存改动后，最新为勾选 **182** 项（绑定 84、局部能力 98）、部分 **59** 项、缺少 **510** 项，未勾选合计 **569** 项。全部函数的全输入一致性认证保持 0 项。

`llama_cpp/llama_cache.py` 含 18 项显式定义；`LlamaCache = LlamaRAMCache` 为别名，沿用原统计规则。逐项状态如下：

| Python 类 | 方法 | Java 对应 | 当前状态 |
|---|---|---|---|
| BaseLlamaCache | __init__ | PythonLlamaCache 构造 | 已测 BigInteger 容量及默认值 |
| BaseLlamaCache | _find_longest_prefix_key | findLongestPrefixKey | 原版 pass 返回 None，对应 null |
| BaseLlamaCache | cache_size | cacheSize | 已覆盖：基类方法体抛 NotImplementedError |
| BaseLlamaCache | __getitem__ | get | 已覆盖：基类方法体抛 NotImplementedError |
| BaseLlamaCache | __contains__ | contains | 已覆盖：基类方法体抛 NotImplementedError |
| BaseLlamaCache | __setitem__ | put | 已覆盖：基类方法体抛 NotImplementedError |
| LlamaRAMCache | __init__ | PythonLlamaRamCache 构造 | 既有局部能力覆盖 |
| LlamaRAMCache | cache_size | cacheSize | 修正属性收集与求和的异常优先顺序 |
| LlamaRAMCache | _find_longest_prefix_key | findLongestPrefixKey | 既有局部能力覆盖 |
| LlamaRAMCache | __getitem__ | get | 既有局部能力覆盖 |
| LlamaRAMCache | __contains__ | contains | 既有局部能力覆盖 |
| LlamaRAMCache | __setitem__ | put | 既有局部能力覆盖 |
| LlamaDiskCache | __init__ | PythonLlamaDiskCache 构造 | 已覆盖：Factory.open 存储端口 |
| LlamaDiskCache | cache_size | cacheSize | 已覆盖：Store.volume 存储端口 |
| LlamaDiskCache | _find_longest_prefix_key | findLongestPrefixKey | 已覆盖：Store.iterKeys 顺序 |
| LlamaDiskCache | __getitem__ | get | 已覆盖：命中后 Store.pop 删除 |
| LlamaDiskCache | __contains__ | contains | 已覆盖：前缀查询 |
| LlamaDiskCache | __setitem__ | put | 已覆盖：写入、日志、体积和淘汰控制流程 |

## 行为与验证范围

RAM 原版 `sum([state.llama_state_size for state in ...])` 先完成列表推导。新增反例先存入大小为 None 的状态，再存入 None 状态；读取 cache_size 时，后者的 AttributeError 优先于求和的 TypeError。Java 现已先读取全部字段，再逐项求和，错误后缓存内容和顺序保持原版规则。固定原版状态数据增至 739 组，其中 RAM 操作序列 134 组。

磁盘缓存按迭代顺序选择最长非空前缀，前缀相同时保留首个候选。get 命中后执行 pop 并删除条目。put 输出 called，转换键，检查精确键，重复键时输出 delete 并删除，写入后输出 set，随后循环读取物理体积、条目数及首键进行淘汰，最后输出 trim。stderr 的文本与换行分别调用监听器；存储操作失败时保留此前已写内容和输出片段。

Java Store 接口保留上述操作的独立调用，Factory 接收原版默认目录 `.cache/llama_cache`。PythonLlama 的缓存字段、setCache 和 cache 改为 PythonLlamaCache，completion 可通过同一抽象执行 get/put。已有 RAM 调用沿用此基类。公开 cache() 返回类型发生变化，使用旧类库编译的调用方需重新编译。

完整验证过程中还核对了工作区新增的仓库路径对照，将 PythonPretrained 的子路径错误消息改为通过 PythonValues.repr 格式化路径，保留反斜杠转义。

114 组新对照包含 110 组磁盘操作序列和 4 组基础类数据。生成器校验全部 25 个固定源码文件摘要，以 AST 执行原版完整函数体。磁盘对照使用显式可追踪存储替身：体积为 `10 + 各存储值大小之和`，保留插入顺序，并对 iterKeys/pop/contains/delete/put/volume/size/firstKey 注入异常。100 组固定随机序列各有 40 次操作。Java 验证按编码精确比较全部结果，无浮点容差。

后续验收包括实际 diskcache SQLite 文件体积、键排序、pickle 序列化/反序列化、跨进程行为和 Python 动态类型。Store 实现由调用方提供；尚未提供与 Python 磁盘文件格式互通的生产存储实现。

## 2026-10-30 补齐：ABC 实例化规则与基类方法体

`PythonLlamaCache` 改为具体类：4 个原版 abstractmethod 的方法体（`raise NotImplementedError`）保持可显式调用；构造器按 ABCMeta 规则检查子类是否仍继承基类声明，缺实现时按原版排序名单抛 TypeError，消息为 `Can't instantiate abstract class <名称> without an implementation for abstract method(s) '...'`（单数/复数形式随缺失数量）。直接实例化、单子集覆盖、全部覆盖和 4 个 super 调用共 8 组场景经固定原版对照；LlamaDiskCache 6 项同步由部分升为已覆盖（函数体移植与端口对照原有 114 组不变）。

## 重新生成

本轮最终 `clean verify -Pnative`：**12,942 项通过，0 失败、0 错误、0 跳过**。Google Java Format 检查及类库/demo 两个 JAR 打包通过。日志保留于被忽略的 `.tools/cache-full-verify.log`；`git ls-files src/test/resources` 输出为空，新增缓存数据命中根 `.gitignore` 第 52 行。

环境：Anaconda CPython 3.13.9，状态数据使用 NumPy 2.4.3。

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_cache_contracts.py --source-root .tools/python-api-audit
& E:/soft/Anaconda/python.exe tools/python_compat/generate_state_contracts.py --source-root .tools/python-api-audit
& E:/soft/Anaconda/python.exe tools/python_compat/update_inventory.py
& E:/soft/Anaconda/python.exe tools/python_compat/map_llama_functions.py --source-root .tools/python-api-audit
.\mvnw.cmd spotless:apply verify -Pnative "-Djavacpp.natives=E:\JavaKotlin\java.cpp\natives\b11669-cpu" "-Djavacpp.model=E:\JavaKotlin\java.cpp\gguf\Qwen3.5-0.8B-Q8_0.gguf"
```

数据保存于被忽略的 `src/test/resources/python-0.3.36-cache-contracts.tsv` 和 `python-0.3.36-state-contracts.tsv`。来源版本、生成环境、数量与摘要保存于 `python-cache-contracts.json`、`python-state-contracts.json`。Java 运行库独立运行。
