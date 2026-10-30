"""Execute pinned low-level bodies (_logger, _warn_deprecated, small llama.py members)."""

import argparse
import ast
import base64
import contextlib
import ctypes
import hashlib
import io
import json
import logging
import pathlib
import sys
import types
import warnings
from contract_wire import wire

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for source in audit["baseline"]["files"]:
    data = (args.source_root / source["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == source["sha256"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == source["sha"]

future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
def execute(nodes, namespace):
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
                 "pinned-lowlevel", "exec"), namespace)

def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))

rows = []

# ---- _logger.py: module body over a stub llama_cpp package --------------------
calls = []
llama_cpp_stub = types.SimpleNamespace(
    llama_log_callback=lambda function: function,
    llama_log_set=lambda *values: calls.append(["llama_log_set", len(values)]))
namespace = dict(sys=sys, ctypes=ctypes, logging=logging, llama_cpp=llama_cpp_stub)
tree = ast.parse((args.source_root / "llama_cpp/_logger.py").read_text(encoding="utf-8"))
execute([node for node in tree.body if not isinstance(node, (ast.Import, ast.ImportFrom))], namespace)
callback, set_verbose = namespace["llama_log_callback"], namespace["set_verbose"]
logger, level_map = namespace["logger"], namespace["GGML_LOG_LEVEL_TO_LOGGING_LEVEL"]

def run_log(argument):
    class Stderr:
        def __init__(self):
            self.writes = []
        def write(self, value):
            self.writes.append(value)
        def flush(self):
            pass
    stderr = Stderr()
    logger.setLevel(argument["level"])
    namespace["_last_log_level"] = level_map[0]
    events = []
    for step in argument["steps"]:
        def operation():
            if step[0] == "callback":
                return callback(step[1], bytes.fromhex(step[2]), None)
            if step[0] == "verbose":
                set_verbose(step[1])
                return None
            raise AssertionError(step)
        with contextlib.redirect_stderr(stderr):
            result = capture(operation)
        events.append(dict(result=result, level=logger.level,
                           last=namespace["_last_log_level"], stderr=list(stderr.writes)))
        stderr.writes.clear()
    return events

log_cases = [
    dict(level=0, steps=[["callback", 1, "696e666f0a"], ["callback", 5, "636f6e74"],
                         ["callback", 0, "63726974"], ["callback", 4, "646267"]]),
    dict(level=0, steps=[["verbose", False], ["callback", 2, "7761726e0a"], ["callback", 3, "6572720a"],
                         ["callback", 0, "63726974"], ["callback", 4, "646267"], ["callback", 5, "636f6e74"]]),
    dict(level=0, steps=[["verbose", True], ["callback", 4, "646267"], ["callback", 5, "636f6e74"],
                         ["verbose", False], ["callback", 1, "696e666f"]]),
    dict(level=0, steps=[["callback", 7, "61"], ["callback", -1, "61"]]),
    dict(level=0, steps=[["callback", 2, "ff"], ["callback", 2, "c2"], ["callback", 2, "c241"],
                         ["callback", 2, "616263e282"], ["callback", 2, "e4b8ade69687"]]),
]
for case in log_cases:
    rows.append("\t".join(["log", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(run_log(case))).decode()]))

# ---- llama_cpp._warn_deprecated: warning filters and caller-module rule -------
tree = ast.parse((args.source_root / "llama_cpp/llama_cpp.py").read_text(encoding="utf-8"))
node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "_warn_deprecated")

def run_warn(argument):
    emitted = []
    namespace = {"warnings": warnings}
    execute([node], namespace)
    # One caller module so its __warningregistry__ persists across the calls.
    warn_ns = {"__name__": argument["module"], "fn": namespace["_warn_deprecated"]}
    exec(compile("def caller():\n    return fn('legacy_symbol', 'use replacement')\n",
                 "caller", "exec"), warn_ns)
    with warnings.catch_warnings(record=True) as recorded:
        warnings.simplefilter(argument["action"])
        results = [capture(warn_ns["caller"]) for _ in range(argument["calls"])]
    for message in recorded:
        emitted.append(dict(category=message.category.__name__, message=str(message.message)))
    return dict(results=results, emitted=emitted)

