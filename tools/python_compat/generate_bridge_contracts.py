"""Execute pinned upstream Python branches using native stubs and generate golden contracts."""

import argparse
import ast
import hashlib
import base64
import contextlib
import ctypes
import json
import pathlib
import sys
import types
import warnings

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
cache = args.source_root
assert sys.version_info[:2] == (3, 13), "Use the pinned CPython 3.13 contract runtime"
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for item in audit["baseline"]["files"]:
    data = (cache / item["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"], item["path"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == item["sha"]
rows = []

def encoded(value):
    data = value if isinstance(value, bytes) else str(value).encode("utf-8")
    return base64.b64encode(data).decode("ascii")

def record(kind, argument, number, category, message):
    rows.append("\t".join([kind, encoded(argument), str(number), category, encoded(message)]))

def function(file, owner, name):
    tree = ast.parse((cache / file).read_text(encoding="utf-8"))
    body = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == owner).body if owner else tree.body
    node = next(node for node in body if isinstance(node, ast.FunctionDef) and node.name == name)
    node.decorator_list = []
    return node

def compile_function(node, namespace):
    future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
    tree = ast.fix_missing_locations(ast.Module(body=[future, node], type_ignores=[]))
    exec(compile(tree, "upstream-0.3.36-extracted", "exec"), namespace)
    return namespace[node.name]

def capture(kind, argument, number, operation):
    try:
        operation()
    except (ValueError, RuntimeError, AssertionError) as failure:
        record(kind, argument, number, type(failure).__name__, str(failure))
    else:
        raise AssertionError("Expected upstream failure: " + kind)

load = function("llama_cpp/_internals.py", "LlamaModel", "__init__")
for kind, exists, model, vocab in [
    ("missingModel", False, None, None),
    ("nullModel", True, None, None),
    ("nullVocab", True, object(), None),
]:
    library = types.SimpleNamespace(llama_model_load_from_file=lambda *args: model,
        llama_model_get_vocab=lambda *args: vocab)
    namespace = {"ExitStack": contextlib.ExitStack,
        "os": types.SimpleNamespace(path=types.SimpleNamespace(exists=lambda path: exists)),
        "suppress_stdout_stderr": lambda **kwargs: contextlib.nullcontext(), "llama_cpp": library}
    call = compile_function(load, namespace)
    capture(kind, "audit-model.gguf", 0,
        lambda: call(types.SimpleNamespace(), path_model="audit-model.gguf", params=object(), verbose=False))

sample = compile_function(function("llama_cpp/llama.py", "Llama", "sample"), {})
capture("emptySample", "", 0, lambda: sample(types.SimpleNamespace(n_tokens=0)))

create = compile_function(function("llama_cpp/_internals.py", "LlamaContext", "__init__"),
    {"ExitStack": contextlib.ExitStack, "llama_cpp": types.SimpleNamespace(llama_init_from_model=lambda *args: None)})
capture("contextNull", "", 0, lambda: create(types.SimpleNamespace(),
    model=types.SimpleNamespace(model=object()), params=object(), verbose=False))

for code in [-100, -1, 1, 2]:
    decode = compile_function(function("llama_cpp/_internals.py", "LlamaContext", "decode"),
        {"llama_cpp": types.SimpleNamespace(llama_decode=lambda *args: code)})
    capture("decode", "", code,
        lambda: decode(types.SimpleNamespace(ctx=object()), types.SimpleNamespace(batch=object())))

for text in [b"hello", "你好🙂".encode(), b"zero\x00byte", b"'quoted'", b'"quoted"', b"'\"\\\r\n\t\xff"]:
    tokenize = compile_function(function("llama_cpp/_internals.py", "LlamaModel", "tokenize"),
        {"llama_cpp": types.SimpleNamespace(llama_token=ctypes.c_int32, llama_tokenize=lambda *args: -7)})
    capture("tokenize", text, -7,
        lambda: tokenize(types.SimpleNamespace(vocab=object(), n_ctx_train=lambda: 8), text, True, False))

completion = function("llama_cpp/llama.py", "Llama", "_create_completion")
selected = [node for node in completion.body if node.lineno in (1344, 1349, 1354)]
assert len(selected) == 3
probe = ast.FunctionDef(name="completion_contract",
    args=ast.arguments(posonlyargs=[], args=[ast.arg(arg=name) for name in ("self", "prompt_tokens", "max_tokens")],
        kwonlyargs=[], kw_defaults=[], defaults=[]),
    body=selected + [ast.Return(value=ast.Name(id="max_tokens", ctx=ast.Load()))], decorator_list=[])
normalize = compile_function(probe, {"llama_cpp": types.SimpleNamespace(llama_n_ctx=lambda ctx: 32)})
for limit in [None, 0, -7, 1, 26, 27, 28, 4096]:
    result = normalize(types.SimpleNamespace(_n_ctx=32, ctx=object()), [0] * 5, limit)
    record("maxTokens", "", "None" if limit is None else limit, "count", result)
for count in [32, 33]:
    capture("overflow", "", count,
        lambda: normalize(types.SimpleNamespace(_n_ctx=32, ctx=object()), [0] * count, 1))

deprecate = compile_function(function("llama_cpp/llama_cpp.py", None, "_warn_deprecated"), {"warnings": warnings})
with warnings.catch_warnings(record=True) as caught:
    warnings.simplefilter("always")
    deprecate("llama_load_model_from_file", "use llama_model_load_from_file instead")
    record("deprecated", "llama_load_model_from_file\tuse llama_model_load_from_file instead", 0,
        caught[0].category.__name__, str(caught[0].message))

duplicate = next(node for node in ast.walk(completion) if isinstance(node, ast.Call)
    and isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name)
    and node.func.value.id == "warnings" and node.func.attr == "warn")
