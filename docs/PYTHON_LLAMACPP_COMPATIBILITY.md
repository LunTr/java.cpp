# llama_cpp.py Python 兼容迁移记录

来源：llama-cpp-python 0.3.36（commit 1652066e0af45f2313b339670ef9555e8a54e545）中 llama_cpp/llama_cpp.py。绑定签名按固定 b11669 头文件核对，30 个符号均在 natives/b11669-cpu/llama.dll 导出表确认存在。

## 本轮覆盖（30 项）

### JNA 绑定（22 项 → internal.LlamaLibrary）

- 默认参数与枚举名：llama_model_quantize_default_params、llama_flash_attn_type_name、llama_load_mode_name、llama_load_mode_from_str、llama_ftype_name
- 后端：llama_backend_free
- 模型文件：llama_model_load_from_splits、llama_model_load_from_file_ptr、llama_model_save_to_file、llama_model_init_from_user
- 系统查询：llama_time_us、llama_max_tensor_buft_overrides、llama_supports_mmap、llama_supports_mlock、llama_supports_rpc
- 上下文/模型访问器：llama_n_ctx_seq、llama_n_ubatch、llama_n_seq_max、llama_n_rs_seq、llama_get_model、llama_model_rope_type、llama_model_n_embd_inp

类型映射：c_bool → byte、c_size_t → long、int64_t → long、uint32_t/int32_t/enum → int、不透明句柄与 FILE* → Pointer、const char** → String[]、按值结构参数 → ByValue 视图。

### 结构体与回调

- internal.LlamaModelQuantizeParams：pinned llama_model_quantize_params 15 字段布局（4×int32 + 6×bool + 4×指针 + size_t），ByValue 视图供按值返回；x64 布局 64 字节、imatrix 偏移 24、max_buf_size 偏移 56 已加入 StructSelfTest 并反射核对
- internal.LlamaLibrary.SetTensorDataCallback：llama_model_set_tensor_data_t（void(*)(ggml_tensor*, void*)）

### 实体函数（8 项 → compat.python.PythonLlamaCpp）

原版为同名 ctypes 绑定先存 `_llama_*` 别名、再定义同名弃用包装：`_warn_deprecated(symbol, hint)` 发出 `DeprecationWarning`（`f"{symbol} is deprecated; {hint}"`，stacklevel=2）后委托 `_llama_*` 原生绑定。Java 侧 `PythonLlamaCpp` 经 `LlamaCppAccess` 端口委托，`PythonWarnings.deprecated` 复刻告警类别与原文：

| 原版定义 | Java 入口 | 委托别名 |
|---|---|---|
| llama_load_model_from_file | `loadModelFromFile` | `_llama_load_model_from_file` |
| llama_free_model | `freeModel` | `_llama_free_model` |
| llama_new_context_with_model | `newContextWithModel` | `_llama_new_context_with_model` |
| llama_n_ctx_train | `nCtxTrain` | `_llama_n_ctx_train` |
| llama_n_embd | `nEmbd` | `_llama_n_embd` |
| llama_n_layer | `nLayer` | `_llama_n_layer` |
| llama_n_head | `nHead` | `_llama_n_head` |
| llama_n_vocab | `nVocab` | `_llama_n_vocab` |

## 契约

- 生成器：tools/python_compat/generate_llamacpp_contracts.py，AST 抽取固定原版 `_warn_deprecated` 与 8 个弃用包装函数体执行（同名后者覆盖 ctypes 桩，与模块语义一致），12 组用例覆盖正常委托、委托抛错传播、error 过滤器（告警变异常、委托未调用）与 ignore 过滤器（告警吞没、委托照常）。
- 回放：src/test/java/io/github/yaulun/javacpp/compat/python/PythonLlamaCppContractTest.java，12/12 通过。
- fixture：src/test/resources/python-0.3.36-llamacpp-contracts.tsv（gitignore，不提交）；元数据 docs/python-llamacpp-contracts.json。
- 22 项纯绑定函数体为 `...`，与既有规则一致：JNA 同名声明即 bound。

