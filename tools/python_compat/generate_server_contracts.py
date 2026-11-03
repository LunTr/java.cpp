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

# ---- server/app.py endpoints ----------------------------------------------------
import contextlib
import functools

app_tree = ast.parse((args.source_root / "llama_cpp/server/app.py").read_text(encoding="utf-8"))
ep_nodes = [n for n in app_tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
            and n.name in ["set_server_settings", "get_server_settings",
                           "set_llama_proxy", "set_ping_message_factory",
                           "get_llama_proxy", "_logit_bias_tokens_to_input_ids",
                           "prepare_request_resources", "get_event_publisher",
                           "create_app", "create_completion", "create_embedding",
                           "create_chat_completion"]]
for node in ep_nodes:
    node.decorator_list = []

ep_trace = []

def mark(value):
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, bytes):
        return {"bytes": value.hex()}
    if isinstance(value, (list, tuple)):
        return [mark(v) for v in value]
    if isinstance(value, dict):
        return {mark(k): mark(v) for k, v in value.items()}
    if isinstance(value, (set, frozenset)):
        return {"set": sorted(mark(v) for v in value)}
    tagged = getattr(value, "_mark", None)
    if tagged is not None:
        return tagged
    if callable(value):
        name = getattr(value, "__name__", None)
        return "_callable" if name in (None, "<lambda>") else name
    return repr(value)

class FakeHTTP(Exception):
    def __init__(self, status_code=None, detail=None, **kw):
        self.status_code, self.detail = status_code, detail
        super().__init__(detail)

def capture_ep(action):
    try:
        return action()
    except FakeHTTP as error:
        return dict(error="HTTPException", status_code=error.status_code,
                    detail=error.detail)
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))

class Mark:
    def __init__(self, name): self._mark = name

def _tr(*entry):
    ep_trace.append(list(entry))

llama_stub = types.SimpleNamespace(__version__="0.3.36")
llama_stub.LlamaGrammar = types.SimpleNamespace(
    from_string=lambda g: (_tr("grammar_from_string", g), ["grammar", g])[1])
llama_stub.MinTokensLogitsProcessor = (
    lambda mt, eos: (_tr("mtp", mt, eos), ["mtp", mt, eos])[1])
llama_stub.LogitsProcessorList = (
    lambda items: (_tr("lpl", mark(list(items))), list(items))[1])
def _llama_dunder(llama, **kw): pass
_llama_dunder._mark = "Llama.__call__"
def _llama_chat(llama, **kw): pass
_llama_chat._mark = "Llama.create_chat_completion"
llama_stub.Llama = types.SimpleNamespace(__call__=_llama_dunder,
                                       create_chat_completion=_llama_chat)

class CancelledError(Exception):
    pass

def _move_on_after(seconds, shield=False):
    class Scope:
        def __enter__(self):
            _tr("move_on_after", seconds, shield)
            return self
        def __exit__(self, *a):
            return False
    return Scope()

def _memory_stream(capacity):
    _tr("memory_stream", capacity)
    return Mark("send_chan"), Mark("recv_chan")

anyio_stub = types.SimpleNamespace(
    create_memory_object_stream=_memory_stream,
    get_cancelled_exc_class=lambda: CancelledError,
    move_on_after=_move_on_after)

async def _ritp(func, *args, **kw):
    _tr("threadpool", mark(func), mark(list(args)), mark(kw))
    return func(*args, **kw)

async def _iit(iterator):
    for item in iterator:
        yield item

def _esr(recv, data_sender_callable=None, sep=None, ping_message_factory=None, **kw):
    payload = [mark(recv),
               mark(data_sender_callable.func) if data_sender_callable is not None else None,
               {k: mark(v) for k, v in
                (data_sender_callable.keywords if data_sender_callable is not None
                 else {}).items()},
               sep, mark(ping_message_factory)]
    _tr("esr", *payload)
    return {"esr": payload}

class FakeBody:
    _mark = "body"
    def __init__(self, spec):
        self.prompt = spec.get("prompt")
        self.model = spec.get("model")
        self.logit_bias = spec.get("logit_bias")
        self.logit_bias_type = spec.get("logit_bias_type")
        self.grammar = spec.get("grammar")
        self.min_tokens = spec.get("min_tokens", 0)
        self._dump = spec.get("dump", {})
    def model_dump(self, exclude=None):
        _tr("model_dump", sorted(mark(x) for x in exclude) if exclude else None)
        return dict(self._dump)

