# `Llama.__init__` 初始化装配迁移

日期：2026-11-03。固定来源为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。生成器核验审计清单全部 25 个源码文件的 Git blob SHA-1 和 SHA-256，再用 AST 提取固定原版 `Llama.__init__` 完整函数体执行。

## 本轮 1 项

`Llama.__init__`（llama.py L60，约 300 行）是清单中最后一个未勾选项。Java 入口为 `compat.python.PythonLlamaInit.init(Instance, modelPath, kwargs, Deps)`，全部外部依赖经 `Deps` 端口注入；字段写入按原版顺序落入 `Instance.fields`，初始化失败时调用方仍能取得已写前缀的快照。

## 保留的行为

- verbose：`set_verbose` 先行，backend_init 与 numa_init 各自包一层 `suppress_stdout_stderr(disable=verbose)`；numa bool 转 `GGML_NUMA_STRATEGY_DISTRIBUTE(2)`/`DISABLED(0)`，非 bool 原样，`!= DISABLED` 才调 `llama_numa_init`。
- model_params：默认值对象逐字段写入并记录；`n_gpu_layers == -1` 转 `0x7FFFFFFF`；`tensor_split` 超 `LLAMA_MAX_DEVICES` 抛原版 `ValueError` 原文，否则构造 `c_float * 4` 数组并保留引用；`use_mmap` 与 `lora_path is None` 联合决定 `load_mode`（MMAP_MLOCK=3/MLOCK=2/MMAP=1/NONE=0）。
- kv_overrides：`llama_model_kv_override * (len+1)` 数组逐 key 写入 UTF-8 bytes；bool/int/float/str 对应 tag 0-3 与 `value.val_*` 嵌套字段；str 超 128 字节抛原版 `ValueError` 原文，`val_str` 经 `ctypes.memmove` 写入 128 字节；未知类型抛 `Unknown value type` 原文；末位 sentinel `key=b"\0"`。
- context_params：`n_batch=min(n_ctx,n_batch)`、`n_ubatch=min(n_batch,n_ubatch)`；`n_threads`/`n_threads_batch`/`seed` 按 Python `or` falsy 回退（cpu_count//2、cpu_count、LLAMA_DEFAULT_SEED）；rope/yarn 浮点字段非零才保留原值；`type_k`/`type_v`/`op_offload`/`swa_full` 仅非 None 写入。
- 资源装配：`os.path.exists` 检查后按原版顺序 `ExitStack.enter_context(closing(...))` 进入 `LlamaModel`/`LlamaContext`/`LlamaBatch`；`tokenizer or LlamaTokenizer(self)`；`n_ctx==0` 回读 `n_ctx_train()` 并重算三个批次字段；embedding 追加 `n_seq_max=min(n_batch, llama_max_parallel_sequences())` 与 `kv_unified=True`。
- LoRA：`lora_path` 非空时 `llama_adapter_lora_init` 返回 None 抛 `Failed to initialize LoRA adapter from lora path: ...`，成功后注册 `free_lora_adapter` 回调，`llama_set_adapters_lora` 非零抛 `Failed to set LoRA adapter from lora path: ...`。
- 词汇与聊天：`_n_vocab`/`_n_ctx`/`_token_nl`/`_token_eos` 走 `self._model`/`self._ctx` 属性链；verbose 打 `llama_print_system_info`；metadata 异常吞掉并按 verbose 打 `Failed to load metadata: {e}`；`tokenizer.chat_template.*` 收集 + `chat_template.default` 注入生成 Jinja2 handler 表；guess 命中写回 `chat_format`，否则按 default 模板/`llama-2` fallback 并打对应 stderr 行。
- 尾部状态：`_candidates`（LlamaTokenDataArray）、`n_tokens=0`、`_requires_eval=True`、intc/single 数组形状、`_mirostat_mu=c_float(10.0)`、`_sampler=None`、`_is_recurrent`/`_is_hybrid` 原生检测。

## 对照方法

`tools/python_compat/generate_init_contracts.py` 以桩化 `llama_cpp`/`internals`/`ctypes`/`os`/`llama_chat_format`/`contextlib`/`multiprocessing` 端口执行固定原版函数体，记录参数字段写入、调用轨迹、stderr 行与异常类别原文。桩包含：记录式 Params/Struct（含 `.value` 子结构自动建）、`ctypes type * n` 乘法建模（`ArrayMul`/`CFloatType`）、FakeModel/FakeContext/FakeBatch 属性链（`n_ctx_train`/`n_vocab`/`token_*`/`metadata`/`token_get_text`/`n_ctx`）、`llama_model_kv_override(_value)` 结构建模。

| 对照组 | 数量 |
|---|---:|
| 成功装配（含 numa/tensor_split/load_mode/lora/embedding/flash/type_kv/模板分支） | 15 |
| 失败分支（tensor_split 超设备、kvo 超长/未知类型、模型路径缺失、LoRA 双失败） | 6 |
| 合计 | 21 |

`PythonInitContractTest` 21/21 通过；字节级比较 wire 编码的 result/snapshot/trace/stderr。全输入一致性认证仍保持 0，桩化端口范围内的行为差异、真实原生加载与动态类型边界继续按逐项标准验收。

## 复现

```bash
E:\soft\Anaconda\python.exe tools\python_compat\generate_init_contracts.py --source-root .tools\python-api-audit
mvnw.cmd -q test -Dtest=PythonInitContractTest
```

生成环境：Anaconda CPython 3.13.9。fixture 写入被忽略的 `src/test/resources/python-0.3.36-init-contracts.tsv`，清单元数据为 `docs/python-init-contracts.json`。
