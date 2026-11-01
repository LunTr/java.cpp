"""Execute pinned server/cli.py + settings/app/model module-level helpers."""

import argparse
import ast
import asyncio
import base64
import hashlib
import json
import pathlib
import sys
import types
from typing import List, Dict, Literal, Union, Optional
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
                 "pinned-server", "exec"), namespace)

def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))

def enc(node):
    """Encode a typing annotation as the Annotation port wire shape."""
    origin = getattr(node, "__origin__", None)
    if origin is Literal: tag = "literal"
    elif origin is Union: tag = "union"
    elif node is type(None): tag = "none"
    elif origin is list: tag = "list"
    elif origin is dict: tag = "dict"
    else: tag = None
    name = getattr(node, "__name__", None)
    return dict(origin=tag, base=name or getattr(node, "_name", None) or str(node),
                args=[enc(a) for a in getattr(node, "__args__", [])])

rows = []

# ---- server/cli.py --------------------------------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/cli.py").read_text(encoding="utf-8"))
cli_ns = dict(List=List, Literal=Literal, Union=Union, Dict=Dict, Any=object,
              Type=type, TypeVar=lambda **k: object(), json=json, BaseModel=object,
              argparse=__import__("argparse"))
execute([n for n in tree.body if isinstance(n, ast.FunctionDef)], cli_ns)
cli = cli_ns

def ann(spec):
    node = spec
    if node == "literal": return Literal["a", "b"]
    if node == "union": return Union[int, None]
    if node == "union_empty": return Union[None]
    if node == "list": return List[int]
    if node == "list_lit": return List[Literal["x"]]
    if node == "lit_list": return Literal[List[int]]
    if node == "dict": return Dict[str, int]
    if node == "lit_union_list": return Union[Literal[List[int]], int]
    if node == "lit_union_dict": return Union[Literal[Dict[str, int]], int]
    if node == "int": return int
    if node == "bool": return bool
    if node == "none": return None
    return None

for spec in ["literal", "union", "union_empty", "list", "list_lit", "lit_list",
             "dict", "lit_union_list", "lit_union_dict", "int", "bool"]:
    node = ann(spec)
    rows.append("\t".join(["getbase", base64.b64encode(wire(spec)).decode(),
        base64.b64encode(wire({"base": capture(lambda: (lambda v: v.__name__ if isinstance(v, type) else type(v).__name__)(cli["_get_base_type"](node))),
                               "list": capture(lambda: cli["_contains_list_type"](node)),
                               "dict": capture(lambda: cli["_contains_dict_type"](node))})).decode()]))

for spec in ["1", "on", "T", "TRUE", " y ", "yes", "0", "OFF", "f", "false", "n", "no",
             "maybe", 1, b"true", b"0", True, False, "0.5"]:
    rows.append("\t".join(["bool", base64.b64encode(wire(spec if not isinstance(spec, bytes) else spec.decode())).decode(),
        base64.b64encode(wire(capture(lambda: cli["_parse_bool_arg"](spec)))).decode()]))

for spec in ['{"a": 1, "b": [2]}', "[1,2]", "bad", '{"x": null}', "42"]:
    rows.append("\t".join(["json", base64.b64encode(wire(spec)).decode(),
        base64.b64encode(wire(capture(lambda: cli["_parse_json_object_arg"](spec)))).decode()]))

def run_addargs():
    trace = []
    class Parser:
        def add_argument(self, flag, dest=None, nargs=None, type=None, help=None):
            tn = getattr(type, "__name__", type)
            trace.append([flag, dest, nargs,
                          "bool_arg" if type is cli["_parse_bool_arg"] else
                          "json_arg" if type is cli["_parse_json_object_arg"] else tn,
                          help])
    class Field:
        def __init__(self, name, annotation, desc=None, default=None, required=True):
            self.name, self.annotation = name, annotation
            self.description, self.default = desc, default
            self._required = required
        def is_required(self): return self._required
    class Model:
        model_fields = {
            "a": Field("a", Literal["x"], "an opt", default="d0", required=False),
            "b": Field("b", List[int], "many", default=None, required=False),
            "c": Field("c", bool, "flag", default=None, required=False),
            "d": Field("d", Dict[str, int], "m", default=None, required=False),
            "e": Field("e", int, None, default=None, required=False),
            "f": Field("f", None, "noann", default=None, required=False),
            "g": Field("g", int, "req"),
        }
    cli["add_args_from_model"](Parser(), Model)
    return trace

