"""Execute hash-verified 0.3.36 functions and persist cross-language chat contracts.

Usage: conda run -n base python tools/python_compat/generate_chat_contracts.py \
    --source-root .tools/python-api-audit
No native model, package import, or rewritten Python implementation is used.
"""

from contract_wire import wire

import argparse
import ast
import base64
import collections
import dataclasses
import hashlib
import json
import pathlib
import random
import struct
import sys
import types
import unicodedata

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
assert sys.version_info[:2] == (3, 13), "Use the pinned CPython 3.13 contract runtime"
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
definitions = []
for item in audit["baseline"]["files"]:
    data = (args.source_root / item["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"], item["path"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == item["sha"]
    tree = ast.parse(data)

    def visit(nodes, prefix=""):
        for node in nodes:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                name = prefix + node.name
                if not isinstance(node, ast.ClassDef):
                    definitions.append((item["path"], name))
                visit(node.body, name + ".")
            else:
                for _, value in ast.iter_fields(node):
                    if isinstance(value, list):
                        visit([v for v in value if isinstance(v, ast.stmt)], prefix)
    visit(tree.body)
assert len(definitions) == audit["summary"]["definitionCount"] == 786
assert len(set(definitions)) == audit["summary"]["uniqueFunctionCount"] == 751
assert set(definitions) == {(row["file"], row["qualname"]) for row in audit["functions"]}

source = args.source_root / "llama_cpp/llama_chat_format.py"
tree = ast.parse(source.read_text(encoding="utf-8"))
names = ["_get_system_message", "_map_roles", "_format_llama2", "_format_add_colon_single",
         "_format_add_colon_two", "_format_no_colon_single", "_format_add_colon_space_single",
         "_format_chatml", "_format_chatglm3", "guess_chat_format_from_gguf_metadata",
         "_convert_text_completion_logprobs_to_chat", "_convert_text_completion_to_chat",
         "_convert_text_completion_chunks_to_chat", "_convert_completion_to_chat"]
formatters = {}
logs = []
namespace = {"__name__": "upstream_chat_contracts", "dataclasses": dataclasses,
             "logger": types.SimpleNamespace(debug=logs.append)}
sys.modules[namespace["__name__"]] = types.ModuleType(namespace["__name__"])
selected = [ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)]
constants = ["CHATML_CHAT_TEMPLATE", "MISTRAL_INSTRUCT_CHAT_TEMPLATE",
             "MIXTRAL_INSTRUCT_CHAT_TEMPLATE", "LLAMA3_INSTRUCT_CHAT_TEMPLATE"]
for node in tree.body:
    if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in constants for t in node.targets):
        selected.append(node)
    if isinstance(node, ast.ClassDef) and node.name == "ChatFormatterResponse":
        selected.append(node)
    if isinstance(node, ast.FunctionDef):
        for decorator in node.decorator_list:
            if isinstance(decorator, ast.Call) and isinstance(decorator.func, ast.Name) and decorator.func.id == "register_chat_format":
                formatters[decorator.args[0].value] = node.name
                names.append(node.name)
        if node.name in names:
            node.decorator_list = []
            selected.append(node)
exec(compile(ast.fix_missing_locations(ast.Module(body=selected, type_ignores=[])), str(source), "exec"), namespace)
assert len(formatters) == 23




rows = []
counts = collections.Counter()


def record(kind, argument, operation, stream=False):
    logs.clear()
    yielded = []
    try:
        value = operation()
        if stream:
            for part in value:
                yielded.append(part)
            value = yielded
        if dataclasses.is_dataclass(value):
            value = dataclasses.asdict(value)
        result = {"value": value, "logs": list(logs)}
    except Exception as error:
        result = {"error": type(error).__name__, "message": str(error), "yielded": yielded, "logs": list(logs)}
    rows.append("\t".join([kind, base64.b64encode(wire(argument)).decode(), base64.b64encode(wire(result)).decode()]))
    counts[kind] += 1


messageCases = [[], [{"role": "user", "content": "hello"}],
                [{"role": "system", "content": "first"}, {"role": "system", "content": "second"}],
                [{"role": "assistant", "content": "reply"}, {"role": "user", "content": "ask"}],
                [{"role": "system"}, {"role": "user"}], [{"role": "user"}, {"role": "system"}],
                [{}], [None], ["text"], [[]], [0], [{"content": "missing role"}],
                [{"role": "tool"}], [{"role": None}], [{"role": []}], [{"role": {}}],
                [{"role": True}], [{"role": 1}]]