## 第二批覆盖（再 30 项，累计 60）

### JNA 绑定（25 项 → internal.LlamaLibrary）

- 模型形状：llama_model_n_embd_out、llama_model_n_layer_nextn、llama_model_n_head、llama_model_n_head_kv、llama_model_n_swa、llama_model_n_cls_out、llama_model_cls_label、llama_model_ftype、llama_model_rope_type 之外补充 llama_model_is_diffusion、llama_model_has_encoder、llama_model_has_decoder、llama_model_decoder_start_token
- 元数据：llama_model_meta_key_str
- 量化：llama_model_quantize（const llama_model_quantize_params* 按引用 → LlamaModelQuantizeParams）
- LoRA 适配器：llama_adapter_lora_init_from_file_ptr、llama_adapter_meta_val_str、llama_adapter_meta_count、llama_adapter_meta_key_by_index、llama_adapter_meta_val_str_by_index、llama_adapter_get_alora_n_invocation_tokens（uint64→long）、llama_adapter_get_alora_invocation_tokens（const llama_token*→Pointer）、llama_set_adapter_cvec（const float*→float[]）
- 内存：llama_memory_seq_div、llama_memory_seq_pos_min、llama_memory_can_shift
- 弃用 C 符号（同名绑定供包装委托）：llama_get_state_size、llama_copy_state_data、llama_set_state_data

### 实体函数（5 项 → compat.python.PythonLlamaCpp）

- `getStateSize`/`copyStateData`/`setStateData`：`_warn_deprecated` 原文后委托 `_llama_*` 同名原生绑定（llama_state_get_size/get_data/set_data 为改名后入口）
- `setAdapterLora`：无同名 C 符号的 Python 端兼容包装——inline `warnings.warn` 原文后按原版构造单元素 ctypes 数组 `(llama_adapter_lora_p_ctypes * 1)(adapter)`、`(c_float * 1)(scale)`，以 `(adapters, 1, scales)` 调 `llama_set_adapters_lora`
- `applyAdapterCvec`：同上 inline warn 后透传 `llama_set_adapter_cvec`

### 契约

llamacpp 对照增至 19 组：新增 state 三包装的正常委托、`set_adapter_lora` 的单元素数组构造、error 过滤器与 `apply_adapter_cvec` 委托抛错分支。PythonLlamaCppContractTest 19/19 通过。

## 第三批覆盖（再 30 项，累计 90）

### JNA 绑定（28 项 → internal.LlamaLibrary）

- 状态/会话文件：llama_state_load_file、llama_state_save_file（llama_token* → int[]，size_t 出参 → long[]）
- 单序列状态：llama_state_seq_get_size/get_data/set_data/save_file/load_file
- 带标志位：llama_state_seq_get_size_ext/get_data_ext/set_data_ext（llama_state_seq_flags → int）
- 批处理：llama_batch_get_one（llama_batch 按值返回 → LlamaBatch）、llama_batch_ext_add/add_embd/add_seq/set_embd_token/set_embd_state/set_output_embd（struct llama_embd 按值 → 新增 24 字节 LlamaEmbd.ByValue）
- 线程与开关：llama_n_threads、llama_n_threads_batch、llama_set_embeddings、llama_set_causal_attn、llama_get_causal_attn、llama_set_warmup、llama_set_abort_callback（新增 AbortCallback，ggml_abort_callback 的 bool 返回 → byte）、llama_synchronize
- 采样指针：llama_get_sampled_token_ith、llama_get_sampled_probs_ith（float* → Pointer）、llama_get_sampled_probs_count_ith
- 弃用 C 符号（同名绑定供包装委托）：llama_load_session_file、llama_save_session_file

### 实体函数（2 项 → compat.python.PythonLlamaCpp）