rows.append("\t".join(["addargs", base64.b64encode(wire(None)).decode(),
    base64.b64encode(wire(run_addargs())).decode()]))

def run_parsemodel(argument):
    class Model:
        model_fields = {"a": None, "b": None}
        def __init__(self, **kw): self.kw = kw
    ns = types.SimpleNamespace(**argument["ns"])
    try:
        made = cli["parse_model_from_args"](Model, ns)
        return dict(made=made.kw)
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))

for spec in [dict(ns=dict(a=1, b=None, c=2)), dict(ns=dict(b=0))]:
    rows.append("\t".join(["parsemodel", base64.b64encode(wire(spec)).decode(),
        base64.b64encode(wire(run_parsemodel(spec))).decode()]))

# ---- server/settings.py set_dynamic_defaults -------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/settings.py").read_text(encoding="utf-8"))
node = next(n for n in ast.walk(tree) if isinstance(n, ast.ClassDef) and n.name == "ModelSettings")
method = next(f for f in node.body if isinstance(f, ast.FunctionDef)
              and f.name == "set_dynamic_defaults")
method.decorator_list = []
set_ns = {"Self": object, "Dict": dict, "cast": lambda t, v: v,
          "multiprocessing": types.SimpleNamespace(cpu_count=lambda: 8)}
execute([method], set_ns)
for spec in [dict(values=dict(n_threads=-1, n_threads_batch=-1)),
             dict(values=dict(n_threads=4, n_threads_batch=-1)),
             dict(values={}), dict(values=dict(n_threads=0))]:
    values = dict(spec["values"])
    result = capture(lambda: set_ns["set_dynamic_defaults"](values))
    rows.append("\t".join(["dyn", base64.b64encode(wire(spec)).decode(),
        base64.b64encode(wire(dict(result=result, values=values))).decode()]))

# ---- server/app.py module helpers -------------------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/app.py").read_text(encoding="utf-8"))
app_nodes = [n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
             and n.name in ["set_server_settings", "get_server_settings",
                            "set_llama_proxy", "set_ping_message_factory",
                            "get_llama_proxy", "_logit_bias_tokens_to_input_ids",
                            "get_event_publisher"]]
trace_app = []
class AsyncLock:
    def __init__(self, name): self.name = name
    async def acquire(self): trace_app.append([self.name, "acquire"])
    def release(self): trace_app.append([self.name, "release"])
outer, inner = AsyncLock("outer"), AsyncLock("inner")
app_ns = {"Lock": AsyncLock,
          "llama_outer_lock": outer, "llama_inner_lock": inner,
          "LlamaProxy": lambda models: ("proxy", list(models)),
          "Optional": object, "typing": types.SimpleNamespace(),
          "List": list, "Dict": dict,
          "llama_cpp": types.SimpleNamespace()}
# The pinned file declares _server_settings/_llama_proxy/_ping_message_factory globals.
execute(app_nodes, app_ns)
app_ns["_server_settings"] = None
app_ns["_llama_proxy"] = None
app_ns["_ping_message_factory"] = None

def run_app(argument):
    app_ns["llama_outer_lock"], app_ns["llama_inner_lock"] = outer, inner
    results = []
    for step in argument["steps"]:
        trace_app.clear()
        def op():
            if step[0] == "set_settings":
                app_ns["set_server_settings"](step[1]); return "ok"
            if step[0] == "get_settings":
                return list(app_ns["get_server_settings"]())
            if step[0] == "set_proxy":
                app_ns["set_llama_proxy"](step[1]); return "ok"
            if step[0] == "set_ping":
                app_ns["set_ping_message_factory"](step[1]); return "ok"
            if step[0] == "get_proxy":
                async def walk():
                    agen = app_ns["get_llama_proxy"]()
                    try:
                        return await agen.__anext__()
                    finally:
                        await agen.aclose()
                return asyncio.run(walk())
            if step[0] == "bias":
                class L:
                    def tokenize(self, token, add_bos, special):
                        trace_app.append(["tokenize", token.hex(), add_bos, special])
                        return [ord(c) for c in token.decode()]
                return app_ns["_logit_bias_tokens_to_input_ids"](L(), step[1])
            raise AssertionError(step)
        results.append(dict(result=capture(op), trace=list(trace_app)))
    return results

for case in [dict(steps=[["set_settings", "S1"], ["get_settings"],
                         ["set_proxy", ["m1", "m2"]], ["set_ping", "F1"],
                         ["bias", {"a": 1.5, "bc": -2.0}]])]:
    rows.append("\t".join(["app", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_app(case))).decode()]))