contents = [None, "", " ", "\n", "中文🙂\0\r\n", "'\"\\", "<|im_end|>[INST]",
            "\u001c\u0085\u00a0\u2007\u2028\u202f\u3000", "\ud800x\udfff", 0, False, [], {},
            [{"type": "text", "text": "image"}], True, 7, {"text": "part"}]
for role in ["system", "user", "assistant", "tool", "bot", "User", "Assistant"]:
    for content in contents:
        messageCases.append([{"role": role, "content": content}])
rng = random.Random(336)
for _ in range(48):
    messageCases.append([{"role": rng.choice(["system", "user", "assistant", "bot", "tool", "User", "Assistant"]),
                          "content": rng.choice(contents)} for _ in range(rng.randrange(2, 12))])
for name, function in formatters.items():
    for messages in messageCases:
        record("format:" + name, messages, lambda: namespace[function](messages=messages, ignored=True))

# Python formatting of dynamically typed system content also uses float/Unicode repr.
reprCases = [0.0, -0.0, float("inf"), float("-inf"), float("nan"), 1e-4, 1e-5,
             1e15, 1e16, 1e23, 5e-324, 1.7976931348623157e308]
for _ in range(512):
    reprCases.append(struct.unpack(">d", rng.getrandbits(64).to_bytes(8, "big"))[0])
printableRanges = []
rangeStart = None
for cp in range(0x110001):
    printable = cp <= 0x10FFFF and chr(cp).isprintable()
    if printable and rangeStart is None:
        rangeStart = cp
    if not printable and rangeStart is not None:
        printableRanges.extend([rangeStart, cp - 1])
        for edge in [rangeStart - 1, rangeStart, cp - 1, cp]:
            if 0 <= edge <= 0x10FFFF:
                reprCases.append(chr(edge))
        rangeStart = None
for value in reprCases:
    record("pythonRepr", value, lambda: repr(value))
for value in reprCases[:524]:
    messages = [{"role": "system", "content": value}]
    record("format:qwen", messages, lambda: namespace["format_qwen"](messages=messages))

for messages in messageCases:
    record("_get_system_message", messages, lambda: namespace["_get_system_message"](messages))
    roleMap = {"system": "S", "user": "U", "assistant": "A", "bot": "B"}
    record("_map_roles", [messages, roleMap], lambda: namespace["_map_roles"](messages, roleMap))
helpers = [name for name in names if name.startswith("_format_")]
for name in helpers:
    for _ in range(48):
        system = rng.choice(["", "system", "中文🙂\0\n"])
        pairs = [(rng.choice(["", "U", "A", "\n:"]), rng.choice([None, "", " ", "\n", "中文🙂\0"]))
                 for _ in range(rng.randrange(10))]
        arguments = [system, pairs, rng.choice(["", "\n", "</s>"])]
        if name in ["_format_llama2", "_format_add_colon_two"]:
            arguments.append(rng.choice(["", "\n", "<end>"]))
        record(name, arguments, lambda: namespace[name](*arguments))

for metadata in [{}, {"tokenizer.chat_template": None}, {"tokenizer.chat_template": ""},
                 {"tokenizer.chat_template": "other"}] + [
                     {"tokenizer.chat_template": namespace[name]} for name in constants] + [
                     {"tokenizer.chat_template": namespace[name] + "\n"} for name in constants]:
    record("guess_chat_format_from_gguf_metadata", metadata,
           lambda: namespace["guess_chat_format_from_gguf_metadata"](metadata))

logprobCases = [None, {}, {"tokens": []}, {"tokens": None, "token_logprobs": [], "top_logprobs": []}]
for length in range(4):
    for probLength in range(4):
        for topLength in range(4):
            logprobCases.append({"tokens": ["中", "🙂", "\0", "end"][:length],
                                 "token_logprobs": [None, -1.25, 0.0, float("-inf")][:probLength],
                                 "top_logprobs": [{"first": -1.5, "second": -2.0}, {}, {"x": None}][:topLength]})
for top in [None, 1, "value", [], [1]]:
    logprobCases.append({"tokens": ["x"], "token_logprobs": [0.0], "top_logprobs": [top]})
for tokens in ["中🙂", {"first": 1, "second": 2}]:
    logprobCases.append({"tokens": tokens, "token_logprobs": [-0.5, -1.0], "top_logprobs": [{}, {}]})