class FakeLlama:
    _mark = "llama"
    def __init__(self, eos=0, result=None):
        self._eos, self._result = eos, result
    def tokenize(self, token, add_bos, special):
        _tr("tokenize", token.hex(), add_bos, special)
        return [ord(c) for c in token.decode("utf-8")]
    def token_eos(self):
        _tr("token_eos")
        return self._eos
    def __call__(self, **kw):
        if isinstance(self._result, dict) and "raise" in self._result:
            raise ValueError(self._result["raise"])
        return self._result
    def create_chat_completion(self, **kw):
        return self.__call__(**kw)
    def create_embedding(self, **kw):
        return self.__call__(**kw)
FakeLlama.create_chat_completion._mark = "create_chat_completion"
FakeLlama.create_embedding._mark = "create_embedding"

class FakeProxy:
    def __init__(self, llama): self._l = llama
    def __call__(self, model):
        _tr("proxy_call", mark(model))
        return self._l

class FakeRequest:
    _mark = "request"
    def __init__(self, spec):
        self.url = types.SimpleNamespace(path=spec.get("path", "/v1/completions"))
        self.client = spec.get("client", "client-1")
        self._disc = list(spec.get("disconnect", []))
    async def is_disconnected(self):
        value = self._disc.pop(0) if self._disc else False
        _tr("is_disconnected", value)
        return value

class FakeChan:
    async def __aenter__(self):
        _tr("chan", "enter")
        return self
    async def __aexit__(self, *a):
        _tr("chan", "close")
        return False
    async def send(self, event):
        _tr("send", mark(event))

class PubLock:
    def __init__(self, name, locked=False):
        self.name, self._locked = name, locked
    async def acquire(self):
        _tr(self.name, "acquire")
    def release(self):
        _tr(self.name, "release")
    def locked(self):
        _tr(self.name, "locked")
        return self._locked

ep_ns = {
    "typing": types.SimpleNamespace(Any=object),
    "Optional": object, "List": list, "Dict": dict, "Union": object,
    "MemoryObjectSendStream": object,
    "Depends": lambda dep: ("depends", mark(dep)),
    "Body": lambda **kw: None,
    "partial": functools.partial,
    "contextlib": contextlib,
    "json": json,
    "print": lambda *a: _tr("print", " ".join(map(str, a))),
    "llama_cpp": llama_stub,
    "anyio": anyio_stub,
    "run_in_threadpool": _ritp,
    "iterate_in_threadpool": _iit,
    "EventSourceResponse": _esr,
    "HTTPException": FakeHTTP,
    "status": types.SimpleNamespace(HTTP_503_SERVICE_UNAVAILABLE=503,
                                    HTTP_400_BAD_REQUEST=400,
                                    HTTP_401_UNAUTHORIZED=401),
    "LlamaProxy": lambda models: ["proxy", [mark(m) for m in models]],
    "llama_outer_lock": PubLock("outer"),
    "llama_inner_lock": PubLock("inner"),
}
execute(ep_nodes, ep_ns)
ep_ns["_server_settings"] = None
ep_ns["_llama_proxy"] = None
ep_ns["_ping_message_factory"] = None

def snap_state():
    return {"server_settings": mark(ep_ns["_server_settings"]),
            "llama_proxy": mark(ep_ns["_llama_proxy"]),
            "ping": mark(ep_ns["_ping_message_factory"])}

def ep_result(result):
    if isinstance(result, dict) and "error" in result:
        return result
    return mark(result)

