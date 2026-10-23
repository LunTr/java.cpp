# RAM 缓存、状态与处理器对照记录

日期：2026-10-23。固定基线为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。来源为审计清单中通过 Git blob SHA-1 和 SHA-256 校验的原版文件。

## 本批 12 项函数

| Python 定义项 | Java 对应 |
|---|---|
| `LlamaRAMCache.__init__` | `PythonLlamaRamCache` 构造器 |
| `LlamaRAMCache.cache_size` | `cacheSize` |
| `LlamaRAMCache._find_longest_prefix_key` | `findLongestPrefixKey` |
| `LlamaRAMCache.__getitem__` | `get` |
| `LlamaRAMCache.__contains__` | `contains` |
| `LlamaRAMCache.__setitem__` | `put` |
| `Llama.longest_token_prefix` | `PythonTokens.longestTokenPrefix` |
| `LlamaState.__init__` | `PythonLlamaState` 构造器 |
| `LogitsProcessorList.__call__` | `PythonLogitsProcessorList.apply` |
| `StoppingCriteriaList.__call__` | `PythonStoppingCriteriaList.test` |
| `MinTokensLogitsProcessor.__init__` | `PythonMinTokensLogitsProcessor` 构造器 |
| `MinTokensLogitsProcessor.__call__` | `PythonMinTokensLogitsProcessor.apply` |

RAM 缓存按最长非空前缀查找；同长度前缀选择当前顺序最靠前的键。`get` 将命中项移到末尾，`contains` 保持顺序。`put` 先替换同键项，再从最早项开始淘汰。空键即使已存入也不会形成非空前缀命中。默认容量为 2 GiB；容量、状态大小和 token 键使用 BigInteger，容量和大小的零值、负值均保留原版规则。

`cacheSize` 每次读取状态对象的当前大小，能观察外部修改；查询和命中操作保持原版的延迟淘汰时机。无命中时提供原版 KeyError 类别和带引号的消息。缓存持有状态引用，状态构造保留传入的数组及字节存储引用。

logits 列表依次执行回调，将每次返回的 scores 引用传给下一项。列表回调可以追加或移除处理器，迭代位置按原版列表循环处理。停止条件列表先执行完整列表推导，再对已收集结果执行 `any`，因此后续回调的副作用和异常会发生，后续回调对早期可变返回值的修改也会影响最终判断。

最小 token 处理器首次调用记录 prompt 长度，在长度差小于阈值时将 EOS 的分数原地设为负无穷。它返回原 scores 引用，支持 NumPy 式负索引，并对齐已测越界索引的类别和消息。

## 原版对照

Anaconda CPython 3.13.9 / NumPy 2.4.3 执行固定原版完整函数体。缓存使用原版 BaseLlamaCache 和 LlamaRAMCache；数组按原版 intc/single 类型建立。生成器不导入原生模型库，未用 Java 实现生成预期结果。

| 对照类型 | 组数 |
|---|---:|
| 最长 token 前缀 | 244 |
| RAM 缓存操作序列 | 133 |
| 最小 token 处理器调用序列 | 196 |
| logits 列表回调序列 | 105 |
| 停止条件回调序列 | 12 |
| 状态构造与引用 | 48 |
| 合计 | 738 |

缓存随机序列每组包含 60 次操作，混合写入、读取、包含判断、前缀查询、容量修改和外部状态大小修改。包括超过 64 位的数值、空键、空状态、缺少状态大小、负容量、重复覆盖及错误后的缓存内容。

每组比较结果、错误类别和消息、当前键顺序、大小、prompt 长度、数组结果、对象身份标记或回调轨迹。另有 Java 引用及默认容量测试。本批共 739 项通过；完整 `verify -Pnative` 共 **11,452 项通过，0 失败、0 错误、0 跳过**，包含原有 11 项真实模型测试。类库和 demo 已重新打包，Google Java Format 检查通过。

来源、环境、计数及数据摘要见 [python-state-contracts.json](python-state-contracts.json)。数据在 `src/test/resources/python-0.3.36-state-contracts.tsv` 本地生成，并由 `.gitignore` 忽略。全部数据的重建入口和 Git 检查步骤见 [生成说明](../tools/python_compat/README.md)。

## 总体进度

751 项定义中，55 项同名绑定、63 项独立能力已有实现，合计 118 项勾选；60 项部分覆盖、573 项尚无对应实现，633 项尚未勾选。全部条目的全输入一致性认证仍为 0 项，已测场景与输入类型范围保存在逐项清单及本记录中。

这些入口是独立的 Python 兼容组件，尚需接入完整生成、保存/恢复状态及缓存复用流程。Python 自定义对象/协议、任意 NumPy dtype/shape、Java int/long 以外的模型属性及完整 Python traceback 继续按全输入标准验收；当前处理器采用原版标注的 intc IDs 和一维 single scores，状态 scores 使用二维 single 对应数组。
