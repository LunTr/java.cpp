"""Update reviewed Python-function mappings and render the complete pinned inventory."""

import collections
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[2]
auditPath = root / "docs/python-api-audit.json"
audit = json.loads(auditPath.read_text(encoding="utf-8"))
contracts = json.loads((root / "docs/python-chat-contracts.json").read_text(encoding="utf-8"))
mappings = {
    "_get_system_message": "PythonChatFormats.getSystemMessage",
    "_map_roles": "PythonChatFormats.mapRoles",
    "_format_llama2": "PythonChatFormats.formatLlama2Prompt",
    "_format_add_colon_single": "PythonChatFormats.formatAddColonSingle",
    "_format_add_colon_two": "PythonChatFormats.formatAddColonTwo",
    "_format_no_colon_single": "PythonChatFormats.formatNoColonSingle",
    "_format_add_colon_space_single": "PythonChatFormats.formatAddColonSpaceSingle",
    "_format_chatml": "PythonChatFormats.formatChatmlPrompt",
    "_format_chatglm3": "PythonChatFormats.formatChatglm3Prompt",
    "guess_chat_format_from_gguf_metadata": "PythonChatFormats.guessChatFormatFromGgufMetadata",
    "_convert_text_completion_logprobs_to_chat": "PythonChatCompletions.convertTextCompletionLogprobsToChat",
    "_convert_text_completion_to_chat": "PythonChatCompletions.convertTextCompletionToChat",
    "_convert_text_completion_chunks_to_chat": "PythonChatCompletions.convertTextCompletionChunksToChat",
    "_convert_completion_to_chat": "PythonChatCompletions.convertCompletionToChat",
    "format_llama2": "PythonChatFormats.formatLlama2",
    "format_llama3": "PythonChatFormats.formatLlama3",
    "format_alpaca": "PythonChatFormats.formatAlpaca",
    "format_qwen": "PythonChatFormats.formatQwen",
    "format": "PythonChatFormats.formatVicuna",
    "format_oasst_llama": "PythonChatFormats.formatOasstLlama",
    "format_baichuan2": "PythonChatFormats.formatBaichuan2",
    "format_baichuan": "PythonChatFormats.formatBaichuan",
    "format_openbuddy": "PythonChatFormats.formatOpenbuddy",
    "format_redpajama_incite": "PythonChatFormats.formatRedpajamaIncite",
    "format_snoozy": "PythonChatFormats.formatSnoozy",
    "format_phind": "PythonChatFormats.formatPhind",
    "format_intel": "PythonChatFormats.formatIntel",
    "format_open_orca": "PythonChatFormats.formatOpenOrca",
    "format_mistrallite": "PythonChatFormats.formatMistrallite",
    "format_zephyr": "PythonChatFormats.formatZephyr",
    "format_pygmalion": "PythonChatFormats.formatPygmalion",
    "format_chatml": "PythonChatFormats.formatChatml",
    "format_mistral_instruct": "PythonChatFormats.formatMistralInstruct",
    "format_chatglm3": "PythonChatFormats.formatChatglm3",
    "format_openchat": "PythonChatFormats.formatOpenchat",
    "format_saiga": "PythonChatFormats.formatSaiga",
    "format_gemma": "PythonChatFormats.formatGemma",
}
assert len(mappings) == contracts["uniqueFunctions"] == 37
found = set()
for row in audit["functions"]:
    if row["file"] == "llama_cpp/llama_chat_format.py" and row["qualname"] in mappings:
        name = row["qualname"]
        found.add(name)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + mappings[name],
                   reason="逐句移植固定原版完整函数体；静态格式、JSON 形值和已测异常/日志/流式时序已有原版对照；全输入一致性继续验收",
                   contractEvidence="docs/python-chat-contracts.json")
assert found == set(mappings)
stateContracts = json.loads((root / "docs/python-state-contracts.json").read_text(encoding="utf-8"))
stateMappings = {
    ("llama_cache.py", "LlamaRAMCache.__init__"): "PythonLlamaRamCache",
    ("llama_cache.py", "LlamaRAMCache.cache_size"): "PythonLlamaRamCache.cacheSize",
    ("llama_cache.py", "LlamaRAMCache._find_longest_prefix_key"): "PythonLlamaRamCache.findLongestPrefixKey",
    ("llama_cache.py", "LlamaRAMCache.__getitem__"): "PythonLlamaRamCache.get",
    ("llama_cache.py", "LlamaRAMCache.__contains__"): "PythonLlamaRamCache.contains",
    ("llama_cache.py", "LlamaRAMCache.__setitem__"): "PythonLlamaRamCache.put",
    ("llama.py", "Llama.longest_token_prefix"): "PythonTokens.longestTokenPrefix",
    ("llama.py", "LlamaState.__init__"): "PythonLlamaState",
    ("llama.py", "LogitsProcessorList.__call__"): "PythonLogitsProcessorList.apply",
    ("llama.py", "StoppingCriteriaList.__call__"): "PythonStoppingCriteriaList.test",
    ("llama.py", "MinTokensLogitsProcessor.__init__"): "PythonMinTokensLogitsProcessor",
    ("llama.py", "MinTokensLogitsProcessor.__call__"): "PythonMinTokensLogitsProcessor.apply",
}
assert len(stateMappings) == stateContracts["uniqueFunctions"] == 12
foundStates = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in stateMappings:
        foundStates.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + stateMappings[key],
                   reason="固定原版完整函数体对照覆盖前缀、RAM 缓存顺序/淘汰、状态引用和处理器时序；使用 BigInteger 或 intc/single 对应类型；全输入一致性继续验收",
                   contractEvidence="docs/python-state-contracts.json")
assert foundStates == set(stateMappings)
llamaContracts = json.loads((root / "docs/python-llama-contracts.json").read_text(encoding="utf-8"))
llamaMappings = {
    "Llama._input_ids": ("inputIds", "intc token 前缀以 IntBuffer 提供可写共享视图，保留负切片及截断规则"),
    "Llama._scores": ("scores", "single scores 前缀以 FloatBuffer 行视图提供可写共享数据"),
    "Llama.eval_tokens": ("evalTokens", "按原版 deque 的 maxlen 保留末尾 token，Java 返回独立 List 快照"),
    "Llama.eval_logits": ("evalLogits", "按 logits_all 选择行数，Java 返回独立二维 List 快照"),
    "Llama.set_cache": ("setCache", "附加或清除 PythonLlamaRamCache 引用，保留当前游标和待求值标记"),
    "Llama.set_seed": ("setSeed", "BigInteger 保留原版整数赋值语义，采样器由调用方管理"),
    "Llama.reset": ("reset", "先更新游标及待求值标记，再通过调用方内存适配回调处理 recurrent/hybrid 分支；已测回调顺序及异常后状态"),
    "Llama.logits_to_logprobs": ("logitsToLogprobs", "single 向量及二维矩阵 axis 0/1/-1/-2 已对照，数值采用绝对及相对 4e-6 容差；NumPy 警告和更高维数组待迁移"),
}
assert len(llamaMappings) == llamaContracts["uniqueFunctions"] == 8
assert set(llamaMappings) == set(llamaContracts["functions"])
foundLlama = set()
for row in audit["functions"]:
    if row["file"] == "llama_cpp/llama.py" and row["qualname"] in llamaMappings:
        name = row["qualname"]
        method, reason = llamaMappings[name]
        foundLlama.add(name)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python.PythonLlama." + method,
                   reason=reason + "；固定原版完整函数体已有场景对照，全输入一致性继续验收",
                   contractEvidence="docs/python-llama-contracts.json")
assert foundLlama == set(llamaMappings)
tokenizerContracts = json.loads((root / "docs/python-tokenizer-contracts.json").read_text(encoding="utf-8"))
tokenizerMappings = {
    ("llama.py", "Llama.tokenize"): "PythonLlama.tokenize",
    ("llama.py", "Llama.detokenize"): "PythonLlama.detokenize",
    ("llama.py", "Llama.tokenizer"): "PythonLlama.tokenizer",
    ("llama_tokenizer.py", "LlamaTokenizer.__init__"): "PythonLlamaTokenizer",
    ("llama_tokenizer.py", "LlamaTokenizer.tokenize"): "PythonLlamaTokenizer.tokenize",
    ("llama_tokenizer.py", "LlamaTokenizer.detokenize"): "PythonLlamaTokenizer.detokenize",
    ("llama_tokenizer.py", "LlamaTokenizer.encode"): "PythonLlamaTokenizer.encode",
    ("llama_tokenizer.py", "LlamaTokenizer.decode"): "PythonLlamaTokenizer.decode",
}
assert len(tokenizerMappings) == tokenizerContracts["uniqueFunctions"] == 8
assert {key[1] for key in tokenizerMappings} == set(tokenizerContracts["functions"])
foundTokenizer = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in tokenizerMappings:
        foundTokenizer.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + tokenizerMappings[key],
                   reason="固定原版完整函数体对照覆盖默认参数、bytes、UTF-8 ignore、prev_tokens、引用及自定义分词器转发；Vocab 适配使用 b11669，跨原生版本的分词结果继续对照",
                   contractEvidence="docs/python-tokenizer-contracts.json")
assert foundTokenizer == set(tokenizerMappings)
executionContracts = json.loads((root / "docs/python-execution-contracts.json").read_text(encoding="utf-8"))
executionMappings = {
    "Llama.eval": "PythonLlama.eval",
    "Llama.save_state": "PythonLlama.saveState",
    "Llama.load_state": "PythonLlama.loadState",
    "Llama.token_nl": "PythonLlama.tokenNl",
    "Llama.pooling_type": "PythonLlama.poolingType",
    "Llama.close": "PythonLlama.close",
}
queryMappings = {"Llama.n_ctx": "nCtx", "Llama.n_embd": "nEmbd", "Llama.n_vocab": "nVocab",
                 "Llama.token_eos": "tokenEos", "Llama.token_bos": "tokenBos"}
nativeAdditions = {"llama_memory_seq_rm", "llama_get_logits_ith", "llama_state_get_size", "llama_state_get_data",
                   "llama_state_set_data", "llama_pooling_type", "llama_vocab_nl"}
