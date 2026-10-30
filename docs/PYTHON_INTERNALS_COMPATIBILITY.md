# _internals.py 迁移记录（29 项，2026-10-30）

固定原版：llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。
数据：`tools/python_compat/generate_internals_contracts.py` 用 AST 执行固定源码中
`LlamaModel`、`LlamaContext`、`LlamaBatch`、`LlamaSampler` 四个类的完整函数体，
`llama_cpp.*`、`os.path.exists`、`warnings.warn` 和 `suppress_stdout_stderr` 走可追踪的
确定性桩端口，产生 17 组对照（`src/test/resources/python-0.3.36-internals-contracts.tsv`，
被 `.gitignore` 忽略；元数据见 `docs/python-internals-contracts.json`）。Java 侧由
`PythonInternalsContractTest` 用相同的桩端口逐条回放，核对调用顺序、参数、返回值与
错误类别/文本完全一致。

## Java 入口

| Python 项 | Java 入口 |
|---|---|
| `LlamaModel.__init__` | `PythonLlamaModel` 构造器 |
| `LlamaModel.close` | `PythonLlamaModel.close` |
| `LlamaModel.desc` | `PythonLlamaModel.desc` |
| `LlamaModel.tokenize` | `PythonLlamaModel.tokenize` |
| `LlamaModel.token_to_piece` | `PythonLlamaModel.tokenToPiece` |
| `LlamaModel.detokenize` | `PythonLlamaModel.detokenize` |
| `LlamaModel.metadata` | `PythonLlamaModel.metadata` |
| `LlamaModel.default_params` | `PythonLlamaModel.defaultParams` |
| `LlamaContext.__init__` | `PythonLlamaContext` 构造器 |
| `LlamaContext.close` | `PythonLlamaContext.close` |
| `LlamaContext.kv_cache_clear` | `PythonLlamaContext.kvCacheClear` |
| `LlamaContext.decode` | `PythonLlamaContext.decode` |
| `LlamaContext.default_params` | `PythonLlamaContext.defaultParams` |
| `LlamaBatch.__init__` | `PythonLlamaBatch` 构造器 |
| `LlamaBatch.close` | `PythonLlamaBatch.close` |
| `LlamaBatch.reset` | `PythonLlamaBatch.reset` |
| `LlamaBatch.set_batch` | `PythonLlamaBatch.setBatch` |
| `LlamaSampler.__init__` | `PythonInternalsSampler` 构造器 |
| `LlamaSampler.close` | `PythonInternalsSampler.close` |
| `LlamaSampler.add_greedy` | `PythonInternalsSampler.addGreedy` |
| `LlamaSampler.add_dist` | `PythonInternalsSampler.addDist` |
| `LlamaSampler.add_softmax` | `PythonInternalsSampler.addSoftmax` |
| `LlamaSampler.add_top_k` | `PythonInternalsSampler.addTopK` |
| `LlamaSampler.add_top_p` | `PythonInternalsSampler.addTopP` |
| `LlamaSampler.add_min_p` | `PythonInternalsSampler.addMinP` |
| `LlamaSampler.add_temp` | `PythonInternalsSampler.addTemp` |
| `LlamaSampler.add_penalties` | `PythonInternalsSampler.addPenalties` |
| `LlamaSampler.get_seed` | `PythonInternalsSampler.getSeed` |
| `LlamaSampler.sample` | `PythonInternalsSampler.sample` |

`LlamaSampler` 因 `PythonLlamaSampler` 已被采样回调接口占用，命名为 `PythonInternalsSampler`。

## 设计

- **`PythonInternalsPort`** 是与 C 入口一一对应的可注入端口；句柄为不透明 `Object`，
  参数结构和批次字段不暴露 JNA 类型。`PythonNativeInternals`（`io.github.yaulun.javacpp`
  包内）经 `LlamaRuntime.get().library()` 落到 b11669 真实实现。
- **`PythonInternalsBatch`** 抽象批次字段写入：`nTokens`/`token`/`pos`/`seqId`/`nSeqId`/`logits`，
  负下标按 ctypes 数组语义落到已分配容量末尾。
- **`PythonExitStack`** 移植 `contextlib.ExitStack`：回调 LIFO、close 幂等；context 的
  `close` 注册进 model 的 ExitStack，保证 context 先于 model 释放；sampler 的
  `free_sampler` 先反向移除 custom samplers 再释放链。
- 采样器 float 参数（top_p/min_p/temp/penalties）以 `double` 传入端口，由原生实现转
  `float`，与 Python float → c_float 的转换时点一致；`add_dist` 的种子为无符号 32 位，
  `get_seed` 返回 `long`。
- `add_softmax` 只发 `DeprecationWarning`（经 `PythonWarnings`），不向链添加节点；
  `sample` 默认 `idx=-1`。
- 资源释放没有 `__del__` 等价物；泄漏防护由外层 `LlamaModel`/`LlamaContext`/`Cleaner`
  生命周期承担。

## 已对照的行为

- `LlamaModel.__init__`：路径存在性、加载失败、vocab 失败的三个 `ValueError` 原文；
  `verbose` 映射到 `suppress_stdout_stderr` 端口。
- `tokenize`：`n_ctx_train` 首缓冲、负数重试及 `RuntimeError` 中 bytes repr 原文。
- `token_to_piece` 固定返回 32 字节；`detokenize` 逐 token 拼接、`assert n<=size`、
  BOS 首空格裁剪。
- `metadata`：1024 字节共享缓冲按 key/value 需要扩容为 `nbytes+1`，返回保序可变 Map。
- `LlamaContext`：`memory=None` 的早退、`llama_decode` 非零码 `RuntimeError` 原文。
- `LlamaBatch`：`set_batch` 逐元素写入 seq 0/连续 pos/logits，末位强制 `logits=true`，
  越界写按 Python 列表语义报 `IndexError`。
- `LlamaSampler`：空链 + 逐项 `add_*` 调用顺序、seed/sample 透传、关闭时反向移除
  custom samplers。

## 新增 JNA 绑定

`internal.LlamaLibrary`：`llama_batch_init`、`llama_batch_free`、`llama_decode`、
`llama_sampler_chain_n`、`llama_sampler_chain_remove`、`llama_sampler_get_seed`；
`internal.LlamaBatch` 为 llama_batch 的按值结构（b11669 x64 共 56 字节）。

## 未覆盖 / 后续

- 未迁移项保持不变：`token_get_*`、特殊 token getter、`get_tensor`、`kv_cache_seq_*`、
  `encode`、`set_n_threads`、`get_logits*`/`get_embeddings*`、已弃用 sample_* 系列、
  `LlamaBatch.add_sequence`、`LlamaTokenDataArray`、`normalize_embedding`、
  `LlamaSamplingContext`、`CustomSampler`、`LlamaSampler` 其余 `add_*`/`accept`/`clone`
  及全部嵌套 `free_*`/`__del__`。
- `suppress_stdout_stderr` 在原生实现中为空操作；Java 不能压制 native stdout。
- 真实 b11669 行为（非桩）经 `PythonNativeInternals` 走通加载/分词/采样链，继续通过
  native verify 验收；桩对照只认证控制流与数据流。
