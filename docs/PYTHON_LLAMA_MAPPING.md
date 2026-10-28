# llama.py 全文件函数与异常映射

固定来源：llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。

完整映射 49 个定义项，包含 4 个嵌套函数、23 处显式异常/断言/警告站点。映射生成器校验源码摘要，并要求定义集合与全量审计清单完全相等。

每项同时记录 Java 去向和实际实现状态。计划中的目标继续保留待实现状态，回调及 NumPy/ctypes 的隐式错误由对应测试和后续对照补充。

| Python 定义 | Java 去向 | 实现状态 |
|---|---|---|
| `Llama.__init__` | PythonErrorBridge.loadModel/createContext + ModelParams/ContextParams（参数全集待连接） | 部分实现 |
| `Llama.__init__.free_lora_adapter` | 计划：LoRA AutoCloseable 资源回调 | 待实现 |
| `Llama.ctx` | PythonLlama.ctx（Java 上下文对象） | 部分实现 |
| `Llama.model` | PythonLlama.model（Java 模型对象） | 部分实现 |
| `Llama._input_ids` | compat.python.PythonLlama.inputIds | 已有窄范围实现 |
| `Llama._scores` | compat.python.PythonLlama.scores | 已有窄范围实现 |
| `Llama.eval_tokens` | compat.python.PythonLlama.evalTokens | 已有窄范围实现 |
| `Llama.eval_logits` | compat.python.PythonLlama.evalLogits | 已有窄范围实现 |
| `Llama.tokenize` | compat.python.PythonLlama.tokenize | 已有窄范围实现 |
| `Llama.detokenize` | compat.python.PythonLlama.detokenize | 已有窄范围实现 |
| `Llama.set_cache` | compat.python.PythonLlama.setCache | 已有窄范围实现 |
| `Llama.set_seed` | compat.python.PythonLlama.setSeed | 已有窄范围实现 |
| `Llama.reset` | compat.python.PythonLlama.reset | 已有窄范围实现 |
| `Llama.eval` | PythonLlama.eval / PythonLlamaExecution | 已有窄范围实现 |
| `Llama._init_sampler` | 计划：完整 Python 采样链构造策略 | 待实现 |
| `Llama._init_sampler.apply_func` | 计划：采样候选数组与 logits processor 的共享内存适配 | 待实现 |
| `Llama.sample` | PythonLlama.sample（采样器工厂注入，完整参数链待连接） | 部分实现 |
| `Llama.generate` | PythonLlama.generate / TokenGenerator.send（状态机已接入，采样策略参数待连接） | 部分实现 |
| `Llama.create_embedding` | 计划：embedding 结果对象与 usage 组装 | 待实现 |
| `Llama.embed` | 计划：多序列 embedding 批处理与归一化 | 待实现 |
| `Llama.embed.decode_batch` | 计划：embedding 解码、池化与输出拷贝 | 待实现 |
| `Llama._create_completion` | 计划：completion 生成、stop/echo/logprobs、缓存和流式 UTF-8 | 待实现 |
| `Llama._create_completion.logit_bias_processor` | 计划：logit bias 拷贝、索引与列表扩展语义 | 待实现 |
| `Llama.create_completion` | 现有 LlamaContext.generate；计划：完整 completion 参数转发和响应 | 部分实现 |
| `Llama.__call__` | 计划：createCompletion 参数别名 | 部分实现 |
| `Llama.create_chat_completion` | 现有 ChatSession / PythonChatFormats；计划：chat handler 优先级和参数转发 | 部分实现 |
| `Llama.create_chat_completion_openai_v1` | 计划：Java OpenAI 类型 provider，依赖与转换异常策略 | 待实现 |
| `Llama.__getstate__` | 计划：Python 构造配置序列化（区别于推理快照） | 待实现 |
| `Llama.__setstate__` | 计划：通过完整构造器重建配置 | 待实现 |
| `Llama.save_state` | PythonLlama.saveState / PythonLlamaExecution.copyState | 已有窄范围实现 |
| `Llama.load_state` | PythonLlama.loadState / PythonLlamaExecution.restoreState | 已有窄范围实现 |
| `Llama.n_ctx` | PythonLlama.nCtx | 已有窄范围实现 |
| `Llama.n_embd` | PythonLlama.nEmbd | 已有窄范围实现 |
| `Llama.n_vocab` | PythonLlama.nVocab | 已有窄范围实现 |
| `Llama.tokenizer` | compat.python.PythonLlama.tokenizer | 已有窄范围实现 |
| `Llama.token_eos` | PythonLlama.tokenEos | 已有窄范围实现 |
| `Llama.token_bos` | PythonLlama.tokenBos | 已有窄范围实现 |
| `Llama.token_nl` | PythonLlama.tokenNl | 已有窄范围实现 |
| `Llama.pooling_type` | PythonLlama.poolingType（按实际返回 int） | 已有窄范围实现 |
| `Llama.close` | PythonLlama.close / setCloseAction | 已有窄范围实现 |
| `Llama.__del__` | AutoCloseable.close；Java 自动清理策略待实现 | 待实现 |
| `Llama.logits_to_logprobs` | compat.python.PythonLlama.logitsToLogprobs | 已有窄范围实现 |
| `Llama.longest_token_prefix` | compat.python.PythonTokens.longestTokenPrefix | 已有窄范围实现 |
| `Llama.from_pretrained` | 现有 ModelDownloader；计划：repo 校验、文件匹配与 Hugging Face provider | 部分实现 |
| `LlamaState.__init__` | compat.python.PythonLlamaState | 已有窄范围实现 |
| `LogitsProcessorList.__call__` | compat.python.PythonLogitsProcessorList.apply | 已有窄范围实现 |
| `StoppingCriteriaList.__call__` | compat.python.PythonStoppingCriteriaList.test | 已有窄范围实现 |
| `MinTokensLogitsProcessor.__init__` | compat.python.PythonMinTokensLogitsProcessor | 已有窄范围实现 |
| `MinTokensLogitsProcessor.__call__` | compat.python.PythonMinTokensLogitsProcessor.apply | 已有窄范围实现 |

