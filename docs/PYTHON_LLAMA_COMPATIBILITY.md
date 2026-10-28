# llama.py 状态访问与数值辅助方法迁移

日期：2026-10-28。固定来源为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。生成器核验审计清单全部 25 个源码文件的 Git blob SHA-1 和 SHA-256，再执行本轮 8 项原版完整函数体。

## 本轮 8 项

| Python 定义 | Java `compat.python.PythonLlama` |
|---|---|
| `Llama._input_ids` | `inputIds()`，可写 IntBuffer 前缀视图 |
| `Llama._scores` | `scores()`，可写 FloatBuffer 行视图列表 |
| `Llama.eval_tokens` | `evalTokens()`，独立 token List 快照 |
| `Llama.eval_logits` | `evalLogits()`，独立二维 List 快照 |
| `Llama.set_cache` | `setCache(PythonLlamaRamCache)` |
| `Llama.set_seed` | `setSeed(BigInteger)` |
| `Llama.reset` | `reset()` |
| `Llama.logits_to_logprobs` | `logitsToLogprobs(float[])` / `logitsToLogprobs(float[][], int)` |

此类是供后续 eval/generate 连接的状态组件。构造器附加调用方已求值的 intc/single 数组并保留引用；调用方负责资源生命周期和同步。初始 `requiresEval=false`，种子为零，缓存为空。完整 Python `Llama.__init__` 和原生模型加载继续使用各自迁移项跟踪。

```java
PythonLlama state = new PythonLlama(
    inputIds, scores, evaluatedTokens, contextLength, logitsAll,
    recurrent, hybrid, () -> context::clear);
state.setSeed(seed);
state.setCache(cache);
IntBuffer tokens = state.inputIds();
List<FloatBuffer> logits = state.scores();
state.reset();
// After caller-managed evaluation succeeds:
state.setEvaluatedTokens(evaluatedTokens);
float[] logprobs = PythonLlama.logitsToLogprobs(lastLogits);
```

内存适配回调每次 reset 重新查询，仅在 recurrent/hybrid 分支调用。存在内存时，返回的 Runnable 应按原版 `data=true` 语义清理；内存缺省时返回 null。示例中的 `context::clear` 使用现有 Java 上下文清理 API，并同时清理其 logits/失败状态标记。测试采用显式内存替身检查原版分支和回调时序。

## 保留的行为

输入和 scores 视图按 `[:n_tokens]` 截断，覆盖负游标、超长游标和空结果。通过 buffer 写入可观察到底层数组变化，底层元素写入也能通过已取得的视图读取。scores 按二维 single 行表示；每行对应 FloatBuffer，列表结构固定。

`evalTokens` 保留末尾至多 n_ctx 项；`evalLogits` 在 logits_all 开启时保留末尾至多 n_ctx 行，关闭时保留末行。返回快照与底层数组独立。负容量沿用已测 ValueError 消息；零容量返回空快照。Java 返回 List，Python deque 的 append、maxlen 和后续变更接口列入后续范围。

`setCache` 保存 RAM 缓存引用或 null；`setSeed` 保存 BigInteger 整数。这两个操作保留当前游标及待求值标记。采样器由调用方管理，生成循环连接后可读取当前种子。

`reset` 先将游标置零并设置 requires_eval，再处理模型类型分支。普通模型保留内存；recurrent/hybrid 模型查询并清理可用内存。查询或清理抛出异常后，已更新的状态继续保留。缓冲数据、种子与缓存引用均保持原值。

log-softmax 使用 single 精度的减最大值、指数、求和、对数及相减步骤，覆盖原版对非有限最大值的处理。支持一维向量和外层非空的矩形二维数组，矩阵轴为 0/1/-1/-2。返回新数组，保留输入。Java Math 和 NumPy 的浮点实现按绝对 `4e-6` 加相对 `4e-6` 容差验证；NaN 和无穷结果逐项核对。更高维数组、其他 dtype、Python List 的隐式 dtype 推断及 NumPy 数值警告继续跟踪。

## 对照与复现

生成环境：Anaconda CPython 3.13.9、NumPy 2.4.3。AST 提取保留本轮完整函数体和属性/静态方法装饰器，构造测试对象时显式填入状态；原生内存查询和清理由带轨迹的替身提供。数值生成屏蔽 RuntimeWarning，预期结果来自 Python 执行。

| 对照组 | 数量 |
|---|---:|
| 视图、共享修改及快照 | 150 |
| 缓存与任意精度种子赋值 | 12 |
| reset 分支、重复调用及异常状态 | 48 |
| 向量 log-softmax | 92 |
| 矩阵 log-softmax | 72 |
| 合计 | 374 |

另有 1 项 Java 空缓冲、求值状态更新、输入保留和接口边界测试。固定来源、运行环境、数量及数据摘要见 [python-llama-contracts.json](python-llama-contracts.json)。

本轮完整 `verify -Pnative`：**11,827 项通过，0 失败、0 错误、0 跳过**，包含 11 项既有真实模型测试。本轮 375 项新增测试全部通过；Google Java Format、公开 API 边界检查通过，类库 JAR 和 demo JAR 已重新打包。新组件的内存适配验证使用替身，真实模型测试验证现有封装的回归行为。

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_llama_contracts.py --source-root .tools/python-api-audit
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify -Pnative "-Djavacpp.natives=E:\JavaKotlin\java.cpp\natives\b11669-cpu" "-Djavacpp.model=E:\JavaKotlin\java.cpp\gguf\Qwen3.5-0.8B-Q8_0.gguf"
```

数据写入被忽略的 `src/test/resources/python-0.3.36-llama-contracts.tsv`。全部四组数据重建步骤见 [生成说明](../tools/python_compat/README.md)。

本轮后清单为 55 项绑定、71 项独立能力实现，共 126 / 751（16.78%）勾选；58 项部分覆盖、567 项待迁移。全输入一致性认证维持逐项待验收。