# ---- create_app -------------------------------------------------------------------
def run_appcreate(argument):
    ep_trace.clear()
    ep_ns["_server_settings"] = None
    ep_ns["_llama_proxy"] = None
    ep_ns["_ping_message_factory"] = None
    files = {p: bytes.fromhex(h) for p, h in argument.get("files", {}).items()}
    environ = dict(argument.get("env", {}))

    class FakeOpen:
        def __init__(self, content): self._content = content
        def read(self): return self._content
        def __enter__(self): return self
        def __exit__(self, *a): return False
    def fake_open(path, mode):
        _tr("open", path, mode)
        return FakeOpen(files[path])
    ep_ns["open"] = fake_open
    ep_ns["os"] = types.SimpleNamespace(
        environ=environ,
        path=types.SimpleNamespace(
            exists=lambda p: (_tr("exists", p), p in files)[1]))

    yaml_module = types.ModuleType("yaml")
    def safe_load(stream):
        data = stream.read()
        _tr("yaml_safe_load", data.hex())
        return argument["yaml_result"]
    yaml_module.safe_load = safe_load
    previous_yaml = sys.modules.get("yaml")
    sys.modules["yaml"] = yaml_module

    class Config:
        _mark = "config"
        def __init__(self, models): self.models = models
    class ConfigFileSettings:
        @staticmethod
        def model_validate_json(payload):
            _tr("config_validate", mark(payload))
            return Config(argument.get("config_models", []))
    class Server:
        def __init__(self, spec):
            self._mark = spec.get("mark")
            self.root_path = spec.get("root_path")
            self.disable_ping_events = spec.get("disable_ping", False)
    class ServerSettings:
        @staticmethod
        def model_validate(value):
            _tr("server_validate", mark(value))
            return Server(argument.get("server", {}))
    class ModelSettings:
        @staticmethod
        def model_validate(value):
            _tr("model_validate", mark(value))
            return Mark(argument.get("model_mark"))
    def Settings():
        _tr("settings")
        return Mark("settings")
    def Middleware(cls, plugins=None):
        return {"mw": mark(cls), "plugins": [mark(p) for p in (plugins or ())]}
    class FastAPI:
        _mark = "app"
        def __init__(self, **kw): _tr("fastapi", mark(kw))
        def add_middleware(self, cls, **kw):
            _tr("add_middleware", mark(cls), mark(kw))
        def include_router(self, r):
            _tr("include_router", mark(r))
    ep_ns.update({
        "ConfigFileSettings": ConfigFileSettings, "ServerSettings": ServerSettings,
        "ModelSettings": ModelSettings, "Settings": Settings,
        "Middleware": Middleware,
        "RawContextMiddleware": Mark("RawContextMiddleware"),
        "RequestIdPlugin": lambda: Mark("RequestIdPlugin"),
        "FastAPI": FastAPI,
        "CORSMiddleware": Mark("CORSMiddleware"),
        "router": Mark("router"),
    })
    args_list = argument.get("args", [None, None, None])
    server_arg = args_list[1]
    if isinstance(server_arg, dict):
        server_arg = Server(server_arg)

    def op():
        return ep_ns["create_app"](args_list[0], server_arg, args_list[2])
    try:
        result = capture_ep(op)
    finally:
        if previous_yaml is None:
            sys.modules.pop("yaml", None)
        else:
            sys.modules["yaml"] = previous_yaml
    return {"result": ep_result(result), "state": snap_state(),
            "trace": list(ep_trace)}

for case in [dict(args=[None, None, None],
                  server=dict(mark="srv", root_path="/api", disable_ping=False),
                  model_mark="ms", config_models=["cm1"]),
             dict(args=["given", None, None], server=dict(mark="srv2"),
                  model_mark="ms2"),
             dict(args=[None, dict(mark="srvP", root_path="/p"), ["mA", "mB"]],
                  server=dict(mark="unused")),
             dict(args=[None, None, ["mA"]]),
             dict(env={"CONFIG_FILE": "missing.json"}, files={}),
             dict(env={"CONFIG_FILE": "c.json"}, files={"c.json": "7b2261223a317d"},
                  server=dict(mark="srvC", root_path="/c"),
                  config_models=["cm1", "cm2"]),
             dict(env={"CONFIG_FILE": "c.yaml"}, files={"c.yaml": "613a20310a"},
                  yaml_result={"a": 1}, server=dict(mark="srvY"),
                  config_models=["cy"]),
             dict(args=[None, None, None],
                  server=dict(mark="srvD", disable_ping=True), model_mark="msD")]:
    rows.append("\t".join(["appcreate", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_appcreate(case))).decode()]))