## 调用顺序与显式报错

### Llama.__init__

[固定源码第 60 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L60)。按源码顺序处理设备数、kv_overrides、路径、原生资源和 LoRA；参数默认值与 Java 默认值逐项跟踪。

- L237 / raise：`ValueError(f'Attempt to split tensors that exceed maximum supported devices. Current LLAMA_MAX_DEVICES={llama_cpp.LLAMA_MAX_DEVICES}')`；触发分支：self.tensor_split is not None → len(self.tensor_split) > llama_cpp.LLAMA_MAX_DEVICES；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L286 / raise：`ValueError(f'Value for {k} is too long: {v}')`；触发分支：kv_overrides is not None → not (isinstance(v, bool)) → not (isinstance(v, int)) → not (isinstance(v, float)) → isinstance(v, str) → len(v_bytes) > 128；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L304 / raise：`ValueError(f'Unknown value type for {k}: {v}')`；触发分支：kv_overrides is not None → not (isinstance(v, bool)) → not (isinstance(v, int)) → not (isinstance(v, float)) → not (isinstance(v, str))；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L384 / raise：`ValueError(f'Model path does not exist: {model_path}')`；触发分支：not os.path.exists(model_path)；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L442 / raise：`RuntimeError(f'Failed to initialize LoRA adapter from lora path: {self.lora_path}')`；触发分支：self.lora_path → self._lora_adapter is None；Java：PythonException / PythonErrorType.RUNTIME_ERROR（bridge-or-planned; see implementationStatus）
- L457 / raise：`RuntimeError(f'Failed to set LoRA adapter from lora path: {self.lora_path}')`；触发分支：self.lora_path → llama_cpp.llama_set_adapters_lora(self._ctx.ctx, adapters, 1, scales)；Java：PythonException / PythonErrorType.RUNTIME_ERROR（bridge-or-planned; see implementationStatus）

### Llama.__init__.free_lora_adapter

[固定源码第 446 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L446)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.ctx

[固定源码第 576 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L576)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.model

[固定源码第 580 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L580)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama._input_ids

[固定源码第 584 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L584)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama._scores

[固定源码第 588 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L588)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.eval_tokens

[固定源码第 592 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L592)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.eval_logits

[固定源码第 596 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L596)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.tokenize

[固定源码第 602 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L602)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.detokenize

[固定源码第 620 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L620)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.set_cache

[固定源码第 640 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L640)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.set_seed

[固定源码第 648 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L648)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.reset

[固定源码第 656 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L656)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.eval