for row in audit["functions"]:
    if row["file"] == "llama_cpp/llama.py":
        name = row["qualname"]
        if name in executionMappings:
            row.update(status="covered", checked=True, pythonExactParity="not-certified",
                       java="compat.python." + executionMappings[name],
                       reason="原版求值/状态/清理完整函数体已有调用顺序及错误后状态对照；查询使用原生整数返回；Java 使用 intc/single 矩形数组及显式执行端口，全输入一致性继续验收",
                       contractEvidence="docs/python-execution-contracts.json")
        elif name in queryMappings:
            row["java"] = "compat.python.PythonLlama." + queryMappings[name]
        elif name in ["Llama.sample", "Llama.generate"]:
            row.update(java="compat.python.PythonLlama." + ("sample" if name.endswith("sample") else "generate"),
                       reason="已逐句移植并对照执行/采样器工厂注入下的状态机、相对索引、send、停止和草稿分支；完整采样参数链及回调输入视图协议继续连接",
                       contractEvidence="docs/python-execution-contracts.json")
    elif row["file"] == "llama_cpp/llama_cpp.py" and row["qualname"] in nativeAdditions:
        row.update(status="bound", checked=True, java="internal.LlamaLibrary." + row["qualname"],
                   reason="按固定 b11669 头文件核对 Windows x64 签名，新增求值/状态原生测试已调用；跨 Python vendor revision 的行为继续核对",
                   pythonExactParity="not-certified", contractEvidence="docs/python-execution-contracts.json")
ctypesContracts = json.loads((root / "docs/python-ctypes-contracts.json").read_text(encoding="utf-8"))
ctypesMappings = {
    "load_shared_library": "PythonCtypesExtensions.loadSharedLibrary",
    "ctypes_function_for_shared_library": "PythonCtypesExtensions.ctypesFunctionForSharedLibrary",
    "ctypes_function_for_shared_library.ctypes_function": "PythonCtypesExtensions.ctypesFunctionForSharedLibrary",
    "ctypes_function_for_shared_library.ctypes_function.decorator": "PythonCtypesExtensions.ctypesFunctionForSharedLibrary",
    "_byref": "PythonCtypesExtensions.byrefStub / PythonCtypesExtensions.byref",
}
assert set(ctypesMappings) == set(ctypesContracts["functions"]) == {row["qualname"] for row in audit["functions"] if row["file"] == "llama_cpp/_ctypes_extensions.py"}
foundCtypes = set()
for row in audit["functions"]:
    if row["file"] == "llama_cpp/_ctypes_extensions.py":
        foundCtypes.add(row["qualname"])
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + ctypesMappings[row["qualname"]],
                   reason="已逐句移植固定原版完整函数体；os/sys/ctypes/pathlib 及加载结果由显式端口提供，全分支、环境变更、kwargs、wraps 属性复制和参数绑定报错已有原版对照；真实操作系统加载效果继续验收",
                   contractEvidence="docs/python-ctypes-contracts.json")
assert foundCtypes == set(ctypesMappings)
chatHandlersContracts = json.loads(
    pathlib.Path("docs/python-chathandlers-contracts.json").read_text(encoding="utf-8"))