# ---- prepare_request_resources ------------------------------------------------------
def run_prepare(argument):
    ep_trace.clear()
    kwargs = dict(argument.get("kwargs", {}))
    proxy = (FakeProxy(FakeLlama(argument.get("token_eos", 0)))
             if argument.get("proxy", True) else None)
    body = FakeBody(argument["body"])
    res = capture_ep(lambda: mark(ep_ns["prepare_request_resources"](
        body, proxy, argument.get("body_model"), kwargs)))
    return {"result": res, "kwargs": mark(kwargs), "trace": list(ep_trace)}

for case in [dict(body=dict(), proxy=True),
             dict(body=dict(), proxy=False),
             dict(body=dict(logit_bias={"x": 1.5}, logit_bias_type="tokens"),
                  proxy=True),
             dict(body=dict(logit_bias={"x": 1.5}, logit_bias_type="input_ids"),
                  proxy=True),
             dict(body=dict(grammar="root ::= x"), proxy=True),
             dict(body=dict(min_tokens=3), token_eos=2, proxy=True),
             dict(body=dict(min_tokens=3), token_eos=2, proxy=True,
                  kwargs={"logits_processor": ["existing"]})]:
    rows.append("\t".join(["appprepare", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_prepare(case))).decode()]))

# ---- get_event_publisher -------------------------------------------------------------
def run_publish(argument):
    ep_trace.clear()
    ep_ns["_server_settings"] = (
        types.SimpleNamespace(interrupt_requests=argument.get("interrupt", False))
        if argument.get("settings", True) else None)
    ep_ns["llama_outer_lock"] = PubLock("outer", argument.get("outer_locked", False))
    ep_ns["llama_inner_lock"] = PubLock("inner")
    ep_ns["_llama_proxy"] = (
        FakeProxy(FakeLlama(argument.get("token_eos", 0)))
        if argument.get("proxy", True) else None)
    chan = FakeChan()
    request = FakeRequest(argument.get("request", {}))
    body = FakeBody(argument.get("body", {}))
    def llama_call(llama, **kw):
        spec = argument.get("call", {})
        if "raise" in spec:
            raise ValueError(spec["raise"])
        return argument.get("chunks", [])
    llama_call._mark = "llama_call"
    res = capture_ep(lambda: asyncio.run(ep_ns["get_event_publisher"](
        request, chan, body, argument.get("body_model"), llama_call,
        dict(argument.get("kwargs", {})))))
    return {"result": res, "trace": list(ep_trace)}

for case in [dict(chunks=[{"a": 1}, {"b": [2]}],
                  request=dict(disconnect=[False, False])),
             dict(chunks=[{"a": 1}], request=dict(disconnect=[True])),
             dict(chunks=[{"a": 1}], request=dict(disconnect=[False]),
                  interrupt=True, outer_locked=True),
             dict(call={"raise": "boom"}),
             dict(chunks=[], request=dict(disconnect=[]), settings=False),
             dict(body=dict(min_tokens=2), token_eos=5, chunks=[{"x": "y"}],
                  request=dict(disconnect=[False]))]:
    rows.append("\t".join(["apppublish", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_publish(case))).decode()]))

# ---- create_completion / create_chat_completion / create_embedding --------------------
def run_completion(argument):
    ep_trace.clear()
    ep_ns["llama_outer_lock"] = PubLock("outer")
    ep_ns["llama_inner_lock"] = PubLock("inner")
    ep_ns["_llama_proxy"] = (
        FakeProxy(FakeLlama(eos=argument.get("token_eos", 0),
                            result=argument.get("tp_result", "RES")))
        if argument.get("proxy", True) else None)
    ep_ns["_ping_message_factory"] = (
        (lambda: b"") if argument.get("ping") else None)
    request = FakeRequest(argument.get("request", {}))
    body = FakeBody(argument["body"])
    res = capture_ep(lambda: asyncio.run(ep_ns["create_completion"](request, body)))
    return {"result": ep_result(res), "prompt": mark(body.prompt),
            "trace": list(ep_trace)}

for case in [dict(body=dict(prompt=["p1"], model="m1", dump={"stream": False}),
                  request=dict(disconnect=[False])),
             dict(body=dict(prompt=[], dump={}), request=dict(disconnect=[False])),
             dict(body=dict(prompt=["a", "b"], dump={})),
             dict(body=dict(prompt="p", model="m", dump={}),
                  request=dict(path="/v1/engines/copilot-codex/completions",
                               disconnect=[False])),
             dict(body=dict(model="m", dump={"stream": True}), ping=True),
             dict(body=dict(model="m", dump={}),
                  request=dict(disconnect=[True], client="cli-9")),
             dict(body=dict(model="m", dump={}), proxy=False,
                  request=dict(disconnect=[False]))]:
    rows.append("\t".join(["appcompletion", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_completion(case))).decode()]))