for bos in ["<s>", "<|begin_of_text|>"]:
    fake = types.SimpleNamespace(token_bos=lambda: 0,
        _model=types.SimpleNamespace(token_get_text=lambda token: bos))
    expression = ast.fix_missing_locations(ast.Expression(body=duplicate))
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        eval(compile(expression, "llama_cpp/llama.py", "eval"), {"warnings": warnings, "self": fake})
        record("duplicateBos", bos, 0, caught[0].category.__name__, str(caught[0].message))

record("warningFormat", "warning 中文", 7, "format",
    warnings.formatwarning("warning 中文", RuntimeWarning, "origin.py", 7, line="  x = call() \n"))
for space in ["\u00a0", "\u0085", "\u202f", "\u3000"]:
    record("warningUnicodeFormat", space, 7, "format",
        warnings.formatwarning("warning", RuntimeWarning, "origin.py", 7, line=space + "x" + space))

for action in ["default", "always", "ignore", "error", "module", "once"]:
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter(action)
        warnings.onceregistry.clear()
        registries = {"pkgA": {}, "pkgB": {}}
        failures = 0
        for module, line in [("pkgA", 1), ("pkgA", 1), ("pkgA", 2), ("pkgB", 1)]:
            try:
                warnings.warn_explicit("test", RuntimeWarning, module + ".py", line,
                    module=module, registry=registries[module])
            except RuntimeWarning:
                failures += 1
        record("warningAction", action, len(caught), "warning-count", failures)

for data in [bytes([number]) for number in range(256)] + [bytes(range(256)), b"'\"", b"'", b'"', b"\n\\\x00\xff"]:
    record("bytesRepr", data, 0, "repr", repr(data))

resources = root / "src/test/resources"
resources.mkdir(parents=True, exist_ok=True)
(resources / "python-0.3.36-contracts.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
metadata = {"pythonPackage": "0.3.36", "sourceCommit": "1652066e0af45f2313b339670ef9555e8a54e545",
    "fixtureRuntime": sys.version, "caseCount": len(rows),
    "fixtureSha256": hashlib.sha256((resources / "python-0.3.36-contracts.tsv").read_bytes()).hexdigest(),
    "method": "Execute original AST branches with explicit native stubs; use CPython warnings/bytes for formatting contracts."}
(root / "docs/python-bridge-contracts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