internalsContracts = json.loads((root / "docs/python-internals-contracts.json").read_text(encoding="utf-8"))
internalsMappings = {
    "LlamaModel.__init__": "PythonLlamaModel",
    "LlamaModel.close": "PythonLlamaModel.close",
    "LlamaModel.desc": "PythonLlamaModel.desc",
    "LlamaModel.tokenize": "PythonLlamaModel.tokenize",
    "LlamaModel.token_to_piece": "PythonLlamaModel.tokenToPiece",
    "LlamaModel.detokenize": "PythonLlamaModel.detokenize",
    "LlamaModel.metadata": "PythonLlamaModel.metadata",
    "LlamaModel.default_params": "PythonLlamaModel.defaultParams",
    "LlamaContext.__init__": "PythonLlamaContext",
    "LlamaContext.close": "PythonLlamaContext.close",
    "LlamaContext.kv_cache_clear": "PythonLlamaContext.kvCacheClear",
    "LlamaContext.decode": "PythonLlamaContext.decode",
    "LlamaContext.default_params": "PythonLlamaContext.defaultParams",
    "LlamaBatch.__init__": "PythonLlamaBatch",
    "LlamaBatch.close": "PythonLlamaBatch.close",
    "LlamaBatch.reset": "PythonLlamaBatch.reset",
    "LlamaBatch.set_batch": "PythonLlamaBatch.setBatch",
    "LlamaSampler.__init__": "PythonInternalsSampler",
    "LlamaSampler.close": "PythonInternalsSampler.close",
    "LlamaSampler.add_greedy": "PythonInternalsSampler.addGreedy",
    "LlamaSampler.add_dist": "PythonInternalsSampler.addDist",
    "LlamaSampler.add_softmax": "PythonInternalsSampler.addSoftmax",
    "LlamaSampler.add_top_k": "PythonInternalsSampler.addTopK",
    "LlamaSampler.add_top_p": "PythonInternalsSampler.addTopP",
    "LlamaSampler.add_min_p": "PythonInternalsSampler.addMinP",
    "LlamaSampler.add_temp": "PythonInternalsSampler.addTemp",
    "LlamaSampler.add_penalties": "PythonInternalsSampler.addPenalties",
    "LlamaSampler.get_seed": "PythonInternalsSampler.getSeed",
    "LlamaSampler.sample": "PythonInternalsSampler.sample",
    "LlamaModel.__init__.free_model": "PythonLlamaModel.close",
    "LlamaModel.__del__": "PythonLlamaModel.close",
    "LlamaModel.vocab_type": "PythonLlamaModel.vocabType",
    "LlamaModel.rope_freq_scale_train": "PythonLlamaModel.ropeFreqScaleTrain",
    "LlamaModel.get_tensor": "PythonLlamaModel.getTensor",
    "LlamaModel.token_get_text": "PythonLlamaModel.tokenGetText",
    "LlamaModel.token_get_score": "PythonLlamaModel.tokenGetScore",
    "LlamaModel.token_get_attr": "PythonLlamaModel.tokenGetAttr",
    "LlamaModel.token_cls": "PythonLlamaModel.tokenCls",
    "LlamaModel.token_sep": "PythonLlamaModel.tokenSep",
    "LlamaModel.token_nl": "PythonLlamaModel.tokenNl",
    "LlamaModel.token_prefix": "PythonLlamaModel.tokenPrefix",
    "LlamaModel.token_middle": "PythonLlamaModel.tokenMiddle",
    "LlamaModel.token_suffix": "PythonLlamaModel.tokenSuffix",
    "LlamaModel.token_eot": "PythonLlamaModel.tokenEot",
    "LlamaModel.add_bos_token": "PythonLlamaModel.addBosToken",
    "LlamaModel.add_eos_token": "PythonLlamaModel.addEosToken",
    "LlamaContext.__init__.free_ctx": "PythonLlamaContext.close",
    "LlamaContext.__del__": "PythonLlamaContext.close",
    "LlamaContext.pooling_type": "PythonLlamaContext.poolingType",
    "LlamaContext.kv_cache_seq_rm": "PythonLlamaContext.kvCacheSeqRm",
    "LlamaContext.kv_cache_seq_cp": "PythonLlamaContext.kvCacheSeqCp",
    "LlamaContext.kv_cache_seq_keep": "PythonLlamaContext.kvCacheSeqKeep",
    "LlamaContext.kv_cache_seq_shift": "PythonLlamaContext.kvCacheSeqShift",
    "LlamaContext.get_state_size": "PythonLlamaContext.getStateSize",
    "LlamaContext.encode": "PythonLlamaContext.encode",
    "LlamaContext.set_n_threads": "PythonLlamaContext.setNThreads",
    "LlamaContext.get_logits": "PythonLlamaContext.getLogits",
    "LlamaContext.get_logits_ith": "PythonLlamaContext.getLogitsIth",
    "LlamaContext.get_embeddings": "PythonLlamaContext.getEmbeddings",
    "LlamaContext.get_embeddings_ith": "PythonLlamaContext.getEmbeddingsIth",
    "LlamaContext.get_embeddings_seq": "PythonLlamaContext.getEmbeddingsSeq",
    "LlamaContext.set_rng_seed": "PythonLlamaContext.setRngSeed",
    "LlamaContext.sample_repetition_penalties": "PythonLlamaContext.sampleRepetitionPenalties",
    "LlamaContext.sample_softmax": "PythonLlamaContext.sampleSoftmax",
    "LlamaContext.sample_top_k": "PythonLlamaContext.sampleTopK",
    "LlamaContext.sample_top_p": "PythonLlamaContext.sampleTopP",
    "LlamaContext.sample_min_p": "PythonLlamaContext.sampleMinP",
    "LlamaContext.sample_typical": "PythonLlamaContext.sampleTypical",
    "LlamaContext.sample_temp": "PythonLlamaContext.sampleTemp",
    "LlamaContext.sample_grammar": "PythonLlamaContext.sampleGrammar",
    "LlamaContext.sample_token_mirostat": "PythonLlamaContext.sampleTokenMirostat",
    "LlamaContext.sample_token_mirostat_v2": "PythonLlamaContext.sampleTokenMirostatV2",
    "LlamaContext.sample_token_greedy": "PythonLlamaContext.sampleTokenGreedy",
    "LlamaContext.sample_token": "PythonLlamaContext.sampleToken",
    "LlamaContext.grammar_accept_token": "PythonLlamaContext.grammarAcceptToken",
    "LlamaContext.reset_timings": "PythonLlamaContext.resetTimings",
    "LlamaContext.print_timings": "PythonLlamaContext.printTimings",
    "LlamaBatch.__init__.free_batch": "PythonLlamaBatch.close",
    "LlamaBatch.__del__": "PythonLlamaBatch.close",
    "LlamaBatch.n_tokens": "PythonLlamaBatch.nTokens",
    "LlamaBatch.add_sequence": "PythonLlamaBatch.addSequence",
    "normalize_embedding": "PythonInternals.normalizeEmbedding",
    "LlamaTokenDataArray.__init__": "PythonLlamaTokenDataArray",
    "LlamaTokenDataArray.copy_logits": "PythonLlamaTokenDataArray.copyLogits",
    "LlamaSamplingContext.reset": "PythonLlamaSamplingContext.reset",
    "LlamaSamplingContext.cp": "PythonLlamaSamplingContext.cp",
    "LlamaSamplingContext.last": "PythonLlamaSamplingContext.last",
    "LlamaSamplingContext.prev_str": "PythonLlamaSamplingContext.prevStr",
    "LlamaSamplingContext.sample": "PythonLlamaSamplingContext.sample",
    "LlamaSamplingContext.accept": "PythonLlamaSamplingContext.accept",
    "CustomSampler.__init__": "PythonCustomSampler",
    "CustomSampler.__init__.apply_wrapper": "PythonCustomSampler",
    "CustomSampler.__init__.free_wrapper": "PythonCustomSampler",
    "CustomSampler.get_sampler": "PythonCustomSampler.getSampler",
    "LlamaSampler.__init__.free_sampler": "PythonInternalsSampler.close",
    "LlamaSampler.__del__": "PythonInternalsSampler.close",
    "LlamaSampler.add_typical": "PythonInternalsSampler.addTypical",
    "LlamaSampler.add_temp_ext": "PythonInternalsSampler.addTempExt",
    "LlamaSampler.add_xtc": "PythonInternalsSampler.addXtc",
    "LlamaSampler.add_top_n_sigma": "PythonInternalsSampler.addTopNSigma",
    "LlamaSampler.add_mirostat": "PythonInternalsSampler.addMirostat",
    "LlamaSampler.add_mirostat_v2": "PythonInternalsSampler.addMirostatV2",
    "LlamaSampler.add_grammar": "PythonInternalsSampler.addGrammar",
    "LlamaSampler.add_grammar_lazy_patterns": "PythonInternalsSampler.addGrammarLazyPatterns",
    "LlamaSampler.add_dry": "PythonInternalsSampler.addDry",
    "LlamaSampler.add_logit_bias": "PythonInternalsSampler.addLogitBias",
    "LlamaSampler.add_infill": "PythonInternalsSampler.addInfill",
    "LlamaSampler.add_custom": "PythonInternalsSampler.addCustom",
    "LlamaSampler.accept": "PythonInternalsSampler.accept",
    "LlamaSampler.clone": "PythonInternalsSampler.clone",
    "LlamaSampler.clone.free_sampler": "PythonInternalsSampler.clone",
}
assert len(internalsMappings) == 111 == internalsContracts["uniqueFunctions"]
chatHandlersMappings = {
    ("llama_chat_format.py", "_grammar_for_json"): ("PythonChatFormatHandlers.grammarForJson", "已移植 LlamaGrammar.from_string(JSON_GBNF) 直通"),
    ("llama_chat_format.py", "_grammar_for_json_schema"): ("PythonChatFormatHandlers.grammarForJsonSchema", "已移植 from_json_schema 失败按 fallback_to_json 回落"),
    ("llama_chat_format.py", "_grammar_for_response_format"): ("PythonChatFormatHandlers.grammarForResponseFormat", "已移植 type!=json_object 返回 None 与 schema 分支"),
    ("llama_chat_format.py", "hf_autotokenizer_to_chat_formatter"): ("PythonChatFormatHandlers.hfAutotokenizerToChatFormatter", "已移植 AutoTokenizer.from_pretrained 端口"),
    ("llama_chat_format.py", "hf_autotokenizer_to_chat_formatter.format_autotokenizer"): ("PythonChatFormatHandlers.hfAutotokenizerToChatFormatter", "已移植 use_default_system_prompt=False、apply_chat_template(tokenize=False,**kwargs) 与 eos_token stop"),
    ("llama_chat_format.py", "hf_autotokenizer_to_chat_completion_handler"): ("PythonChatFormatHandlers.hfAutotokenizerToChatCompletionHandler", "已移植 formatter 构造后走 chat_formatter_to_chat_completion_handler"),
    ("llama_chat_format.py", "hf_tokenizer_config_to_chat_formatter"): ("PythonChatFormatHandlers.hfTokenizerConfigToChatFormatter", "已移植三项断言与构造期 env.from_string"),
    ("llama_chat_format.py", "hf_tokenizer_config_to_chat_formatter.format_tokenizer_config"): ("PythonChatFormatHandlers.hfTokenizerConfigToChatFormatter", "已移植 add_generation_prompt 追加空 assistant、render(messages,bos,eos,**kwargs) 与 [eos,bos] stop"),
    ("llama_chat_format.py", "hf_tokenizer_config_to_chat_completion_handler"): ("PythonChatFormatHandlers.hfTokenizerConfigToChatCompletionHandler", "同 autotokenizer 包装"),
    ("llama_chat_format.py", "_convert_completion_to_chat_function"): ("PythonChatFormatHandlers.convertCompletionToChatFunction", "已移植 call_ 前缀 tool_id、assert usage、function_call/tool_calls 双写与 finish_reason=tool_calls"),
    ("llama_chat_format.py", "_convert_completion_to_chat_function._stream_response_to_function_stream"): ("PythonChatFormatHandlers.streamResponseToFunctionStream", "已移植首帧空 delta、后续 delta 双写与末帧 finish_reason 帧序"),
    ("llama_chat_format.py", "functionary_chat_handler.generate_type_definition"): ("PythonChatFormatHandlers.generateTypeDefinition", "已移植 /Array<object>/enum 分支"),
    ("llama_chat_format.py", "functionary_chat_handler.generate_shared_definitions"): ("PythonChatFormatHandlers.generateSharedDefinitions", "已移植两空格缩进 type 定义拼接"),
    ("llama_chat_format.py", "functionary_chat_handler.generate_schema_from_functions"): ("PythonChatFormatHandlers.generateSchemaFromFunctionsV1", "v1 缩进与尾换行版本"),
    ("llama_chat_format.py", "functionary_chat_handler.prepare_messages_for_inference"): ("PythonChatFormatHandlers.prepareMessagesForInferenceV1", "已移植 functions./SYSTEM_MESSAGE 注入与 join"),
    ("llama_chat_format.py", "functionary_chat_handler.prepare_messages_for_inference.message_to_str"): ("PythonChatFormatHandlers.messageToStr", "全角色分支与 Unsupported role 原文"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler.generate_type_definition"): ("PythonChatFormatHandlers.generateTypeDefinition", "同一函数体"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler.generate_shared_definitions"): ("PythonChatFormatHandlers.generateSharedDefinitions", "同一函数体"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler.generate_schema_from_functions"): ("PythonChatFormatHandlers.generateSchemaFromFunctionsV2", "v1/v2 无缩进、无尾换行版本"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler.prepare_messages_for_inference"): ("PythonChatFormatHandlers.prepareMessagesForInferenceV2", "已移植 tool_choice==none 空 schema、tokenizer.hf_tokenizer.apply_chat_template 与版本后缀"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler.get_grammar"): ("PythonChatFormatHandlers.functionaryGetGrammar", "已移植 functions/tools 查找序、suppress 内 json_schema_to_gbnf 双调用+print 与 JSON_GBNF 兜底"),
    ("llama_chat_format.py", "Llava15ChatHandler.get_image_urls"): ("PythonChatFormatHandlers.getImageUrls", "已移植 user/content 遍历与 image_url dict/str 双形态"),
    ("llama_chat_format.py", "Llava15ChatHandler.split_text_on_image_urls"): ("PythonChatFormatHandlers.splitTextOnImageUrls", "已移植 substrs 顺序优先（非最左位置）语义"),
    ("llama_chat_format.py", "Llava15ChatHandler.split_text_on_image_urls.find_first"): ("PythonChatFormatHandlers.splitTextOnImageUrls", "嵌套 find_first 随宿主覆盖"),
    ("llama_chat_format.py", "MTMDChatHandler.get_image_urls"): ("PythonChatFormatHandlers.getImageUrls", "同一函数体"),
    ("llama_chat_format.py", "MTMDChatHandler._get_template_messages"): ("PythonChatFormatHandlers.getTemplateMessages", "逐消息转换映射"),
    ("llama_chat_format.py", "MTMDChatHandler._convert_message_for_template"): ("PythonChatFormatHandlers.convertMessageForTemplate", "dict 浅拷贝+list content 转换"),
    ("llama_chat_format.py", "MTMDChatHandler._convert_content_part_for_template"): ("PythonChatFormatHandlers.convertContentPartForTemplate", "image_url 部件转 {type:text,text:media_marker}"),
    ("llama_chat_format.py", "MTMDChatHandler._decode_token_piece"): ("PythonChatFormatHandlers.decodeTokenPiece", "bytes UTF-8 ignore 解码否则 str()"),
    ("llama_chat_format.py", "MTMDChatHandler._postprocess_template_text"): ("PythonChatFormatHandlers.postprocessTemplateText", "逐 url 替换 media_marker"),
    ("llama_chat_format.py", "Qwen25VLChatHandler.__call__"): ("PythonChatFormatHandlers.qwen25vlCall", "已移植 reset/kv_cache_clear/n_tokens=0/input_ids.fill(0)/_last_image_* 清理、verbose stderr 提示与 super().__call__"),
}
assert len(chatHandlersMappings) == 31 == chatHandlersContracts["uniqueFunctions"]
assert set(key[1] for key in chatHandlersMappings) == set(chatHandlersContracts["functions"])
foundChatHandlers = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in chatHandlersMappings:
        target, reason = chatHandlersMappings[key]
        foundChatHandlers.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + target, reason=reason,
                   contractEvidence="docs/python-chathandlers-contracts.json")
assert foundChatHandlers == set(chatHandlersMappings)

chatFullMappings = {
    ("llama_chat_format.py", "Jinja2ChatFormatter.IgnoreGenerationTags.parse"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Jinja2ChatFormatter.__init__"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Jinja2ChatFormatter.__call__"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Jinja2ChatFormatter.__call__.raise_exception"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Jinja2ChatFormatter.__call__.stop_on_last_token"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Jinja2ChatFormatter.to_chat_handler"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "functionary_chat_handler"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler.create_completion"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "functionary_v1_v2_chat_handler.generate_streaming"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler.__init__"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler._init_mtmd_context"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler._init_mtmd_context.mtmd_free"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler.load_image"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler._create_bitmap_from_bytes"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler.__call__"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler._load_image"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "Llava15ChatHandler.from_pretrained"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler.__init__"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler._init_mtmd_context"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler._init_mtmd_context.mtmd_free"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler.load_image"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler._create_bitmap_from_bytes"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler._get_chat_template"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler.__call__"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler.__call__.raise_exception"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler._load_image"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "MTMDChatHandler.from_pretrained"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
    ("llama_chat_format.py", "chatml_function_calling"): ("PythonChatHandlersFull", "已移植固定源码函数体；jinja/mtmd/huggingface_hub/llama 全部经可注入端口回放"),
}
chatFullContracts = json.loads((root / "docs/python-chatfull-contracts.json").read_text(encoding="utf-8"))
assert len(chatFullMappings) == 29 == chatFullContracts["uniqueFunctions"]
assert set(key[1] for key in chatFullMappings) == set(chatFullContracts["functions"])
foundChatFull = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in chatFullMappings:
        target, reason = chatFullMappings[key]
        foundChatFull.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + target, reason=reason,
                   contractEvidence="docs/python-chatfull-contracts.json")