# get_llama_proxy lock order standalone
def run_getproxy():
    app_ns["_llama_proxy"] = "PROXY"
    trace_app.clear()
    async def walk():
        agen = app_ns["get_llama_proxy"]()
        try:
            return await agen.__anext__()
        finally:
            await agen.aclose()
    return dict(result=capture(lambda: asyncio.run(walk())), trace=list(trace_app))
rows.append("\t".join(["app", base64.b64encode(wire(dict(steps=[["get_proxy"]]))).decode(),
    base64.b64encode(wire(dict(result=run_getproxy()["result"], trace=list(trace_app)))).decode()]))

# ---- server/model.py LlamaProxy ----------------------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/model.py").read_text(encoding="utf-8"))
cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "LlamaProxy")
keep = [f for f in cls.body if isinstance(f, ast.FunctionDef)
        and f.name in ["__init__", "__call__", "__getitem__", "__setitem__", "__iter__", "free"]]
cls.body = keep
model_trace = []
class FakeSettings:
    def __init__(self, model=None, model_alias=None):
        self.model, self.model_alias = model, model_alias
    def model_dump(self):
        model_trace.append(["dump", self.model])
        return dict(alias=self.model_alias)
    @staticmethod
    def model_validate_json(payload):
        model_trace.append(["validate", payload])
        return FakeSettings(model="V:" + payload)
class FakeLlama:
    def __init__(self, alias): self.alias = alias; self.closed = False
    def close(self): model_trace.append(["close", self.alias]); self.closed = True
model_ns = {"List": list, "Optional": object, "Union": object, "Dict": dict,
            "ModelSettings": FakeSettings, "llama_cpp": types.SimpleNamespace(),
            "llama_speculative": types.SimpleNamespace(),
            "llama_tokenizer": types.SimpleNamespace(), "json": json}
model_ns["llama_cpp"].Llama = FakeLlama
execute([cls], model_ns)
Proxy = model_ns["LlamaProxy"]
Proxy.load_llama_from_model_settings = staticmethod(
    lambda settings: FakeLlama(settings.model_alias))
Proxy.init = None

def run_proxy(argument):
    loader_calls = []
    orig = Proxy.load_llama_from_model_settings
    Proxy.load_llama_from_model_settings = staticmethod(
        lambda settings: (loader_calls.append(settings.model_alias), FakeLlama(settings.model_alias))[1])
    settings = [FakeSettings(model="m" + str(i + 1)) for i in range(argument["models"])]
    results = []
    def op():
        proxy = Proxy(models=settings)
        for step in argument["steps"]:
            def inner_op():
                if step[0] == "call": return getattr(proxy(step[1]), "alias", "None")
                if step[0] == "get": return proxy[step[1]]
                if step[0] == "set": proxy[step[1]] = step[2]; return "ok"
                if step[0] == "iter": return list(proxy)
                if step[0] == "free": proxy.free(); return "ok"
                raise AssertionError(step)
            results.append(dict(result=capture(inner_op), trace=list(model_trace)))
            model_trace.clear()
        return results
    out = capture(op)
    Proxy.load_llama_from_model_settings = orig
    return dict(result=out if isinstance(out, dict) else results, loader=loader_calls)

for case in [dict(models=2, steps=[["call", None], ["call", "m2"], ["call", "ghost"], ["iter"]]),
             dict(models=1, steps=[["get", "m1"], ["get", "bad"],
                                   ["set", "new", '{"model": "n"}'], ["call", "new"], ["free"]]),
             dict(models=0, steps=[])]:
    rows.append("\t".join(["proxy", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_proxy(case))).decode()]))

fixture = root / "src/test/resources/python-0.3.36-server-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=20,
                functions=["_get_base_type", "_contains_list_type", "_contains_dict_type",
                           "_parse_bool_arg", "_parse_json_object_arg",
                           "add_args_from_model", "parse_model_from_args",
                           "ModelSettings.set_dynamic_defaults",
                           "set_server_settings", "get_server_settings",
                           "set_llama_proxy", "set_ping_message_factory",
                           "get_llama_proxy", "_logit_bias_tokens_to_input_ids",
                           "LlamaProxy.__init__", "LlamaProxy.__call__",
                           "LlamaProxy.__getitem__", "LlamaProxy.__setitem__",
                           "LlamaProxy.__iter__", "LlamaProxy.free"],
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Hash-verified pinned bodies over stub pydantic/argparse/asyncio-lock/Llama ports; lock acquisition order and parser add_argument calls recorded verbatim.")
(root / "docs/python-server-contracts.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