def run_chat(argument):
    ep_trace.clear()
    ep_ns["llama_outer_lock"] = PubLock("outer")
    ep_ns["llama_inner_lock"] = PubLock("inner")
    ep_ns["_llama_proxy"] = (
        FakeProxy(FakeLlama(eos=argument.get("token_eos", 0),
                            result=argument.get("tp_result", "CHAT")))
        if argument.get("proxy", True) else None)
    ep_ns["_ping_message_factory"] = None
    request = FakeRequest(argument.get("request", {}))
    body = FakeBody(argument["body"])
    res = capture_ep(lambda: asyncio.run(
        ep_ns["create_chat_completion"](request, body)))
    return {"result": ep_result(res), "trace": list(ep_trace)}

for case in [dict(body=dict(model="cm", dump={}), request=dict(disconnect=[False])),
             dict(body=dict(model="cm", dump={"stream": True})),
             dict(body=dict(model="cm", dump={}),
                  request=dict(disconnect=[True], client="cli-8")),
             dict(body=dict(min_tokens=4, dump={}), token_eos=3,
                  request=dict(disconnect=[False]))]:
    rows.append("\t".join(["appchat", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_chat(case))).decode()]))

def run_embed(argument):
    ep_trace.clear()
    request = FakeBody(argument["body"])
    proxy = (FakeProxy(FakeLlama(result=argument.get("tp_result", "EMB")))
             if argument.get("proxy", True) else None)
    res = capture_ep(lambda: asyncio.run(ep_ns["create_embedding"](request, proxy)))
    return {"result": ep_result(res), "trace": list(ep_trace)}

for case in [dict(body=dict(model="em", dump={"input": "hi"})),
             dict(body=dict(model="em", dump={}), proxy=False)]:
    rows.append("\t".join(["appembed", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_embed(case))).decode()]))

# ---- server/model.py load_llama_from_model_settings -------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/model.py").read_text(encoding="utf-8"))
proxy_cls = next(n for n in ast.walk(tree)
                 if isinstance(n, ast.ClassDef) and n.name == "LlamaProxy")
load_fn = next(f for f in proxy_cls.body
               if isinstance(f, ast.FunctionDef)
               and f.name == "load_llama_from_model_settings")
load_fn.decorator_list = []

def make_handler_class(name, calls):
    def ctor(*args, **kwargs):
        calls.append(["handler_ctor", name, kwargs])
        return {"handler": name}
    ctor.from_pretrained = (
        lambda **kwargs: (calls.append(["handler_pretrained", name, kwargs]),
                          {"handler": name + ".pretrained"})[1])
    return ctor