assert foundChatFull == set(chatFullMappings)

grammarMappings = {
    ("llama_grammar.py", "LlamaGrammar.__init__"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "LlamaGrammar.from_string"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "LlamaGrammar.from_file"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "LlamaGrammar.from_json_schema"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "_build_repetition"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "_build_repetition.opt_repetitions"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "BuiltinRule.__init__"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.__init__"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._format_literal"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.not_literal"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.not_literal.recurse"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._add_rule"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.resolve_refs"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.resolve_refs.visit"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._generate_union_rule"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._visit_pattern"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._visit_pattern.to_rule"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._visit_pattern.transform"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._visit_pattern.transform.get_dot"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._visit_pattern.transform.join_seq"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._resolve_ref"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._generate_constant_rule"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.visit"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.visit.add_component"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._add_primitive"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._build_object_rule"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter._build_object_rule.get_recursive_refs"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "SchemaConverter.format_grammar"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
    ("llama_grammar.py", "json_schema_to_gbnf"): ("PythonLlamaGrammar", "纯 Python 输入/输出等价移植；文件与网络经端口"),
}
grammarContracts = json.loads((root / "docs/python-grammar-contracts.json").read_text(encoding="utf-8"))
assert len(grammarMappings) == 29 == grammarContracts["uniqueFunctions"]
assert set(key[1] for key in grammarMappings) == set(grammarContracts["functions"])
foundGrammar = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in grammarMappings:
        target, reason = grammarMappings[key]
        foundGrammar.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + target, reason=reason,
                   contractEvidence="docs/python-grammar-contracts.json")
assert foundGrammar == set(grammarMappings)


assert set(internalsMappings) == set(internalsContracts["functions"])
foundInternals = set()
for row in audit["functions"]:
    if row["file"] == "llama_cpp/_internals.py" and row["qualname"] in internalsMappings:
        name = row["qualname"]
        foundInternals.add(name)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + internalsMappings[name],
                   reason="已逐句移植固定原版函数体；调用顺序、缓冲区约定、错误类别/文本、ExitStack 关闭顺序和批次字段写入经追踪原生端口对照；真实 b11669 行为继续验收",
                   contractEvidence="docs/python-internals-contracts.json")
assert foundInternals == set(internalsMappings)
lowlevelContracts = json.loads((root / "docs/python-lowlevel-contracts.json").read_text(encoding="utf-8"))
lowlevelMappings = {
    ("_logger.py", "llama_log_callback"): ("PythonLlamaLogger.logCallback",
        "已逐句移植固定原版函数体：GGML 级别映射、CONT 级别沿用、logger.level 阈值门控、stderr 分两次写入及 _last_log_level 更新均经原版对照"),
    ("_logger.py", "set_verbose"): ("PythonLlamaLogger.setVerbose",
        "已移植 logger.setLevel(DEBUG/ERROR) 分支语义，原版对照核对切换后各 ggml 级别的打印门控"),
    ("llama_cpp.py", "_warn_deprecated"): ("PythonWarnings.deprecated",
        "类别与消息有原版契约对照；过滤器动作（always/ignore/error/once/module/default）及调用方模块规则经固定原版 warnings 对照"),
    ("llama_tokenizer.py", "LlamaTokenizer.from_ggml_file"): ("PythonLlamaTokenizer.fromGgmlFile",
        "已移植 cls(llama_cpp.Llama(model_path=path, vocab_only=True)) 工厂语义；kwargs 构造及 _model 取址经原版对照"),
    ("llama.py", "Llama.ctx"): ("PythonLlama.ctx",
        "已移植属性直读：返回存储的上下文句柄，关闭后仍返回原值，与原版 _ctx.ctx 一致"),
    ("llama.py", "Llama.model"): ("PythonLlama.model",
        "已移植属性直读：返回存储的模型句柄，关闭后仍返回原值，与原版 _model.model 一致"),
    ("llama.py", "Llama.__del__"): ("PythonLlama.close / Cleaner",
        "已移植 __del__ 即 self.close()；Java 由幂等 close 与 Cleaner 注册承担析构时序，原版对照核对 close 调用"),
    ("llama.py", "Llama.__init__.free_lora_adapter"): ("PythonLlamaResource.free",
        "已移植嵌套释放闭包：空指针早退、成功后清空引用、释放失败保留引用均经原版对照"),
}
assert len(lowlevelMappings) == 8 == lowlevelContracts["uniqueFunctions"]
assert set(key[1] for key in lowlevelMappings) == set(lowlevelContracts["functions"])
foundLowlevel = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in lowlevelMappings:
        target, reason = lowlevelMappings[key]
        foundLowlevel.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + target, reason=reason,
                   contractEvidence="docs/python-lowlevel-contracts.json")
assert foundLowlevel == set(lowlevelMappings)
helpersContracts = json.loads((root / "docs/python-helpers-contracts.json").read_text(encoding="utf-8"))
helpersMappings = {
    ("_utils.py", "suppress_stdout_stderr.__init__"): ("PythonSuppressStdoutStderr",
        "已移植构造即存 disable 标志；enter/exit 经注入 os/sys/null 文件端口重放原版 dup/dup2/close 顺序与 ipykernel _original_stdstream_copy 分支"),
    ("_utils.py", "suppress_stdout_stderr.__enter__"): ("PythonSuppressStdoutStderr.enter", "同上"),
    ("_utils.py", "suppress_stdout_stderr.__exit__"): ("PythonSuppressStdoutStderr.exit", "同上"),
    ("_utils.py", "MetaSingleton.__call__"): ("PythonSingleton.call",
        "已移植按类注册的共享实例表；构造异常传播且不写入注册表，原版对照核对缓存命中与异常路径"),
    ("_utils.py", "Singleton.__init__"): ("PythonSingleton",
        "已移植仅链接 super().__init__ 的空构造"),
    ("llama_tokenizer.py", "BaseLlamaTokenizer.tokenize"): ("PythonBaseLlamaTokenizer.tokenize",
        "已移植 abstractmethod 基类方法体抛 NotImplementedError；ABC 实例化 TypeError 名单按原版排序"),
    ("llama_tokenizer.py", "BaseLlamaTokenizer.detokenize"): ("PythonBaseLlamaTokenizer.detokenize", "同上"),
    ("llama_tokenizer.py", "LlamaHFTokenizer.__init__"): ("PythonHfTokenizer",
        "已移植构造存 hf_tokenizer 引用"),
    ("llama_tokenizer.py", "LlamaHFTokenizer.tokenize"): ("PythonHfTokenizer.tokenize",
        "已移植 errors=ignore 解码后 encode(add_special_tokens=special) 调用"),
    ("llama_tokenizer.py", "LlamaHFTokenizer.detokenize"): ("PythonHfTokenizer.detokenize",
        "已移植 prev_tokens 前缀差分切片与 skip_special_tokens 取反语义"),
    ("llama_tokenizer.py", "LlamaHFTokenizer.from_pretrained"): ("PythonHfTokenizer.fromPretrained",
        "已移植 transformers 导入失败原文与 kwargs 工厂路径；transformers 由工厂端口注入"),
    ("llama_speculative.py", "LlamaDraftModel.__call__"): ("PythonDraftModel.call",
        "已移植 abstractmethod 抛 NotImplementedError 与 ABC 实例化 TypeError"),
    ("llama_speculative.py", "LlamaPromptLookupDecoding.__init__"): ("PythonPromptLookupDecoding",
        "已移植默认 max_ngram_size=2/num_pred_tokens=10 字段赋值"),
    ("llama_speculative.py", "LlamaPromptLookupDecoding.find_candidate_pred_tokens"): ("PythonPromptLookupDecoding.findCandidatePredTokens",
        "已移植降序 ngram 滑窗匹配与首个续写截取，空匹配返回空 intc 数组"),
    ("llama_speculative.py", "LlamaPromptLookupDecoding.__call__"): ("PythonPromptLookupDecoding.call",
        "已移植 kwargs 透传到静态查找"),
    ("server/errors.py", "ErrorResponseFormatters.context_length_exceeded"): ("PythonServerErrors.contextLengthExceeded",
        "已移植 chat/completion 两种消息模板、max_tokens None→0 求和与 400/invalid_request_error/messages/context_length_exceeded 载荷"),
    ("server/errors.py", "ErrorResponseFormatters.model_not_found"): ("PythonServerErrors.modelNotFound",
        "已移植模型路径反引号消息与固定 type/param/code 字段"),
    ("server/errors.py", "RouteErrorHandler.error_message_wrapper"): ("PythonServerErrors.RouteErrorHandler.errorMessageWrapper",
        "已移植 isinstance 门控、pattern 顺序搜索、未匹配时 stderr 四段写入与 500 兜底"),
    ("server/errors.py", "RouteErrorHandler.get_route_handler"): ("PythonServerErrors.RouteErrorHandler",
        "已移植 super().get_route_handler() 注入与异步包装"),
    ("server/errors.py", "RouteErrorHandler.get_route_handler.custom_route_handler"): ("PythonServerErrors.RouteErrorHandler.customRouteHandler",
        "已移植计时头、HTTPException 穿透、request.json() 异常传播与 body 重解析失败兜底"),
}
assert len(helpersMappings) == 20 == helpersContracts["uniqueFunctions"]
assert set(key[1] for key in helpersMappings) == set(helpersContracts["functions"])
foundHelpers = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in helpersMappings:
        target, reason = helpersMappings[key]
        foundHelpers.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + target, reason=reason,
                   contractEvidence="docs/python-helpers-contracts.json")
