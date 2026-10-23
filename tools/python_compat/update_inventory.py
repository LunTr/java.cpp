"""Update the reviewed chat-function mappings and render the complete pinned inventory."""

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
states = collections.Counter(row["status"] for row in audit["functions"])
summary = audit["summary"]
summary.update(boundCount=states["bound"], coveredCount=states["covered"],
               checkedCount=states["bound"] + states["covered"], partialCount=states["partial"],
               missingCount=states["missing"], exactParityCertifiedCount=0,
               uncheckedCount=states["partial"] + states["missing"])
assert sum(states.values()) == 751
auditPath.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

readmePath = root / "README.md"
readme = readmePath.read_text(encoding="utf-8")
start, oldList = readme.split("### 全量函数清单", 1)
start = re.sub(r"\| 已覆盖独立[^\n]*", f"| 已覆盖独立能力（含静态聊天转换） | {states['covered']} |", start)
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
