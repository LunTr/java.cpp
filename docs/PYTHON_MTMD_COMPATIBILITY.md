# mtmd_cpp.py Python 兼容迁移记录

来源：llama-cpp-python 0.3.36（commit 1652066e0af45f2313b339670ef9555e8a54e545）中 llama_cpp/mtmd_cpp.py。

## 第十一轮覆盖（30 项）

### JNA 绑定（29 项 → internal.MtmdLibrary）

- 上下文：mtmd_default_marker、mtmd_context_params_default、mtmd_init_from_file、mtmd_free、mtmd_decode_use_non_causal、mtmd_decode_use_mrope、mtmd_support_vision、mtmd_support_audio、mtmd_get_audio_sample_rate、mtmd_get_marker
- 位图：mtmd_bitmap_init、mtmd_bitmap_init_from_audio、mtmd_bitmap_free、mtmd_bitmap_get_nx、mtmd_bitmap_get_ny、mtmd_bitmap_get_data、mtmd_bitmap_get_n_bytes、mtmd_bitmap_is_audio、mtmd_bitmap_get_id、mtmd_bitmap_set_id、mtmd_bitmap_set_mergeable、mtmd_bitmap_init_lazy
- 输入块：mtmd_input_chunks_init、mtmd_input_chunks_free、mtmd_input_chunks_size、mtmd_input_chunks_get、mtmd_tokenize、mtmd_input_chunk_get_n_tokens、mtmd_input_chunk_get_type

类型映射：c_bool → byte、c_size_t → long、不透明句柄与 const 指针 → Pointer、c_char_p → String；结构按 pinned 字段序声明。

### 结构体与回调

- internal.MtmdContextParams：pinned mtmd_context_params 15 字段布局，ByValue 视图供按值返回/传参
- internal.MtmdInputText：pinned mtmd_input_text 布局，mtmd_tokenize 按引用传入
- 回调：SchedEvalCallback（ggml_backend_sched_eval_callback）、ProgressCallback（mtmd_progress_callback）、BitmapLazyCallback（mtmd_bitmap_lazy_callback）
- NativeLoader.loadMtmd(Path)：mtmd.dll 可选加载，缺失返回 null，不影响既有必需库加载路径

### 实体函数（1 项 → compat.python.PythonMtmdCpp）

- mtmd_get_audio_bitrate：DeprecationWarning 原文 "mtmd_get_audio_bitrate is deprecated; use mtmd_get_audio_sample_rate instead"（stacklevel=2）后委托 mtmd_get_audio_sample_rate；MtmdAccess 端口可注入

## 契约

- 生成器：tools/python_compat/generate_mtmd_contracts.py，AST 抽取固定原版函数体执行，2 组用例（正常/空 ctx、警告类别+消息原文、委托调用 trace）
- 回放：src/test/java/io/github/yaulun/javacpp/compat/python/PythonMtmdContractTest.java，2/2 通过
- fixture：src/test/resources/python-0.3.36-mtmd-contracts.tsv（gitignore，不提交）；元数据 docs/python-mtmd-contracts.json
- 29 项纯绑定函数体为 ...，与 llama_cpp.py 同规则：JNA 同名声明即 bound

## 再生成

```
python tools/python_compat/generate_mtmd_contracts.py --source-root .tools/python-api-audit
```

## 第十二轮追加（35 项，累计 65/87）

### JNA 绑定（33 项 → internal.MtmdLibrary）

- 分词：mtmd_tokenize_from_parts
- chunk 访问器：mtmd_input_chunk_get_tokens_text/get_tokens_image/get_id/get_n_pos/copy/free/get_placeholder/save/load（size_t 出参用 LongByReference，load 的 buf 为二进制走 Pointer）
- image_tokens：get_n_tokens/get_nx/get_ny/get_id/get_n_pos/get_decoder_pos（返回 MtmdTypes.DecoderPos.ByValue）
- 编码与批处理：mtmd_encode、mtmd_encode_chunk、mtmd_get_output_embd、mtmd_batch_init/free/add_chunk/encode/get_output_embd
- 能力探测：mtmd_get_cap_from_file（Caps.ByValue）、mtmd_gen_audio_get_info（GenAudioInfo.ByValue）、mtmd_gen_inp_default（GenInp.ByValue）、mtmd_gen_audio_process（GenInp/GenOut 按引用）
- 测试与 helper：mtmd_test_create_input_chunks、mtmd_helper_support_video、mtmd_helper_init_opt_default、mtmd_helper_bitmap_init_from_file_wrapper、mtmd_helper_bitmap_init_from_buf_wrapper（InitOpt/BitmapWrapper ByValue）

### 实体函数（2 项 → compat.python.PythonMtmdCpp）

- mtmd_helper_bitmap_init_from_file / _from_buf：opt is None 先取 mtmd_helper_init_opt_default，再调 *_wrapper 返回 .bitmap；MtmdAccess 端口回放，BitmapWrapperResult 镜像结构体字段

## 第十三轮收尾（22 项，文件 87/87 全覆盖）

### JNA 绑定（22 项 → internal.MtmdLibrary）

- chunk 统计：mtmd_helper_get_n_tokens、mtmd_helper_get_n_pos、mtmd_helper_image_get_decoder_pos（DecoderPos 出参按引用）
- 评估：mtmd_helper_eval_chunks、mtmd_helper_eval_chunk_single（llama_pos 出参 IntByReference）、mtmd_helper_decode_image_chunk（新增 PostDecodeCallback 回调与 HelperEmbdBatch 结构）
- 视频流：mtmd_helper_video_init_params_default（VideoInitParams.ByValue）、video_init/video_init_from_buf/video_free/video_get_info（新增 VideoInfo.ByValue）、video_read_next（bitmap/text 双出参 PointerByReference）
- 对话与音频生成 helper：mtmd_helper_model_can_chat、gen_audio_init/free/reset、gen_audio_set_input（新增 GenAudioInp 结构按引用）、gen_audio_step_prompt、gen_audio_step_gen（h_state_out PointerByReference、out_stop ByteByReference）、gen_audio_get_output（IntByReference/PointerByReference/LongByReference x2）
- 日志：mtmd_log_set、mtmd_helper_log_set（复用 LlamaLibrary.LogCallback）

至此 mtmd_cpp.py 87 项全部迁移：82 项 JNA 绑定 + 5 项实体函数（含此前 audio_bitrate 与两个 bitmap helper 实体包装）。llama_cpp.llama_seq_id/llama_pos/llama_token 按 i32 映射为 int，llama_token/pos 出参按位宽分别取 IntByReference/LongByReference。