assert foundHelpers == set(helpersMappings)
serverContracts = json.loads((root / "docs/python-server-contracts.json").read_text(encoding="utf-8"))
serverMappings = {
    ("server/cli.py", "_get_base_type"): ("PythonServerCli.getBaseType",
        "已移植 typing origin 递归展开：Literal 取首参类型、Union 过滤 NoneType 取首项、list 递归参数；断言语义由注入注解端口承担"),
    ("server/cli.py", "_contains_list_type"): ("PythonServerCli.containsListType",
        "已移植 origin 为 list 命中、Literal/Union 任一参数递归判定"),
    ("server/cli.py", "_contains_dict_type"): ("PythonServerCli.containsDictType", "同上 dict 判定"),
    ("server/cli.py", "_parse_bool_arg"): ("PythonServerCli.parseBoolArg",
        "已移植 bytes 解码、小写去空白、true/false 集合与 ValueError 原文"),
    ("server/cli.py", "_parse_json_object_arg"): ("PythonServerCli.parseJsonObjectArg",
        "已移植 bytes 解码、json 解析（内部最小解析器）、非 dict 时 ValueError 原文；JSONDecodeError 类别保留"),
    ("server/cli.py", "add_args_from_model"): ("PythonServerCli.addArgsFromModel",
        "已移植 description 追加 default、base/list/dict 分派、bool 专用 _parse_bool_arg 分支与 add_argument 调用顺序"),
    ("server/cli.py", "parse_model_from_args"): ("PythonServerCli.parseModelFromArgs",
        "已移植仅取非 None 且属于 model_fields 的命名空间项构造模型"),
    ("server/settings.py", "ModelSettings.set_dynamic_defaults"): ("PythonServerSettings.setDynamicDefaults",
        "已移植 n_threads/n_threads_batch 的 -1→cpu_count 替换与原地写入"),
    ("server/app.py", "set_server_settings"): ("PythonServerApp.setServerSettings", "已移植全局赋值"),
    ("server/app.py", "get_server_settings"): ("PythonServerApp.getServerSettings",
        "已移植单次 yield 的生成器语义"),
    ("server/app.py", "set_llama_proxy"): ("PythonServerApp.setLlamaProxy",
        "已移植 LlamaProxy(models=...) 构造，代理工厂注入"),
    ("server/app.py", "set_ping_message_factory"): ("PythonServerApp.setPingMessageFactory", "已移植全局赋值"),
    ("server/app.py", "get_llama_proxy"): ("PythonServerApp.getLlamaProxy",
        "已移植 outer acquire→inner acquire→outer release→inner release 双锁顺序与 releaseOuterLock 兜底"),
    ("server/app.py", "_logit_bias_tokens_to_input_ids"): ("PythonServerApp.logitBiasTokensToInputIds",
        "已移植 utf-8 编码后 tokenize(add_bos=False,special=True) 遍历写 str(input_id)->score"),
    ("server/model.py", "LlamaProxy.__init__"): ("PythonLlamaProxy",
        "已移植空列表 AssertionError 原文、alias 回填 model、默认模型即时加载"),
    ("server/model.py", "LlamaProxy.__call__"): ("PythonLlamaProxy.call",
        "已移植未知模型回落默认、同 alias 命中直返、切换前关闭旧模型"),
    ("server/model.py", "LlamaProxy.__getitem__"): ("PythonLlamaProxy.get", "已移植 model_dump 转发与 KeyError 引号消息"),
    ("server/model.py", "LlamaProxy.__setitem__"): ("PythonLlamaProxy.set", "已移植 str/bytes 走 model_validate_json 端口"),
    ("server/model.py", "LlamaProxy.__iter__"): ("PythonLlamaProxy.keys", "已移植字典序迭代"),
    ("server/model.py", "LlamaProxy.free"): ("PythonLlamaProxy.free", "已移植关闭并删除当前模型引用"),
    ("server/app.py", "create_app"): ("PythonServerApp.createApp",
        "已移植 CONFIG_FILE 环境变量→JSON/yaml 配置分支、Settings/ServerSettings/ModelSettings.validate 序列、双断言、FastAPI+middleware+CORS+router 调用顺序及 disable_ping_events 空工厂"),
    ("server/app.py", "prepare_request_resources"): ("PythonServerApp.prepareRequestResources",
        "已移植 proxy None→503、logit_bias tokens 转换、grammar 编译、min_tokens logits_processor 合并/extend"),
    ("server/app.py", "get_event_publisher"): ("PythonServerApp.getEventPublisher",
        "已移植双锁代理获取、send channel enter/close、逐块 json.dumps send、disconnect 与 interrupt_requests+outer.locked() 取消、cancelled 后双 print+raise、[DONE] 哨兵"),
    ("server/app.py", "create_completion"): ("PythonServerApp.createCompletion",
        "已移植 prompt 列表归一化断言、copilot-codex 路径别名、exclude 集合、stream 分支 ESR+partial kwargs 绑定、非流式双锁+断连 400+threadpool 调用"),
    ("server/app.py", "create_embedding"): ("PythonServerApp.createEmbedding",
        "已移植 proxy(model).create_embedding threadpool 调用与 exclude={user} dump；proxy None 抛 TypeError 原文"),
    ("server/app.py", "create_chat_completion"): ("PythonServerApp.createChatCompletion",
        "已移植 exclude 集合、stream 分支 ESR+partial（Llama.create_chat_completion）、非流式双锁+断连 400+threadpool bound-method 调用"),
}
assert len(serverMappings) == 26 == serverContracts["uniqueFunctions"]
assert set(key[1] for key in serverMappings) == set(serverContracts["functions"])
foundServer = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in serverMappings:
        target, reason = serverMappings[key]
        foundServer.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + target, reason=reason,
                   contractEvidence="docs/python-server-contracts.json")
assert foundServer == set(serverMappings)
chatContracts = json.loads((root / "docs/python-chatregistry-contracts.json").read_text(encoding="utf-8"))
chatMappings = {
    ("llama_cpp_ext.py", "_ctypes_function_from_names"): ("PythonCtypesExt.ctypesFunctionFromNames",
        "已移植逐名 getattr 收集 missing、首个命中赋值 argtypes/restype、wraps 后返回、全部缺失抛 AttributeError 原文"),
    ("llama_cpp_ext.py", "_ctypes_function_from_names.decorator"): ("PythonCtypesExt.ctypesFunctionFromNames", "同上嵌套装饰器"),
    ("llama_chat_format.py", "LlamaChatCompletionHandler.__call__"): ("PythonChatFormat.handlerCall",
        "Protocol 省略号方法体返回 None；缺参 TypeError 由测试侧参数绑定复刻"),
    ("llama_chat_format.py", "LlamaChatCompletionHandlerRegistry.register_chat_completion_handler"): ("PythonChatHandlerRegistry.registerChatCompletionHandler",
        "已移植 overwrite=False 重复名 ValueError 原文；处理表挂在类属性上按单例共享"),
    ("llama_chat_format.py", "LlamaChatCompletionHandlerRegistry.unregister_chat_handler"): ("PythonChatHandlerRegistry.unregisterChatHandler",
        "已移植未注册 ValueError 原文"),
    ("llama_chat_format.py", "LlamaChatCompletionHandlerRegistry.get_chat_completion_handler_by_name"): ("PythonChatHandlerRegistry.getChatCompletionHandlerByName",
        "已移植 KeyError→NotFound 自定义异常与合法名单 repr"),
    ("llama_chat_format.py", "get_chat_completion_handler"): ("PythonChatHandlerRegistry.getChatCompletionHandler",
        "已移植经 Singleton 注册表的模块级查询"),
    ("llama_chat_format.py", "register_chat_completion_handler"): ("PythonChatHandlerRegistry.registerChatCompletionHandler",
        "已移植返回装饰器的工厂"),
    ("llama_chat_format.py", "register_chat_completion_handler.decorator"): ("PythonChatHandlerRegistry.registerChatCompletionHandler",
        "已移植装饰器注册并原样返回 f"),
    ("llama_chat_format.py", "register_chat_format"): ("PythonChatHandlerRegistry.registerChatFormat",
        "已移植经 formatterToHandler 端口包装后注册"),
    ("llama_chat_format.py", "register_chat_format.decorator"): ("PythonChatHandlerRegistry.registerChatFormat", "同上嵌套装饰器"),
    ("llama_chat_format.py", "ChatFormatter.__call__"): ("PythonChatFormat.formatterCall",
        "Protocol 省略号方法体返回 None"),
    ("llama_chat_format.py", "Jinja2ChatFormatter.strftime_now"): ("PythonChatFormat.strftimeNow",
        "已移植 datetime.now().strftime(f)，时钟注入"),
    ("llama_chat_format.py", "Jinja2ChatFormatter.tojson"): ("PythonChatFormat.tojson + PythonJson",
        "已移植 json.dumps 参数组；新增 PythonJson 复刻 ensure_ascii/indent/separators/sort_keys 与浮点 repr"),
    ("llama_chat_format.py", "chat_formatter_to_chat_completion_handler"): ("PythonChatFormat.chatFormatterToChatCompletionHandler",
        "已移植返回嵌套闭包；真实 handler 体经端口重放"),
    ("llama_chat_format.py", "chat_formatter_to_chat_completion_handler.chat_completion_handler"): ("PythonChatFormat.chatCompletionHandler",
        "已移植完整嵌套体：stop 合并、response_format grammar、functions/tool_call 转换、tool_choice 查找与 ValueError、grammar 失败回退 JSON_GBNF、create_completion 23 个 kwargs 与两个 convert 出口"),
    ("server/app.py", "authenticate"): ("PythonServerApp.authenticate",
        "已移植 api_key 未配置放行、credentials 命中返回、否则 HTTP 401"),
    ("server/app.py", "tokenize"): ("PythonServerApp.tokenizeEndpoint", "已移植 utf-8 编码 + special=True tokenize"),
    ("server/app.py", "detokenize"): ("PythonServerApp.detokenizeEndpoint", "已移植 detokenize + utf-8 解码"),
    ("server/app.py", "count_query_tokens"): ("PythonServerApp.countQueryTokensEndpoint", "已移植 len(tokens) 返回"),
    ("server/app.py", "get_models"): ("PythonServerApp.getModels", "已移植代理迭代生成固定字段模型列表"),
}
assert len(chatMappings) == 21 == len(chatContracts["functions"]) == chatContracts["uniqueFunctions"]
assert set(key[1] for key in chatMappings) == set(chatContracts["functions"])
foundChat = set()
for row in audit["functions"]:
    key = (row["file"].removeprefix("llama_cpp/"), row["qualname"])
    if key in chatMappings:
        target, reason = chatMappings[key]
        foundChat.add(key)
        row.update(status="covered", checked=True, pythonExactParity="not-certified",
                   java="compat.python." + target, reason=reason,
                   contractEvidence="docs/python-chatregistry-contracts.json")