def run_load(argument):
    calls = []
    chat_format_ns = types.SimpleNamespace(
        **{cls: make_handler_class(cls, calls) for cls in
           ["Llava15ChatHandler", "ObsidianChatHandler", "Llava16ChatHandler",
            "MTMDChatHandler", "Gemma4ChatHandler", "MoondreamChatHandler",
            "NanoLlavaChatHandler", "Llama3VisionAlpha", "MiniCPMv26ChatHandler",
            "Qwen25VLChatHandler"]})
    chat_format_ns.hf_autotokenizer_to_chat_completion_handler = (
        lambda path: calls.append(["hf_autotokenizer", path]) or {"handler": "hf_auto"})
    chat_format_ns.hf_tokenizer_config_to_chat_completion_handler = (
        lambda cfg: calls.append(["hf_config", cfg]) or {"handler": "hf_cfg"})
    chat_format_ns.get_chat_completion_handler = (
        lambda fmt: calls.append(["registry_handler", fmt]) or {"handler": "registry:" + str(fmt)})
    def base_handler(*args, **kwargs):
        calls.append(["base_handler", args, kwargs])
        return "BASE"
    class FakeLlama:
        def __init__(self):
            self.chat_handler = argument.get("model_chat_handler", base_handler)
            self._chat_handlers = {k: base_handler for k in
                                   argument.get("model_chat_handlers", {})}
            self.chat_format = argument.get("model_chat_format", "chatml")
            self.cache = None
        def set_cache(self, cache):
            calls.append(["set_cache", cache]); self.cache = cache
        @classmethod
        def from_pretrained(cls, repo_id=None, filename=None, **kwargs):
            calls.append(["llama_pretrained", repo_id, filename, kwargs])
            made.append(("from_pretrained", repo_id, filename, kwargs))
            return cls()
        def __call__(self, *args, **kwargs):
            calls.append(["llama_call", args, kwargs]); return FakeLlama()
    llama_cpp_ns = types.SimpleNamespace(
        llama_chat_format=chat_format_ns,
        Llama=lambda **kwargs: (calls.append(["llama_ctor", kwargs]),
                                made.append(("ctor", kwargs)), FakeLlama())[2],
        LlamaDiskCache=lambda capacity_bytes=None: calls.append(["disk_cache", capacity_bytes]) or ("disk", capacity_bytes),
        LlamaRAMCache=lambda capacity_bytes=None: calls.append(["ram_cache", capacity_bytes]) or ("ram", capacity_bytes),
    )
    # functools.partial semantics: create_fn(**kwargs) forwards to from_pretrained
    llama_cpp_ns.Llama.from_pretrained = FakeLlama.from_pretrained
    tokenizer_ns = types.SimpleNamespace(
        LlamaHFTokenizer=types.SimpleNamespace(
            from_pretrained=lambda name: calls.append(["hf_tokenizer", name]) or ("tok", name)))
    speculative_ns = types.SimpleNamespace(
        LlamaPromptLookupDecoding=lambda num_pred_tokens=None:
            calls.append(["draft", num_pred_tokens]) or ("draft", num_pred_tokens))
    files = dict(argument.get("files", {}))
    def fake_open(path, mode="r"):
        calls.append(["open", path, mode])
        import io
        return io.StringIO(files.get(path, "{}"))
    ns = {"llama_cpp": llama_cpp_ns, "llama_tokenizer": tokenizer_ns,
          "llama_speculative": speculative_ns, "json": json, "open": fake_open,
          "Optional": object, "Dict": dict, "List": list, "Union": object}
    made = []
    execute([load_fn], ns)
    settings = types.SimpleNamespace(**argument["settings"])
    try:
        model = ns["load_llama_from_model_settings"](settings)
        result = {"model": "ok", "calls": calls, "made": made}
        handler = getattr(model, "chat_handler", None)
        if callable(handler) and handler is not base_handler:
            inner = handler(*argument.get("handler_args", []),
                            **argument.get("handler_kwargs", {}))
            result["handler_result"] = inner
            result["handler_calls"] = calls
        return result
    except Exception as error:
        return {"error": type(error).__name__, "message": str(error), "calls": calls}

base_settings = dict(chat_format=None, clip_model_path=None, hf_model_repo_id=None,
                     hf_pretrained_model_name_or_path=None, hf_tokenizer_config_path=None,
                     draft_model=None, draft_model_num_pred_tokens=2, kv_overrides=None,
                     model="m.gguf", n_gpu_layers=0, split_mode=1, main_gpu=0, tensor_split=None,
                     vocab_only=False, use_mmap=True, use_mlock=False, rpc_servers=None, seed=7,
                     n_ctx=512, n_batch=512, n_ubatch=512, n_threads=None, n_threads_batch=None,
                     rope_scaling_type=-1, rope_freq_base=0.0, rope_freq_scale=0.0,
                     yarn_ext_factor=-1.0, yarn_attn_factor=1.0, yarn_beta_fast=32.0,
                     yarn_beta_slow=1.0, yarn_orig_ctx=0, mul_mat_q=True, logits_all=False,
                     embedding=False, offload_kqv=True, flash_attn=False, last_n_tokens_size=64,
                     lora_base=None, lora_path=None, numa=False, chat_template_kwargs=None,
                     type_k=None, type_v=None, cache=False, cache_type="ram", cache_size=8,
                     verbose=False)
