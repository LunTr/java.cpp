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
states = collections.Counter(row["status"] for row in audit["functions"])
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

完整构造参数、采样策略链、embedding、completion/chat handler 全流程、配置重建和通用 from_pretrained 在映射表中列出后续目标及异常要求。sample/generate 的完整参数策略及回调输入视图协议继续标为部分实现。用法、验证与适用范围见 [执行链路记录](docs/PYTHON_EXECUTION_COMPATIBILITY.md)。

### 测试数据管理

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