assert foundChat == set(chatMappings)
for row in audit["functions"]:
    if row["file"] == "llama_cpp/llama_cpp.py" and row["qualname"] in {
            "llama_batch_init", "llama_batch_free", "llama_decode",
            "llama_context_default_params", "llama_init_from_model"}:
        row.update(status="bound", checked=True, java="internal.LlamaLibrary." + row["qualname"],
                   reason="已声明 b11669 Windows x64 JNA 入口，完整 native verify 已验证加载；Python vendor 同版本行为对照继续核实",
                   pythonExactParity="not-certified", contractEvidence="docs/python-internals-contracts.json")
supplementCounts = {name: json.loads((root / f"docs/python-{name}-contracts.json").read_text(encoding="utf-8"))["caseCount"]
                    for name in ("services", "completion", "configuration")}
supplements = {
    "Llama._init_sampler": ("covered", "PythonLlama.initSampler", "services"),
    "Llama._init_sampler.apply_func": ("covered", "PythonLlama.applyProcessors", "services"),
    "Llama.create_embedding": ("covered", "PythonLlamaServices.createEmbedding", "services"),
    "Llama.embed": ("covered", "PythonLlamaServices.embed", "services"),
    "Llama.embed.decode_batch": ("covered", "PythonLlamaServices.decodeEmbedding", "services"),
    "Llama._create_completion": ("partial", "PythonCompletion", "completion"),
    "Llama._create_completion.logit_bias_processor": ("partial", "PythonCompletion.logitBias", "completion"),
    "Llama.create_completion": ("covered", "PythonLlamaServices.createCompletion", "services"),
    "Llama.__call__": ("covered", "PythonLlamaServices.call", "services"),
    "Llama.create_chat_completion": ("covered", "PythonLlamaServices.createChatCompletion", "services"),
    "Llama.create_chat_completion_openai_v1": ("covered", "PythonLlamaServices.createChatCompletionOpenAiV1", "services"),
    "Llama.__getstate__": ("covered", "PythonLlamaConfiguration.getState", "configuration"),
    "Llama.__setstate__": ("partial", "PythonLlamaConfiguration.setState", "configuration"),
    "Llama.from_pretrained": ("covered", "PythonPretrained.fromPretrained", "configuration"),
}
for row in audit["functions"]:
    if row["file"] != "llama_cpp/llama.py": continue
    name = row["qualname"]
    if name in supplements:
        status, target, evidence = supplements[name]
        row.update(status=status, checked=status == "covered", java="compat.python." + target,
                   pythonExactParity="not-certified", contractEvidence=f"docs/python-{evidence}-contracts.json",
                   reason="已实现并执行固定原版完整函数体对照；covered 仅指已测端口/类型范围的能力，partial 保留缓存时序、动态类型、完整 provider 或初始化失败状态的后续核实；全行为一致性未认证")
    elif name == "Llama.__init__":
        row.update(java="PythonNativeLlama.load / compat.python.PythonLlama.load",
                   reason="原生构造、Python 默认参数、LoRA 和配置重建已实现并有模型测试；完整 Jinja2、动态参数转换及初始化失败状态继续核实")
    elif name in {"Llama.__init__.free_lora_adapter", "Llama.__del__"}:
        pass  # covered by docs/python-lowlevel-contracts.json above
    elif name in {"Llama.sample", "Llama.generate"}:
        row["reason"] = "完整采样参数入口和状态机已接入；原版执行对照及原生测试已通过；动态类型和回调共享视图语义继续核实"
llamacppDeprecated = {
    "llama_load_model_from_file": "loadModelFromFile",
    "llama_free_model": "freeModel",
    "llama_new_context_with_model": "newContextWithModel",
    "llama_n_ctx_train": "nCtxTrain",
    "llama_n_embd": "nEmbd",
    "llama_n_layer": "nLayer",
    "llama_n_head": "nHead",
    "llama_n_vocab": "nVocab",
    "llama_get_state_size": "getStateSize",
    "llama_copy_state_data": "copyStateData",
    "llama_set_state_data": "setStateData",
    "llama_load_session_file": "loadSessionFile",
    "llama_save_session_file": "saveSessionFile",
    "llama_token_get_text": "tokenGetText",
    "llama_token_get_score": "tokenGetScore",
    "llama_token_get_attr": "tokenGetAttr",
    "llama_token_is_eog": "tokenIsEog",
    "llama_token_is_control": "tokenIsControl",
    "llama_token_bos": "tokenBos",
    "llama_token_eos": "tokenEos",
    "llama_token_eot": "tokenEot",
    "llama_token_cls": "tokenCls",
    "llama_token_sep": "tokenSep",
    "llama_token_nl": "tokenNl",
    "llama_token_pad": "tokenPad",
    "llama_add_bos_token": "addBosToken",
    "llama_add_eos_token": "addEosToken",
    "llama_token_fim_pre": "tokenFimPre",
    "llama_token_fim_suf": "tokenFimSuf",
    "llama_token_fim_mid": "tokenFimMid",
    "llama_token_fim_pad": "tokenFimPad",
    "llama_token_fim_rep": "tokenFimRep",
    "llama_token_fim_sep": "tokenFimSep",
    "llama_sampler_init_grammar_lazy": "samplerInitGrammarLazy",
}
llamacppPyOnly = {
    "llama_set_adapter_lora": "setAdapterLora",
    "llama_apply_adapter_cvec": "applyAdapterCvec",
}
for row in audit["functions"]:
    if row["file"] == "llama_cpp/llama_cpp.py" and row["qualname"] in llamacppDeprecated and row["status"] == "missing":
        row.update(status="covered", checked=True,
                   java="compat.python.PythonLlamaCpp." + llamacppDeprecated[row["qualname"]],
                   reason="已逐句移植固定原版弃用包装：DeprecationWarning 原文经 _warn_deprecated 发出后委托同名原生绑定；告警/委托时序与 error/ignore 过滤器分支经原版对照",
                   pythonExactParity="not-certified", contractEvidence="docs/python-llamacpp-contracts.json")
    elif row["file"] == "llama_cpp/llama_cpp.py" and row["qualname"] in llamacppPyOnly and row["status"] == "missing":
        row.update(status="covered", checked=True,
                   java="compat.python.PythonLlamaCpp." + llamacppPyOnly[row["qualname"]],
                   reason="已逐句移植固定原版 Python 端弃用包装（无同名 C 符号）：inline warnings.warn 原文后委托改名入口，set_adapter_lora 按原版构造单元素 ctypes 数组传 adapters/scales；告警/委托时序经原版对照",
                   pythonExactParity="not-certified", contractEvidence="docs/python-llamacpp-contracts.json")
declared = {line.split("(")[0].split()[-1] for line in (root / "src/Java_Cpp/io/github/yaulun/javacpp/internal/LlamaLibrary.java").read_text(encoding="utf-8").splitlines() if " llama_" in line and "(" in line}
for row in audit["functions"]:
    if row["file"] == "llama_cpp/llama_cpp.py" and row["qualname"] in declared and row["status"] == "missing":
        row.update(status="bound", checked=True, java="internal.LlamaLibrary." + row["qualname"],
                   reason="已声明 b11669 Windows x64 JNA 入口，完整 native verify 已验证加载；Python vendor 同版本行为对照继续核实", pythonExactParity="not-certified")
mtmdDeclared = {line.split("(")[0].split()[-1] for line in (root / "src/Java_Cpp/io/github/yaulun/javacpp/internal/MtmdLibrary.java").read_text(encoding="utf-8").splitlines() if " mtmd_" in line and "(" in line}
for row in audit["functions"]:
    if row["file"] == "llama_cpp/mtmd_cpp.py" and row["qualname"] in mtmdDeclared and row["status"] == "missing":
        row.update(status="bound", checked=True, java="internal.MtmdLibrary." + row["qualname"],
                   reason="已声明 pinned mtmd.h JNA 入口并支持 mtmd.dll 可选加载；Python vendor 同版本行为对照继续核实", pythonExactParity="not-certified")
    if row["file"] == "llama_cpp/mtmd_cpp.py" and row["qualname"] == "mtmd_get_audio_bitrate" and row["status"] == "missing":
        row.update(status="covered", checked=True, java="compat.python.PythonMtmdCpp.getAudioBitrate",
                   reason="已逐句移植固定原版函数体：DeprecationWarning 原文与 mtmd_get_audio_sample_rate 委托调用经原版对照",
                   pythonExactParity="not-certified", contractEvidence="docs/python-mtmd-contracts.json")
mtmdHelpers = {"mtmd_helper_bitmap_init_from_file": "bitmapInitFromFile",
               "mtmd_helper_bitmap_init_from_buf": "bitmapInitFromBuf"}
for row in audit["functions"]:
    if row["file"] == "llama_cpp/mtmd_cpp.py" and row["qualname"] in mtmdHelpers and row["status"] == "missing":
        row.update(status="covered", checked=True,
                   java="compat.python.PythonMtmdCpp." + mtmdHelpers[row["qualname"]],
                   reason="已逐句移植固定原版函数体：opt is None 时先取 mtmd_helper_init_opt_default 再调 wrapper 并返回 .bitmap，默认/自定义两路经原版对照",
                   pythonExactParity="not-certified", contractEvidence="docs/python-mtmd-contracts.json")
for row in audit["functions"]:
    if row["file"] != "llama_cpp/llama_cache.py": continue
    owner, method = row["qualname"].split(".")
    methods = {"__init__": "<init>", "cache_size": "cacheSize",
               "_find_longest_prefix_key": "findLongestPrefixKey", "__getitem__": "get",
               "__contains__": "contains", "__setitem__": "put"}
    if owner == "BaseLlamaCache":
        row.update(status="covered", checked=True, java="compat.python.PythonLlamaCache." + methods[method],
                   pythonExactParity="not-certified", contractEvidence="docs/python-cache-contracts.json",
                   reason="已移植 ABC 实例化规则：缺实现的类构造时按原版排序名单抛 TypeError，基类 abstractmethod 方法体显式调用抛 NotImplementedError；固定原版对照核对 8 组场景")
    elif owner == "LlamaDiskCache":
        row.update(status="covered", checked=True, java="compat.python.PythonLlamaDiskCache." + methods[method],
                   pythonExactParity="not-certified", contractEvidence="docs/python-cache-contracts.json",
                   reason="完整控制流程经固定原版存储端口对照，包含 pop 删除、日志分片、失败后状态及淘汰时序；diskcache 物理存储由端口提供，SQLite 体积/键序/pickle 继续核实")
