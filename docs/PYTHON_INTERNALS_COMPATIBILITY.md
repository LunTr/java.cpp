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

## 第六轮：访问器、KV/state/encode 与批次追加（2026-11-01）

### 覆盖范围

53 项未覆盖函数全部迁移：

- `LlamaModel` 17 项：`vocab_type`、`rope_freq_scale_train`、`token_get_text/score/attr`、
  `token_cls`（原版特意转发 `llama_vocab_bos`）、`token_sep/nl`、FIM `prefix/middle/suffix`、
  `token_eot`、`add_bos_token`/`add_eos_token`、`get_tensor`（无条件 NotImplementedError
  原文）、`free_model`/`__del__` 闭包语义（经 `close`）。
- `LlamaContext` 31 项：`free_ctx`/`__del__`、`pooling_type`、
  `kv_cache_seq_rm`（负 `seq_id` 夹取为 0 + `memory is None` 断言原文）/`
  kv_cache_seq_cp`/`kv_cache_seq_keep`/`kv_cache_seq_shift`（实现为
  `llama_memory_seq_add`）、`get_state_size`、`encode`（非零码 RuntimeError 原文）、
  `set_n_threads`、`get_logits`/`get_logits_ith`/`get_embeddings`/`get_embeddings_ith`/
  `get_embeddings_seq` 指针透传、`reset_timings`/`print_timings`，以及 14 项
  deprecated `sample_*`/`set_rng_seed`/`grammar_accept_token`（统一
  NotImplementedError "<name> is deprecated, use LlamaSampler instead"）。
- `LlamaBatch` 4 项：`free_batch`/`__del__`、`n_tokens` 直读字段、`add_sequence`
  （pos 从 0 重起、写入新 seq_id、末位强制 logits=true）。
- 模块级 `normalize_embedding`：`np.linalg.norm` 等价实现，零范数原样返回。

### 新增 JNA 绑定（15）

`llama_vocab_type`、`llama_model_rope_freq_scale_train`、`llama_vocab_get_score`、
`llama_vocab_get_attr`、`llama_vocab_eot`、`llama_vocab_fim_pre`/`fim_suf`/`fim_mid`、
`llama_memory_seq_cp`/`seq_keep`/`seq_add`、`llama_encode`、`llama_set_n_threads`、
`llama_get_logits`、`llama_get_embeddings_ith`。`PythonInternalsPort` 对应新增同名
端口方法，`PythonNativeInternals` 为真实实现。

### 生成器修正

- 新增 `NumpyStub`（仅 `linalg.norm`）执行原版 `normalize_embedding` 函数体。
- **GC 非确定性修复**：`LlamaContext` 经 `model._exit_stack.callback(self.close)` 与
  `self.model` 形成循环引用，CPython 循环 GC 的回收时机曾把 `llama_free`/
  `llama_model_free` 漂移进后续用例 trace 中段。生成器现 `gc.disable()` 并在每个
  用例运行前显式 `gc.collect()`，把跨用例释放固定冲刷进旧桩 trace。
- `batchDump` 结果为 `FakeBatch.snapshot()` 原始值；`"batch"` 前缀只属于 trace 中
  `show()` 的编码，回放端已区分。

## 第七轮：TokenDataArray、SamplingContext、CustomSampler 与采样器补全（2026-11-01）

`_internals.py` 至此 **120 项全覆盖**。本轮迁移剩余 29 项：

- `LlamaTokenDataArray` 2 项：`__init__`/`copy_logits`。存储经
  `candidatesData`/`tokenDataArray`/`candidatesStore`/`candidatesAssign` 端口，
  `llama_token_data_array` 构造调用进 trace，字段写不可见；copy_logits 按原版
  重置 id=0..n-1、p=0、sorted=false、size=n_vocab。
- `LlamaSamplingContext` 6 项：dataclass 字段默认值（`LlamaSamplingParams` 全字段）、
  `reset` 重绑 prev/cur 并调 `grammar.reset()`、`cp` 共享 params/mirostat_mu/grammar
  但复制两个列表、`last`、`prev_str`（`prev[-n:]` 反 detokenize 再 UTF-8 解码）、
  `sample` 无条件 NIE 原文、`accept` 仅追加 prev。
- `CustomSampler` 4 项：sampler_i 六槽位按序赋值（apply 为具名 apply_wrapper，
  name/accept/reset/clone/free 均为 literal 0）、sampler.iface=pointer(sampler_i)、
  ctx=None、`get_sampler` 返回 `pointer(sampler)`。
- `LlamaSampler` 17 项：`free_sampler`/`__del__`、add_typical/temp_ext/xtc/
  top_n_sigma/mirostat/mirostat_v2/grammar/grammar_lazy_patterns/dry/logit_bias/
  infill/custom/accept/reset、clone 及 clone.free_sampler。要点：
  - `add_grammar`/`lazy`/`dry` 的 pattern/breaker 以 UTF-8 编码成 C 数组；
    `logit_bias` 保留 dict 插入序。
  - `add_custom` 先构造 `CustomSampler`（八个 llama_cpp 调用），chain_add 后
    记录 `chain_n - 1` 索引供 free 前移除。
  - `clone` 对含 custom samplers 的链抛 NIE 原文；克隆体注册的 free 无移除循环。

### 新增 JNA 绑定（8）

`llama_sampler_init_temp_ext`/`init_xtc`/`init_top_n_sigma`/
`init_grammar_lazy_patterns`/`init_dry`/`init_logit_bias`/`init_infill`/
`llama_sampler_clone`；另加 `LlamaSamplerApply` 回调接口与
`NativeSamplerI`（name/accept/apply/reset/clone/free 字段序）、
`NativeLlamaSampler`（iface/ctx）结构。可选槽位在 Python 是
`llama_sampler_i_*(0)` 包裹的字面 0，JNA 侧等价写 NULL。

### 生成器补充

- `NumpyStub` 扩展 `recarray`/`arange`/`zeros`/`dtype`/`intc`/`single`，
  `FakeRec` 支持字段切片赋值。
- `ctypes.pointer` 在用例内打桩为 `ptr:<name>` 句柄，用例结束还原。
- `llama_token_data_array` kwargs 构造经自定义 trace 记录；
  `llama_sampler_i_*` 回调工厂记录字段名与参数（apply 记 `apply_wrapper`）。

## 未覆盖 / 后续

- `_internals.py` 已全部勾选；`llama_cpp.py` 中对应 `llama_sampler_*`/`llama_memory_*`
  符号以 JNA 绑定计。
- `suppress_stdout_stderr` 在原生实现中为空操作；Java 不能压制 native stdout。
- CustomSampler 的真实 apply 回调经 `LlamaSamplerApply` 接入 JNA；
  Python 原版 `llama_sampler_i` 是无 `_fields_` 的空结构体，ABI 语义由 JNA
  结构正确承担，对照只认证调用顺序。
- 真实 b11669 行为（非桩）经 `PythonNativeInternals` 走通加载/分词/采样链，继续通过
  native verify 验收；桩对照只认证控制流与数据流。