for action in ["always", "ignore", "error", "once", "module", "default"]:
    for module in ["__main__", "llama_cpp.llama_cpp"]:
        spec = dict(action=action, module=module, calls=2)
        rows.append("\t".join(["warn", base64.b64encode(wire(spec)).decode(),
                               base64.b64encode(wire(run_warn(spec))).decode()]))

# ---- llama.py small members: free_lora_adapter, __del__, ctx, model -----------
tree = ast.parse((args.source_root / "llama_cpp/llama.py").read_text(encoding="utf-8"))
llama_cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Llama")
init = next(n for n in llama_cls.body if isinstance(n, ast.FunctionDef) and n.name == "__init__")
free_lora = next(n for n in ast.walk(init)
                 if isinstance(n, ast.FunctionDef) and n.name == "free_lora_adapter")

def run_lora(argument):
    trace = []
    def free(value):
        trace.append(value)
        if argument["fail"]:
            raise ValueError("free failed")
    self = types.SimpleNamespace(_lora_adapter=argument["adapter"])
    ns = {"llama_cpp": types.SimpleNamespace(llama_adapter_lora_free=free), "self": self}
    execute([free_lora], ns)
    result = capture(ns["free_lora_adapter"])
    return dict(result=result, adapter=self._lora_adapter, trace=trace)

for spec in [dict(adapter=None, fail=False), dict(adapter="P", fail=False), dict(adapter="P", fail=True)]:
    rows.append("\t".join(["lora", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_lora(spec))).decode()]))

dunder = {n.name: n for n in llama_cls.body if isinstance(n, ast.FunctionDef)
          and n.name in ["__del__", "ctx", "model"]}
ns = {}
execute(list(dunder.values()), ns)

def run_dunder(argument):
    trace = []
    self = types.SimpleNamespace(_ctx=types.SimpleNamespace(ctx="CTX"),
                                 _model=types.SimpleNamespace(model="MODEL"),
                                 close=lambda: trace.append("close"))
    if argument["member"] == "__del__":
        return dict(result=capture(lambda: ns["__del__"](self)), trace=trace)
    fn = ns[argument["member"]]
    if isinstance(fn, property): fn = fn.fget
    return dict(result=capture(lambda: fn(self)))

for member in ["ctx", "model", "__del__"]:
    spec = dict(member=member)
    rows.append("\t".join(["dunder", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_dunder(spec))).decode()]))

# ---- llama_tokenizer.LlamaTokenizer.from_ggml_file ----------------------------
tree = ast.parse((args.source_root / "llama_cpp/llama_tokenizer.py").read_text(encoding="utf-8"))
tokenizer_cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "LlamaTokenizer")
members = [n for n in tokenizer_cls.body if isinstance(n, ast.FunctionDef)
           and n.name in ["__init__", "from_ggml_file"]]
trace = []
stub_model = types.SimpleNamespace(marker="internals-model")
def fake_llama(**keywords):
    trace.append(keywords)
    return types.SimpleNamespace(_model=stub_model)
ns = {"llama_cpp": types.SimpleNamespace(Llama=fake_llama), "BaseLlamaTokenizer": object,
      "List": list, "Optional": object}
execute(members, ns)
cls = type("LlamaTokenizer", (object,), {"__init__": ns["__init__"],
                                       "from_ggml_file": ns["from_ggml_file"]})
def run_tokenizer(argument):
    created = cls.from_ggml_file(argument["path"])
    return dict(keywords=list(trace), model=self_model(created))
def self_model(created):
    return "internals-model" if created._model is stub_model else repr(created._model)
for spec in [dict(path="models/t.gguf"), dict(path="模型/a.gguf")]:
    trace.clear()
    rows.append("\t".join(["tokenizer", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_tokenizer(spec))).decode()]))

fixture = root / "src/test/resources/python-0.3.36-lowlevel-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=8,
                functions=["llama_log_callback", "set_verbose", "_warn_deprecated",
                           "LlamaTokenizer.from_ggml_file", "Llama.ctx", "Llama.model",
                           "Llama.__del__", "Llama.__init__.free_lora_adapter"],
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Hash-verified pinned bodies over stub ports: logger level map, warning filters and caller modules, LoRA release ordering, dunder pass-throughs and tokenizer factory keywords.")
(root / "docs/python-lowlevel-contracts.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