states = collections.Counter(row["status"] for row in audit["functions"])
llamaStates = collections.Counter(row["status"] for row in audit["functions"] if row["file"] == "llama_cpp/llama.py")
summary = audit["summary"]
summary.update(boundCount=states["bound"], coveredCount=states["covered"],
               checkedCount=states["bound"] + states["covered"], partialCount=states["partial"],
               missingCount=states["missing"], exactParityCertifiedCount=0,
               uncheckedCount=states["partial"] + states["missing"])
publicLlama = [row for row in audit["functions"]
               if row["file"] == "llama_cpp/llama.py" and row["qualname"].startswith("Llama.")
               and row["qualname"].count(".") == 1 and not row["qualname"].split(".")[1].startswith("_")]
summary.update(publicLlamaMembers=len(publicLlama), publicLlamaCovered=sum(row["checked"] for row in publicLlama))
summary.update(sameNamedNativeBindings=states["bound"] + 2, javaNativeBindingCount=states["bound"] + 2 + 9)
assert sum(states.values()) == 751
auditPath.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

readmePath = root / "README.md"
readme = readmePath.read_text(encoding="utf-8")
start, oldList = readme.split("### 全量函数清单", 1)
start = re.sub(r"Java 已声明其中 \d+ 个同名入口；上下文结构相关 2 项列为部分覆盖，其余 \d+ 项标记绑定完成。",
               f"Java 已声明其中 {summary['sameNamedNativeBindings']} 个同名入口；上下文结构相关 2 项列为部分覆盖，其余 {states['bound']} 项标记绑定完成。", start)
start = re.sub(r"高层 `Llama` 有[^\n]*",
               f"高层 `Llama` 有 {summary['publicLlamaMembers']} 个普通公开命名成员（含 4 个属性 getter），其中 {summary['publicLlamaCovered']} 项已有查询或窄范围辅助能力；构造、生成、聊天、分词等继续逐项对照。构造器、`__call__`、析构、私有辅助成员在下表另行完整列出。", start)
start = re.sub(r"\| 已覆盖独立[^\n]*", f"| 已覆盖独立能力（含聊天、缓存、处理器及 Llama 辅助方法） | {states['covered']} |", start)
start = re.sub(r"\| 已有完整同名 JNA 绑定[^\n]*", f"| 已有完整同名 JNA 绑定 | {states['bound']} |", start)
start = re.sub(r"\| 本清单打勾项合计[^\n]*", f"| 本清单打勾项合计 | {summary['checkedCount']} / 751（{summary['checkedCount'] / 751:.2%}） |", start)
start = re.sub(r"\| 部分覆盖，保持未勾选[^\n]*", f"| 部分覆盖，保持未勾选 | {states['partial']} |", start)
start = re.sub(r"\| 当前缺少明确对应迁移项[^\n]*", f"| 当前缺少明确对应迁移项 | {states['missing']} |", start)
marker = "### 静态聊天函数逐项移植"
if marker in start:
    start = start.split(marker)[0]