load_cases = [
    dict(settings={**base_settings}),
    dict(settings={**base_settings, "chat_format": "llava-1-5", "clip_model_path": "c.gguf"}),
    dict(settings={**base_settings, "chat_format": "mtmd", "clip_model_path": "c.gguf",
                   "hf_model_repo_id": "org/repo"}),
    dict(settings={**base_settings, "chat_format": "llava-1-6"}),
    dict(settings={**base_settings, "chat_format": "hf-autotokenizer"}),
    dict(settings={**base_settings, "chat_format": "hf-autotokenizer",
                   "hf_pretrained_model_name_or_path": "org/tok"}),
    dict(settings={**base_settings, "chat_format": "hf-tokenizer-config",
                   "hf_tokenizer_config_path": "tc.json"},
         files={"tc.json": '{"a": 1}'}),
    dict(settings={**base_settings,
                   "kv_overrides": ["a=bool:true", "b=int:5", "c=float:0.5", "d=str:x"]}),
    dict(settings={**base_settings, "kv_overrides": ["e=bad:1"]}),
    dict(settings={**base_settings, "chat_template_kwargs": {"t": 1}},
         handler_args=["m"], handler_kwargs={"t": 9, "k": 2}),
    dict(settings={**base_settings, "chat_template_kwargs": {"t": 1},
                   "model_chat_handlers": {"chatml": {"handler": "h2"}},
                   "model_chat_format": "chatml"}),
    dict(settings={**base_settings, "chat_template_kwargs": {}},
         ),
    dict(settings={**base_settings, "cache": True, "cache_type": "disk", "verbose": True}),
    dict(settings={**base_settings, "cache": True, "cache_type": "ram", "verbose": False}),
]
for case in load_cases:
    rows.append("\t".join(["loadmodel", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_load(case))).decode()]))

# ---- server/__main__.py main -------------------------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/__main__.py").read_text(encoding="utf-8"))
main_fn = next(n for n in tree.body
               if isinstance(n, ast.FunctionDef) and n.name == "main")

def run_main(argument):
    trace = []
    class StubParser:
        def add_argument(self, flag, **kwargs):
            tn = kwargs.get("type")
            trace.append(["add_argument", flag,
                          "bool_arg" if tn is cli["_parse_bool_arg"] else
                          "json_arg" if tn is cli["_parse_json_object_arg"] else
                          getattr(tn, "__name__", tn),
                          kwargs.get("help")])
        def parse_args(self):
            trace.append(["parse_args"])
            return types.SimpleNamespace(**{"config_file": None, **argument.get("args", {})})
        def print_help(self):
            trace.append(["print_help"])
    argparse_ns = types.SimpleNamespace(
        ArgumentParser=lambda description=None: trace.append(["ArgumentParser", description]) or StubParser())
    class Field:
        def __init__(self, name, annotation, desc=None, default=None, required=True):
            self.name, self.annotation = name, annotation
            self.description, self.default = desc, default
            self._required = required
        def is_required(self): return self._required
    class Settings:
        model_fields = {
            "a": Field("a", Literal["x"], "an opt", default="d0", required=False),
            "b": Field("b", List[int], "many", default=None, required=False),
            "c": Field("c", bool, "flag", default=None, required=False),
        }
    class ConfigFileSettings:
        models = None
        @classmethod
        def model_validate_json(cls, payload):
            trace.append(["config_validate_json",
                          payload if isinstance(payload, str) else payload.decode()])
            if argument.get("config_fail"):
                raise ValueError("bad config")
            spec = argument.get("config_result", {})
            inst = types.SimpleNamespace(models=list(spec.get("models", ["m1"])))
            return inst
    class ServerSettings:
        @classmethod
        def model_validate(cls, cfg):
            trace.append(["server_validate", cfg.models])
            inst = types.SimpleNamespace(host=argument.get("host", "0.0.0.0"),
                                         port=argument.get("port", 8000),
                                         ssl_keyfile=argument.get("key"),
                                         ssl_certfile=argument.get("cert"))
            made.append(("server", inst))
            return inst
    class ModelSettings:
        pass
    exits = []
    def fake_exit(code):
        trace.append(["exit", code])
        exits.append(code)
        raise SystemExit(code)
    files = dict(argument.get("files", {}))
    def fake_open(path, mode="r"):
        trace.append(["open", path, mode])
        import io
        if mode == "rb":
            return io.BytesIO(files.get(path, "{}").encode())
        return io.StringIO(files.get(path, "{}"))
    os_ns = types.SimpleNamespace(
        environ=dict(argument.get("env", {})),
        getenv=lambda k, d=None: dict(argument.get("env", {})).get(k, d),
        path=types.SimpleNamespace(exists=lambda p: trace.append(["exists", p]) or p in files))
    def fake_create_app(server_settings=None, model_settings=None):
        trace.append(["create_app",
                      "validated" if made and server_settings is made[0][1] else "parsed",
                      len(model_settings)])
        return "app"
    uvicorn_ns = types.SimpleNamespace(
        run=lambda app=None, **kwargs: trace.append(["uvicorn", app, kwargs]))
    yaml_ns = types.SimpleNamespace(safe_load=lambda f: json.loads(f.read().decode()))
    import io as _io
    stderr_buf = _io.StringIO()
    sys_ns = types.SimpleNamespace(stderr=stderr_buf, exit=fake_exit)
    ns = {"os": os_ns, "sys": sys_ns, "argparse": argparse_ns, "uvicorn": uvicorn_ns,
          "create_app": fake_create_app, "Settings": Settings,
          "ServerSettings": ServerSettings, "ModelSettings": ModelSettings,
          "ConfigFileSettings": ConfigFileSettings,
          "add_args_from_model": cli["add_args_from_model"],
          "parse_model_from_args": lambda model, args: trace.append(["parse_model", model.__name__]) or types.SimpleNamespace(host="h", port=1, ssl_keyfile=None, ssl_certfile=None),
          "yaml": yaml_ns, "json": json, "open": fake_open}
    made = []
    execute([main_fn], ns)
    try:
        ns["main"]()
        return {"result": "ok", "trace": trace, "stderr": stderr_buf.getvalue()}
    except SystemExit:
        return {"result": "exit", "trace": trace, "stderr": stderr_buf.getvalue()}
    except Exception as error:
        return {"error": type(error).__name__, "message": str(error),
                "trace": trace, "stderr": stderr_buf.getvalue()}