[固定源码第 666 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L666)。先移除后缀，再逐批 decode、写 input_ids、写扁平 scores，最后推进 n_tokens；空输入仍调用后缀移除。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama._init_sampler

[固定源码第 704 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L704)。custom、penalties、grammar、温度和 mirostat 分支按顺序；tfs_z/penalize_nl 在该函数体中未参与原生构造。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama._init_sampler.apply_func

[固定源码第 726 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L726)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.sample

[固定源码第 792 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L792)。先检查 n_tokens；临时采样器构造先于 ctx 断言；idx 转相对位置；成功后清除临时引用，异常保留。

- L822 / assert：`self.n_tokens > 0`；Java：PythonException / PythonErrorType.ASSERTION_ERROR（executed-contract）
- L848 / assert：`self.ctx is not None`；Java：PythonException / PythonErrorType.ASSERTION_ERROR（executed-contract）

### Llama.generate

[固定源码第 854 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L854)。惰性启动；缓存前缀/恢复后重放；yield 前停止检查；send 后追加 token；draft 验证及异常导致生成器结束。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.create_embedding

[固定源码第 1027 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1027)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.embed

[固定源码第 1067 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1067)。先查询维度/池化，再检查 embeddings；按容量和序列数批处理；仅在成功路径清空 KV 并 reset。

- L1097 / raise：`RuntimeError('Llama model must be created with embedding=True to call this method')`；触发分支：self.context_params.embeddings is False；Java：PythonException / PythonErrorType.RUNTIME_ERROR（bridge-or-planned; see implementationStatus）
- L1160 / raise：`ValueError(f'Requested tokens ({n_tokens}) exceed batch size of {n_batch}')`；触发分支：n_tokens > n_batch；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）

### Llama.embed.decode_batch

[固定源码第 1115 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1115)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama._create_completion

[固定源码第 1195 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1195)。校验 suffix、准备 prompt、重复 BOS 警告、bias、上下文上限、stop 编码、logprobs 限制、缓存/生成；异常发生在首次迭代时。

- L1225 / assert：`suffix is None or suffix.__class__ is str`；Java：PythonException / PythonErrorType.ASSERTION_ERROR（bridge-or-planned; see implementationStatus）
- L1314 / warning：`warnings.warn(f'Detected duplicate leading "{self._model.token_get_text(self.token_bos())}" in prompt, this will likely reduce response quality, consider removing it...', RuntimeWarning)`；触发分支：prompt_tokens[:2] == [self.token_bos()] * 2；Java：PythonException / PythonErrorType.RUNTIME_WARNING（bridge-or-planned; see implementationStatus）
- L1345 / raise：`ValueError(f'Requested tokens ({len(prompt_tokens)}) exceed context window of {llama_cpp.llama_n_ctx(self.ctx)}')`；触发分支：len(prompt_tokens) >= self._n_ctx；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L1366 / raise：`ValueError('logprobs is not supported for models created with logits_all=False')`；触发分支：logprobs is not None and self._logits_all is False；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）

### Llama._create_completion.logit_bias_processor

[固定源码第 1324 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1324)。复制 scores 再逐键赋值；索引错误和 extend 返回 None 的源码行为均需保留证据。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.create_completion

[固定源码第 1815 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1815)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.__call__

[固定源码第 1912 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L1912)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.create_chat_completion

[固定源码第 2004 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2004)。优先显式 chat_handler，再查模型内 handler，最后全局 registry；handler 错误原样传播。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.create_chat_completion_openai_v1

[固定源码第 2107 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2107)。import 在 stream bool 检查之前；流式对象转换惰性执行；捕获范围包含同步 create_chat_completion 的 ImportError。

- L2129 / assert：`isinstance(stream, bool)`；Java：PythonException / PythonErrorType.ASSERTION_ERROR（bridge-or-planned; see implementationStatus）
- L2138 / raise：`ImportError('To use create_chat_completion_openai_v1, you must install the openai package.You can install it with 'pip install openai'.')`；触发分支：except ImportError；Java：计划：ImportError 的依赖/provider 异常桥接（bridge-or-planned; see implementationStatus）

### Llama.__getstate__

[固定源码第 2143 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2143)。返回构造配置，包含当前 seed；保留可调用对象引用；不含推理 KV 字节。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.__setstate__