start = start.rstrip() + f"""

### 静态聊天函数逐项移植

本批移植 37 项：23 个静态 formatter、9 个共用辅助函数、1 个 GGUF 模板识别函数和 4 个 completion 转换函数。Java 入口为 `compat.python.PythonChatFormats`、`PythonChatCompletions`，返回 prompt/stop 或保留原版字段及 null 的可变 Map。流式转换采用惰性 Iterator，首个 role chunk 先于 choices 访问，终止 delta 为空字典。Gemma 的原版 debug 消息通过显式监听器接收。

固定原版完整函数体及 CPython repr 产生 {contracts['caseCount']:,} 条对照样例，比较 UTF-16 内容（包括未配对代理字符）、空值、角色过滤、异常类别/原文、字典顺序、debug 日志、数值和 Unicode 边界及出错前已产出的流式块。来源的 25 个文件均重新验证 Git blob SHA-1 / SHA-256，并用 AST 复核 786 个定义、751 个去重项。生成脚本已纳入版本控制，见 [本批验证记录](docs/PYTHON_CHAT_COMPATIBILITY.md)。

清单尚未打勾 {summary['uncheckedCount']} 项，其中部分覆盖 {states['partial']} 项、尚无对应实现 {states['missing']} 项。所有 751 项继续保留全输入一致性待验收状态。

### RAM 缓存、状态与处理器逐项移植

本批新增 12 项：RAM 缓存 6 项、最长 token 前缀 1 项、LlamaState 构造 1 项、logits/停止列表 2 项及最小 token 处理器 2 项。{stateContracts['caseCount']} 组原版完整函数体对照覆盖状态与缓存操作序列、任意精度容量/大小、空键、前缀并列、命中后排序、负索引、原地修改和列表回调时序。见 [本批记录](docs/PYTHON_STATE_COMPATIBILITY.md)。

### llama.py 状态访问与数值辅助方法（2026-10-28）

本轮迁移 8 项：`Llama._input_ids`、`_scores`、`eval_tokens`、`eval_logits`、`set_cache`、`set_seed`、`reset` 和 `logits_to_logprobs`，Java 入口为 `compat.python.PythonLlama`。它接收调用方已求值的 intc/single 缓冲，提供可写共享视图、按原版容量截取的独立 List 快照、缓存引用及任意精度种子赋值，并保留 reset 的状态更新和 recurrent/hybrid 内存回调顺序。

log-softmax 支持 single 向量及矩形二维数组的 axis 0/1/-1/-2；固定原版的 {llamaContracts['caseCount']} 组对照覆盖切片、共享修改、快照、种子、重置分支与异常时序、数值边界。浮点对照采用绝对与相对 `4e-6` 容差。完整 `Llama` 构造、eval/generate 连接、Python deque 的后续变更操作、多维 NumPy 接口及数值警告列入后续范围。接口示例、8 项映射和验证结果见 [本轮记录](docs/PYTHON_LLAMA_COMPATIBILITY.md)。

### llama.py 与 LlamaTokenizer 分词链路（2026-10-28，第二轮）

本轮迁移 8 项：Llama 的 tokenize/detokenize/tokenizer，以及 LlamaTokenizer 的构造、tokenize/detokenize/encode/decode。入口为 `PythonLlama`、`PythonLlamaTokenizer`，支持原始 bytes、两层不同的 special 默认值、UTF-8 errors=ignore、自定义分词器及每次新建的模型包装器。原版 `{tokenizerContracts['caseCount']}` 组对照核对参数、调用轨迹、引用和返回内容。

`PythonLlamaTokenizer.modelFromVocab(model.vocab())` 可接入现有模型；字节反分词保留原版 32 字节 piece 缓冲及 BOS 首空格规则。原生依赖继续锁定 b11669，固定 Python vendor revision 的全模型分词对照列入后续范围。8 项映射、用法和验证结果见 [分词迁移记录](docs/PYTHON_TOKENIZER_COMPATIBILITY.md)。

### llama.py 全文件映射与执行链路（2026-10-28，第三轮）

已为 llama.py 全部 49 个定义项建立 [函数与异常映射](docs/PYTHON_LLAMA_MAPPING.md)，覆盖 4 个嵌套函数和 23 处显式 raise/assert/警告站点。机器清单保存完整签名、Java 去向、触发分支、异常类别、调用依赖和实现状态；计划中的实现继续单独标注。

本轮补齐 eval、save_state、load_state、token_nl、pooling_type 和 close 的窄范围实现，并接入 sample/generate 的执行状态机。{executionContracts['caseCount']} 组固定原版对照核对批处理、数组广播、错误后的状态、诊断顺序、临时采样器引用、前缀复用、send、停止条件和草稿路径；原生测试验证实际模型求值、保存恢复及继续生成。新增 7 项 b11669 C 入口，接口保持 JNA 类型封装。

采样、embedding、completion/chat 转发、配置重建及仓库匹配已有实现；2026-10-29 已同步机器映射。services {supplementCounts['services']}、completion {supplementCounts['completion']}、configuration/repository {supplementCounts['configuration']} 组原版对照，共 {sum(supplementCounts.values())} 组。保留完整 Jinja2、初始化失败状态、动态类型、缓存时序及同版本原生推理对照。验证结果见 [缓存记录](docs/PYTHON_CACHE_COMPATIBILITY.md)，用法和既有记录见 [执行链路记录](docs/PYTHON_EXECUTION_COMPATIBILITY.md)。

### llama_cache.py 缓存补充（2026-10-29，2026-10-30 补齐）

18 项定义已全部覆盖：RAM 6 项、基础类 6 项与磁盘缓存 6 项。基础类迁移 ABC 实例化规则——缺实现的类构造时按原版排序名单抛 TypeError，4 个 abstractmethod 基类方法体显式调用抛 NotImplementedError。磁盘控制流程经固定原版存储端口对照，diskcache 物理存储由端口提供；SQLite 体积、pickle 和实际键排序继续核实。详见 [缓存迁移记录](docs/PYTHON_CACHE_COMPATIBILITY.md)。

### _logger、_warn_deprecated 与小成员（2026-10-30，第二轮）

本轮迁移 20 项部分覆盖函数：llama_cache 基础类抽象方法 4 项、LlamaDiskCache 6 项、_logger 的 llama_log_callback/set_verbose 2 项、llama_cpp 的 _warn_deprecated 及两个上下文入口 3 项、llama_tokenizer 的 from_ggml_file 1 项、llama.py 的 ctx/model/__del__/free_lora_adapter 4 项。新增 `PythonLlamaLogger`（GGML 级别映射、CONT 沿用、logger.level 门控、stderr 分写）与 `PythonLlamaTokenizer.fromGgmlFile`；`PythonLlamaCache` 迁移 ABC 规则，`ctx`/`model` 属性按原版直读句柄。`llama_context_default_params`/`llama_init_from_model` 随 b11669 的 168 字节上下文结构对齐后列为绑定完成。固定原版对照新增 {json.loads((root / 'docs/python-lowlevel-contracts.json').read_text(encoding='utf-8'))['caseCount']} 组低层用例及缓存 ABC 场景，核对过滤器动作、调用方模块、释放失败保留引用与工厂 kwargs。见 [本轮记录](docs/PYTHON_LOWLEVEL_COMPATIBILITY.md)。

### llama_chat_format.py 处理器与辅助函数（2026-11-01，第八轮）

本轮迁移 llama_chat_format.py 剩余 31 项：_grammar_for_json/_schema/_response_format（llama_grammar 端口，schema 失败按 fallback_to_json 回落）、HF 两组 formatter/handler 封装（AutoTokenizer 端口 use_default_system_prompt=False 加 apply_chat_template(tokenize=False,**kwargs)；tokenizer_config 版在构造期建 ImmutableSandboxedEnvironment、调用期 render，add_generation_prompt 默认追加空 assistant）、_convert_completion_to_chat_function 与流式生成器（call_ 前缀 id、tool_calls/function_call 双写、首帧空 delta 与末帧 finish_reason）、functionary 两组嵌套 helper（generate_type_definition 的 /Array/object/enum 分支、v1/v2 两套缩进与尾换行差异、prepare_messages_for_inference 的 functions. 前缀/SYSTEM_MESSAGE 注入与 message_to_str 全分支、v1v2 get_grammar 的 suppress+json_schema_to_gbnf 双调用+print）、媒体消息辅助（get_image_urls、split_text_on_image_urls 的 substrs 顺序优先语义、image_url 转 media_marker、_decode_token_piece、Qwen25VL __call__ 前置清状态+stderr 提示+super 调用）。固定原版对照 {chatHandlersContracts['caseCount']} 组。见 [本轮记录](docs/PYTHON_CHATHANDLERS_COMPATIBILITY.md)。

### _internals.py 收尾：TokenDataArray、SamplingContext、CustomSampler 与采样器补全（2026-11-01，第七轮）

本轮迁移剩余 29 项，`_internals.py` 至此 **120 项全覆盖**：`LlamaTokenDataArray` 2 项（recarray 式 id/logit/p 存储经 `candidatesData`/`tokenDataArray`/`candidatesStore`/`candidatesAssign` 端口，copy_logits 重置 id/p/sorted/size）、`LlamaSamplingContext` 6 项（reset 重绑列表+grammar.reset、cp 共享标量复制列表、last/prev_str[-n:] 反 detokenize、sample 弃用 NIE、accept 追加）、`CustomSampler` 4 项（sampler_i 六槽位按序赋值、可选槽位 literal 0、sampler.iface/ctx 指针装配、get_sampler 取指针）、`LlamaSampler` 17 项（typical/temp_ext/xtc/top_n_sigma/mirostat/mirostat_v2/grammar/grammar_lazy_patterns/dry/logit_bias/infill/custom/accept/free_sampler/__del__/clone 及 clone.free_sampler——clone 对 custom samplers 抛 NIE 原文，克隆体注册无移除循环的 free）。新增 JNA 绑定 8 个（temp_ext/xtc/top_n_sigma/grammar_lazy_patterns/dry/logit_bias/infill/sampler_clone）及 `LlamaSamplerApply` 回调与 `NativeSamplerI`/`NativeLlamaSampler` 结构。固定原版对照增至 {internalsContracts['caseCount']} 组。见 [本轮记录](docs/PYTHON_INTERNALS_COMPATIBILITY.md)。

### _internals.py 访问器、KV/state/encode 与批次追加（2026-11-01，第六轮）

本轮迁移 53 项未覆盖函数：`LlamaModel` 17 项（vocab_type、rope_freq_scale_train、token_get_text/score/attr、七个特殊 token、add_bos/eos_token、get_tensor 的 NotImplementedError、free_model/__del__ 闭包）、`LlamaContext` 31 项（free_ctx/__del__、pooling_type、kv_cache_seq_rm/cp/keep/shift——其中 shift 经 `llama_memory_seq_add`、负 seq_id 夹取与 None memory 断言原文、state 大小、encode 返回码报错、set_n_threads、logits/embeddings 五个指针入口、reset/print_timings、14 项 deprecated NotImplementedError）、`LlamaBatch` 4 项（free_batch/__del__、n_tokens、add_sequence 位置重起与末位 logits）及模块级 `normalize_embedding`。`LlamaLibrary` 新增 15 个 b11669 JNA 绑定，`PythonInternalsPort`/`PythonNativeInternals` 对应扩展。固定原版对照增至 {internalsContracts['caseCount']} 组，核对断言门控、seq_id 夹取、encode 报错、指针入口与 deprecated 原文。见 [本轮记录](docs/PYTHON_INTERNALS_COMPATIBILITY.md)。

### _internals.py 模型、上下文、批次与采样器（2026-10-30）

本轮迁移 29 项部分覆盖函数：`_internals.LlamaModel` 8 项、`LlamaContext` 5 项、`LlamaBatch` 4 项、`LlamaSampler` 12 项。Java 入口为 `compat.python.PythonLlamaModel`、`PythonLlamaContext`、`PythonLlamaBatch`、`PythonInternalsSampler`，统一经 `PythonInternalsPort` 访问 C 入口，原生实现为 `PythonNativeInternals`（b11669）。固定原版完整函数体在追踪原生端口上产出 {internalsContracts['caseCount']} 组对照，核对调用顺序、固定 1024/32 字节缓冲、tokenize 负数重试、detokenize BOS 首空格、metadata 扩容、llama_decode 返回码报错、ExitStack 关闭顺序、批次字段逐元素写入及采样器链添加时序。见 [本轮记录](docs/PYTHON_INTERNALS_COMPATIBILITY.md)。

### _utils、tokenizer 基类/HF、speculative 与 server/errors（2026-11-01）

本轮迁移 20 项：`_utils` 的 suppress_stdout_stderr 三项与 Singleton 两项、llama_tokenizer 的 BaseLlamaTokenizer 与 LlamaHFTokenizer 六项、llama_speculative 四项、server/errors 五项。新增 `PythonAbstract` 共享 ABC 实例化检查（PythonLlamaCache 已复用）、`PythonSuppressStdoutStderr` 的 os/sys/null 文件端口、`PythonSingleton` 按类注册表、`PythonBaseLlamaTokenizer`/`PythonHfTokenizer`（errors=ignore 编解码与 prev_tokens 前缀差分）、`PythonPromptLookupDecoding` 滑窗查找、`PythonServerErrors` 错误格式化与路由包装端口。固定原版对照 {json.loads((root / 'docs/python-helpers-contracts.json').read_text(encoding='utf-8'))['caseCount']} 组，核对 fd 操作顺序、单例缓存与异常传播、transformers 导入失败原文、消息模板与 stderr 分段。见 [本轮记录](docs/PYTHON_HELPERS_COMPATIBILITY.md)。

### server 模块级函数与 LlamaProxy（2026-11-01）

本轮迁移 20 项：`server/cli.py` 的 7 个 argparse/pydantic 帮助函数（注入 typing 注解端口与 parser/model 端口，含最小 JSON 解析器与 JSONDecodeError）、`settings.set_dynamic_defaults`（cpu_count 端口）、`app.py` 模块级 6 项（全局赋值、单次 yield、双锁 get_llama_proxy 顺序、logit_bias tokenize 遍历）、`model.py` LlamaProxy 容器 6 项（默认模型加载、切换关闭旧模型、dump/validate/iter/free）。固定原版对照 {json.loads((root / 'docs/python-server-contracts.json').read_text(encoding='utf-8'))['caseCount']} 组，核对 add_argument 调用序列、锁顺序与关闭时机。见 [本轮记录](docs/PYTHON_SERVER_COMPATIBILITY.md)。

### chat_format 注册表/转换器、app 端点与 ext 装饰器（2026-11-01）

本轮迁移 21 项：`llama_cpp_ext._ctypes_function_from_names` 两项（逐名 getattr 缺失收集、命中赋值 argtypes/restype、wraps、全缺 AttributeError 原文）、chat_format 注册表 7 项与两个装饰器工厂（类属性共享 handler 表、单例查询、自定义 NotFound 异常与合法名单 repr）、两个 Protocol 省略号方法体、`strftime_now`/`tojson`（新增 `PythonJson` 复刻 dumps 参数组与浮点 repr）、`chat_formatter_to_chat_completion_handler` 及嵌套 handler（stop 合并、response_format grammar、tool_choice 查找 ValueError、grammar 失败回退、create_completion 全参数）、app.py 五个端点（authenticate/tokenize/detokenize/count/get_models）。固定原版对照 {json.loads((root / 'docs/python-chatregistry-contracts.json').read_text(encoding='utf-8'))['caseCount']} 组，核对 add_argument 式调用序列与 kwargs 组装。见 [本轮记录](docs/PYTHON_CHAT_COMPATIBILITY.md)。

### 测试数据管理

本轮补齐 `from_pretrained` 的 Windows 字符串仓库匹配路径：保留无效范围、字面括号、仓库外路径异常原文及各次下载请求的独立性。配置/仓库原版对照现为 {json.loads((root / 'docs/python-configuration-contracts.json').read_text(encoding='utf-8'))['caseCount']} 组。llama.py 已测窄范围能力 **{llamaStates['covered']}/49（{llamaStates['covered']/49:.2%}）**，部分实现 {llamaStates['partial']} 项；全函数行为一致验收继续进行。

`src/test/resources/` 全部由 `.gitignore` 忽略，测试数据及 Base64 样例保留本地。生成脚本、固定来源和小型摘要元数据纳入版本控制。清洁检出后先使用 [生成说明](tools/python_compat/README.md) 恢复六组数据，再执行 Maven 验证；`AGENTS.md` 已记录后续维护规则。

"""
parts = ["### 全量函数清单\n\n"]
for source in audit["baseline"]["files"]:
    file = source["path"]
    rows = [row for row in audit["functions"] if row["file"] == file]
    parts.append(f"#### `{file}` — {len(rows)} 项，勾选 {sum(r['checked'] for r in rows)}，部分 {sum(r['status'] == 'partial' for r in rows)}\n\n")
    if not rows:
        parts.append("本文件没有显式函数/方法定义，导入、类型声明和库对象加载不增加函数数。\n\n")
        continue
    for row in rows:
        mark = "x" if row["checked"] else " "
        label = {"bound": "绑定完成", "covered": "能力已覆盖", "partial": "部分覆盖", "missing": "未迁移"}[row["status"]]
        tags = "；嵌套函数" if row["nested"] else ""
        if len(row["definitionLines"]) > 1:
            tags += "；同名定义行 " + "/".join(map(str, row["definitionLines"]))
        detail = (" → `" + row["java"] + "`；" + row["reason"]) if row["java"] else ""
        parts.append(f"- [{mark}] [`{row['id']}`]({row['source']}) — {label}{tags}{detail}。\n")
    parts.append("\n")
readmePath.write_text(start + "".join(parts).rstrip() + "\n", encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