- `loadSessionFile`/`saveSessionFile`：`_warn_deprecated` 原文（use llama_state_load_file/llama_state_save_file instead）后委托 `_llama_*` 同名原生绑定

### 契约

llamacpp 对照增至 23 组：新增 session 读写两包装的正常委托、委托抛错与 error 过滤器分支。PythonLlamaCppContractTest 23/23 通过。


## 第四批覆盖（50 项，llama_cpp.py 清零）

### JNA 绑定（29 项 → internal.LlamaLibrary）

- 采样输出指针：llama_get_sampled_logits_ith（float*→Pointer）、llama_get_sampled_logits_count_ith、llama_get_sampled_candidates_ith（llama_token*→Pointer）、llama_get_sampled_candidates_count_ith（uint32→int）
- 词表：llama_vocab_is_control、llama_vocab_pad/mask、llama_vocab_get_add_sep（bool→byte）、llama_vocab_get_suppress_tokens（int32* 出参→int[]，llama_token*→Pointer）、llama_vocab_fim_pad/rep/sep
- 聊天模板：llama_chat_builtin_templates（const char** 出参→Pointer[]，size_t→long，int32→int）
- 采样器：llama_set_sampler（bool→byte）、llama_sampler_init（llama_sampler_i*→新增 96 字节 LlamaSamplerI 按引用，llama_sampler_context_t→Pointer）、llama_sampler_name、llama_sampler_copy、llama_sampler_chain_get、llama_sampler_init_adaptive_p
- 分卷路径：llama_split_path/llama_split_prefix（char* 出参→byte[]，const char*→String）
- 日志：llama_log_get（ggml_log_callback* 与 void** 出参→Pointer[]）
- 性能：llama_perf_context（按值返回→新增 48 字节 LlamaPerfContextData.ByValue）、llama_perf_sampler（→16 字节 LlamaPerfSamplerData.ByValue）、llama_perf_sampler_print/reset
- 训练：llama_opt_param_filter_all（bool→byte）、llama_opt_init（llama_opt_params 按值→新增 40 字节 LlamaOptParams.ByValue，镜像 Python ctypes 布局；C 头多出的 optimizer_type 字段按原版省略）、llama_opt_epoch（int64→long，回调/数据集→Pointer）
- 弃用 C 符号（同名绑定供包装委托）：llama_token_get_text/get_score/get_attr/is_eog/is_control/bos/eos/eot/cls/sep/nl/pad、llama_add_bos_token/add_eos_token、llama_token_fim_pre/suf/mid/pad/rep/sep、llama_sampler_init_grammar_lazy（POINTER(c_char_p)→String[]，POINTER(llama_token)→int[]）

### 实体函数（21 项 → compat.python.PythonLlamaCpp）

- 20 个 token_* 弃用包装：`_warn_deprecated` 原文（use llama_vocab_* / llama_vocab_get_add_* instead）后委托 `_llama_*` 同名原生绑定
- `samplerInitGrammarLazy`：`_warn_deprecated` 原文（use llama_sampler_init_grammar_lazy_patterns instead）后 7 参透传委托 `_llama_sampler_init_grammar_lazy`

### 契约

llamacpp 对照增至 46 组：新增 21 个包装的正常委托分支、token_pad 的 error 过滤器分支、grammar_lazy 的委托抛错分支。PythonLlamaCppContractTest 46/46 通过。

### 原生验证

NativeLoader 全量符号解析通过（50 个新符号全在 b11669 dll）；StructSelfTest 新增 sampler_i=96、perf_context_data=48、perf_sampler_data=16、opt_params=40、embd=24 全部通过；llama_split_path 输出与头文件示例一致，llama_chat_builtin_templates 返回 54 项模板，llama_opt_param_filter_all 返回真。

## 再生成

```
python tools/python_compat/generate_llamacpp_contracts.py --source-root .tools/python-api-audit
```