for value in logprobCases:
    record("_convert_text_completion_logprobs_to_chat", value,
           lambda: namespace["_convert_text_completion_logprobs_to_chat"](value))

completionCases = [{}, {"usage": None}, {"usage": None, "id": None}]
for logprobs in logprobCases:
    for reason in [None, "stop", "length"]:
        completionCases.append({"id": "cmpl-中文🙂", "created": 123, "model": "test",
                                "choices": [{"index": 4, "text": "\0 hello\n", "logprobs": logprobs,
                                             "finish_reason": reason}],
                                "usage": {"prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5}})
base = completionCases[3]
for key in base:
    completionCases.append({k: v for k, v in base.items() if k != key})
for choices in [[], [{}], [{"text": None}], None]:
    completionCases.append({**base, "choices": choices})
for completion in completionCases:
    for kind in ["_convert_text_completion_to_chat", "_convert_completion_to_chat:false"]:
        record(kind, completion, lambda: namespace["_convert_text_completion_to_chat"](completion)
               if kind.endswith("_to_chat") else namespace["_convert_completion_to_chat"](completion, stream=False))

streamCases = [[], [base], [base, {**base, "id": "next", "choices": [{"finish_reason": "stop", "logprobs": None}]}],
               [{"id": "first", "created": 1, "model": "model", "choices": []}], [{}]]
for completion in completionCases:
    streamCases.append([completion])
    streamCases.append([base, completion])
for chunks in streamCases:
    for kind in ["_convert_text_completion_chunks_to_chat", "_convert_completion_to_chat:true"]:
        record(kind, chunks, lambda: namespace["_convert_text_completion_chunks_to_chat"](iter(chunks))
               if kind.endswith("chunks_to_chat") else namespace["_convert_completion_to_chat"](iter(chunks), stream=True), stream=True)

fixture = root / "src/test/resources/python-0.3.36-chat-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
manifest = {"pythonPackage": audit["baseline"]["version"], "sourceCommit": audit["baseline"]["commit"],
            "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(), "fixtureRuntime": sys.version,
            "uniqueFunctions": len(set(names)), "caseCount": len(rows), "casesByOperation": dict(counts),
            "unicodeVersion": unicodedata.unidata_version,
            "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "method": "Execute complete hash-verified upstream function bodies, stripping registration decorators; capture returns, errors, debug messages, and yielded prefixes. Also execute CPython repr for float and Unicode boundaries. Wire strings preserve UTF-16 code units."}
(root / "docs/python-chat-contracts.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# Emit only the literal template constants, independently of the Java implementation.
java = "package io.github.yaulun.javacpp.compat.python;\n\n"
java += "/** Exact template literals used by the pinned upstream metadata lookup. */\n"
java += "final class PythonChatTemplateConstants {\n  private PythonChatTemplateConstants() {}\n"
for name in constants:
    java += "  static final String " + name + " = " + json.dumps(namespace[name], ensure_ascii=True) + ";\n"
java += "}\n"
(root / "src/Java_Cpp/io/github/yaulun/javacpp/compat/python/PythonChatTemplateConstants.java").write_text(java, encoding="utf-8")
java = "package io.github.yaulun.javacpp.python;\n\n"
java += "/** CPython 3.13 Unicode " + unicodedata.unidata_version + " printable ranges for repr. */\n"
java += "final class PythonUnicode {\n  private PythonUnicode() {}\n"
java += "  private static final int[] printableRanges = {\n"
for offset in range(0, len(printableRanges), 12):
    java += "    " + ", ".join(hex(cp) for cp in printableRanges[offset:offset + 12]) + ",\n"
java += "  };\n  static boolean printable(int cp) {\n"
java += "    int low = 0, high = printableRanges.length / 2;\n"
java += "    while (low < high) {\n      int mid = (low + high) >>> 1;\n"
java += "      if (cp < printableRanges[2 * mid]) high = mid;\n"
java += "      else if (cp > printableRanges[2 * mid + 1]) low = mid + 1;\n"
java += "      else return true;\n    }\n    return false;\n  }\n}\n"
(root / "src/Java_Cpp/io/github/yaulun/javacpp/python/PythonUnicode.java").write_text(java, encoding="utf-8")
print(json.dumps({"verifiedSourceFiles": 25, "uniqueInventoryFunctions": 751,
                  "portedFunctions": len(set(names)), "caseCount": len(rows)}, indent=2))