[固定源码第 2211 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2211)。直接调用 __init__(**state)，重跑校验和资源初始化。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.save_state

[固定源码第 2214 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2214)。按原版顺序输出诊断；实际拷贝数可小于分配量；大于分配量抛错；仅复制有效 scores，复制全部 input_ids。

- L2227 / raise：`RuntimeError('Failed to copy llama state data')`；触发分支：int(n_bytes) > int(state_size)；Java：PythonException / PythonErrorType.RUNTIME_ERROR（executed-contract）

### Llama.load_state

[固定源码第 2244 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2244)。先广播 scores 并清除尾部正数，替换 input_ids/游标/种子，再检查 byte buffer 并恢复原生状态；失败保留已完成修改。

- L2261 / raise：`RuntimeError('Failed to set llama state data')`；触发分支：llama_cpp.llama_state_set_data(self._ctx.ctx, llama_state, state_size) != state_size；Java：PythonException / PythonErrorType.RUNTIME_ERROR（executed-contract）

### Llama.n_ctx

[固定源码第 2263 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2263)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.n_embd

[固定源码第 2267 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2267)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.n_vocab

[固定源码第 2271 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2271)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.tokenizer

[固定源码第 2275 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2275)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.token_eos

[固定源码第 2279 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2279)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.token_bos

[固定源码第 2283 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2283)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.token_nl

[固定源码第 2287 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2287)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.pooling_type

[固定源码第 2291 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2291)。源码注解是 str，但函数直接返回 _ctx.pooling_type() 的整数；Java 保留整数值。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.close

[固定源码第 2295 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2295)。ExitStack 消耗清理回调；重复 close 有效，回调失败后的重试也保留已消耗状态。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.__del__

[固定源码第 2299 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2299)。调用 close；Python 析构期间异常报告由 Python 运行时承担。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.logits_to_logprobs

[固定源码第 2303 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2303)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.longest_token_prefix

[固定源码第 2321 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2321)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### Llama.from_pretrained

[固定源码第 2331 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2331)。先导入依赖并校验 repo，再匹配唯一文件；主文件先下载，随后校验/下载附加文件；无 local_dir 时再次取得缓存路径。

- L2359 / raise：`ImportError('Llama.from_pretrained requires the huggingface-hub package. You can install it with 'pip install huggingface-hub'.')`；触发分支：except ImportError；Java：计划：ImportError 的依赖/provider 异常桥接（bridge-or-planned; see implementationStatus）
- L2383 / raise：`ValueError(f'No file found in {repo_id} that match {filename}\n\nAvailable Files:\n{json.dumps(file_list)}')`；触发分支：len(matching_files) == 0；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L2389 / raise：`ValueError(f'Multiple files found in {repo_id} matching {filename}\n\nAvailable Files:\n{json.dumps(files)}')`；触发分支：len(matching_files) > 1；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L2419 / raise：`ValueError(f'No file found in {repo_id} that match {additonal_file_name}\n\nAvailable Files:\n{json.dumps(file_list)}')`；触发分支：additional_files → len(matching_additional_files) == 0；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）
- L2425 / raise：`ValueError(f'Multiple files found in {repo_id} matching {additonal_file_name}\n\nAvailable Files:\n{json.dumps(files)}')`；触发分支：additional_files → len(matching_additional_files) > 1；Java：PythonException / PythonErrorType.VALUE_ERROR（bridge-or-planned; see implementationStatus）

### LlamaState.__init__

[固定源码第 2466 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2466)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### LogitsProcessorList.__call__

[固定源码第 2489 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2489)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### StoppingCriteriaList.__call__

[固定源码第 2501 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2501)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### MinTokensLogitsProcessor.__init__

[固定源码第 2508 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2508)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

### MinTokensLogitsProcessor.__call__

[固定源码第 2513 行](https://github.com/abetlen/llama-cpp-python/blob/1652066e0af45f2313b339670ef9555e8a54e545/llama_cpp/llama.py#L2513)。保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。

显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。

## 复现

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/map_llama_functions.py --source-root .tools/python-api-audit
```

机器清单保留完整签名、装饰器、直接调用依赖、错误站点和实现证据，见 [python-llama-mapping.json](python-llama-mapping.json)。求值与状态实现记录见 [执行链路记录](PYTHON_EXECUTION_COMPATIBILITY.md)。
