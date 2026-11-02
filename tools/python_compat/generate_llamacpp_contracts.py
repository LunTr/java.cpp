"""Execute pinned llama_cpp.py deprecation wrappers; result+warning+trace contract."""

import argparse
import ast
import base64
import hashlib
import json
import pathlib
import sys
import warnings

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from contract_wire import wire

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for source in audit["baseline"]["files"]:
    data = (args.source_root / source["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == source["sha256"]

src = (args.source_root / "llama_cpp/llama_cpp.py").read_text(encoding="utf-8")
tree = ast.parse(src)
# Later same-named defs overwrite the ctypes stubs, matching module semantics.
funcs = {n.name: n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)}

WRAPPERS = [
    "llama_load_model_from_file",
    "llama_free_model",
    "llama_new_context_with_model",
    "llama_n_ctx_train",
    "llama_n_embd",
    "llama_n_layer",
    "llama_n_head",
    "llama_n_vocab",
    "llama_get_state_size",
    "llama_copy_state_data",
    "llama_set_state_data",
    "llama_load_session_file",
    "llama_save_session_file",
]

# Python-only compatibility wrappers without a same-named C symbol.
PYONLY = {
    "llama_set_adapter_lora": "llama_set_adapters_lora",
    "llama_apply_adapter_cvec": "llama_set_adapter_cvec",
}

future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)


def drive(case):
    trace = []
    ns = {"warnings": warnings, "__name__": "pinned-llamacpp"}

    def make(alias):
        def stub(*args):
            trace.append([alias] + list(args))
            if case.get("delegate_error"):
                raise RuntimeError(case["delegate_error"])
            return case.get("delegate_result")

        return stub

    for name in WRAPPERS:
        ns["_" + name] = make("_" + name)

    def set_adapters_lora(ctx, adapters, n_adapters, scales):
        trace.append(["llama_set_adapters_lora", ctx,
                      list(adapters), n_adapters, list(scales)])
        if case.get("delegate_error"):
            raise RuntimeError(case["delegate_error"])
        return case.get("delegate_result")

    ns["llama_set_adapters_lora"] = set_adapters_lora
    ns["llama_set_adapter_cvec"] = make("llama_set_adapter_cvec")

    import ctypes
    ns["ctypes"] = ctypes
    ns["llama_adapter_lora_p_ctypes"] = ctypes.c_void_p
    ns["llama_context_p"] = int
    ns["llama_adapter_lora_p"] = int
    ns["CtypesPointerOrRef"] = type("Sub", (), {"__class_getitem__": classmethod(lambda cls, k: k)})

    nodes = [funcs["_warn_deprecated"], funcs[case["func"]]]
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
                 "pinned-llamacpp", "exec"), ns)

    results = []
    for step in case["ops"]:
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter(case.get("action", "always"))
            try:
                results.append(ns[case["func"]](*step))
            except Exception as e:
                results.append(dict(error=type(e).__name__, message=str(e)))
        for w in caught:
            trace.append(["warn", w.category.__name__, str(w.message)])
    return dict(results=results, trace=trace)


cases = [
    dict(func="llama_load_model_from_file", ops=[["models/a.gguf", {"params": 1}]],
         delegate_result="model-ptr"),
    dict(func="llama_load_model_from_file", ops=[["m.gguf", "params"]],
         delegate_error="load failed"),
    dict(func="llama_free_model", ops=[["model0"]], delegate_result=None),
    dict(func="llama_free_model", ops=[["model0"]], action="error"),
    dict(func="llama_free_model", ops=[["model0"], ["model1"]], action="ignore",
         delegate_result=None),
    dict(func="llama_new_context_with_model", ops=[["model0", {"params": 2}]],
         delegate_result="ctx-ptr"),
    dict(func="llama_n_ctx_train", ops=[["model0"]], delegate_result=1024),
    dict(func="llama_n_embd", ops=[["model0"]], delegate_result=4096),
    dict(func="llama_n_layer", ops=[["model0"]], delegate_result=32),
    dict(func="llama_n_head", ops=[["model0"]], delegate_result=16),
    dict(func="llama_n_vocab", ops=[["vocab0"]], delegate_result=32000),
    dict(func="llama_n_vocab", ops=[["vocab0"]], action="error"),
    dict(func="llama_get_state_size", ops=[["ctx0"]], delegate_result=2048),
    dict(func="llama_copy_state_data", ops=[["ctx0", "dst-buf"]], delegate_result=512),
    dict(func="llama_set_state_data", ops=[["ctx0", "src-buf"]], delegate_result=512),
    dict(func="llama_set_adapter_lora", ops=[["ctx0", 4096, 0.5]],
         delegate_result=0),
    dict(func="llama_set_adapter_lora", ops=[["ctx0", 4096, 0.5]], action="error"),
    dict(func="llama_apply_adapter_cvec",
         ops=[["ctx0", "cvec-data", 24, 128, 1, 4]], delegate_result=0),
    dict(func="llama_apply_adapter_cvec",
         ops=[["ctx0", "cvec-data", 24, 128, 1, 4]],
         delegate_error="cvec failed"),
    dict(func="llama_load_session_file",
         ops=[["ctx0", "session.bin", "tokens-out", 32, "count-out"]],
         delegate_result=True),
    dict(func="llama_load_session_file",
         ops=[["ctx0", "session.bin", "tokens-out", 32, "count-out"]],
         delegate_error="io error"),
    dict(func="llama_save_session_file",
         ops=[["ctx0", "session.bin", "tokens", 32]], delegate_result=True),
    dict(func="llama_save_session_file",
         ops=[["ctx0", "session.bin", "tokens", 32]], action="error"),
]

rows = []
for case in cases:
    rows.append("\t".join(["llamacpp", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(drive(case))).decode()]))
fixture = root / "src/test/resources/python-0.3.36-llamacpp-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")

meta = {
    "source": "llama_cpp/llama_cpp.py",
    "cases": len(cases),
    "functions": WRAPPERS + list(PYONLY),
    "note": "ctypes binding declarations are bound via internal.LlamaLibrary JNA; "
            "this contract covers the real-bodied deprecation wrappers that emit "
            "DeprecationWarning before delegating to the same-named raw binding "
            "or the renamed replacement entry.",
}
(root / "docs/python-llamacpp-contracts.json").write_text(
    json.dumps(meta, indent=2) + "\n", encoding="utf-8")
print("wrote", fixture, len(cases), "cases")