for case in [dict(args={}, env={}),
             dict(args={"config_file": "cfg.json"}, env={},
                  files={"cfg.json": '{"models": ["a", "b"]}'}, config_result={"models": ["a", "b"]}),
             dict(args={"config_file": "cfg.yaml"}, env={},
                  files={"cfg.yaml": '{"models": ["y"]}'}, config_result={"models": ["y"]}),
             dict(args={"config_file": "missing.json"}, env={}),
             dict(args={}, env={"CONFIG_FILE": "e.json"},
                  files={"e.json": '{"models": ["e"]}'}, config_result={"models": ["e"]}),
             dict(args={"config_file": "bad.json"}, env={},
                  files={"bad.json": '{}'}, config_fail=True),
             dict(args={}, env={"HOST": "1.2.3.4", "PORT": "9"}, port=8)]:
    rows.append("\t".join(["servermain", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_main(case))).decode()]))

fixture = root / "src/test/resources/python-0.3.36-server-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=29,
                functions=["_get_base_type", "_contains_list_type", "_contains_dict_type",
                           "_parse_bool_arg", "_parse_json_object_arg",
                           "add_args_from_model", "parse_model_from_args",
                           "ModelSettings.set_dynamic_defaults",
                           "set_server_settings", "get_server_settings",
                           "set_llama_proxy", "set_ping_message_factory",
                           "get_llama_proxy", "_logit_bias_tokens_to_input_ids",
                           "LlamaProxy.__init__", "LlamaProxy.__call__",
                           "LlamaProxy.__getitem__", "LlamaProxy.__setitem__",
                           "LlamaProxy.__iter__", "LlamaProxy.free",
                           "create_app", "prepare_request_resources",
                           "get_event_publisher", "create_completion",
                           "create_embedding", "create_chat_completion",
                           "LlamaProxy.load_llama_from_model_settings",
                           "LlamaProxy.load_llama_from_model_settings.chat_handler_with_kwargs",
                           "main"],
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Hash-verified pinned bodies over stub pydantic/argparse/asyncio-lock/Llama ports; lock acquisition order, channel sends, middleware calls and parser add_argument calls recorded verbatim.")
(root / "docs/python-server-contracts.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
