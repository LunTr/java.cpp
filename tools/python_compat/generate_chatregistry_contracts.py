"""Execute pinned chat-format registry/formatter ports + server endpoints + ext decorator."""

import argparse
import ast
import asyncio
import base64
import contextlib
import hashlib
import io
import json
import pathlib
import sys
import types
from datetime import datetime
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
                 "pinned-chat", "exec"), namespace)

def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))

rows = []

# ---- llama_cpp_ext._ctypes_function_from_names ------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/llama_cpp_ext.py").read_text(encoding="utf-8"))
def run_ext(argument):
    trace = []
    class Func:
        def __init__(self, name):
            self.__dict__["_name"] = name
        def __setattr__(self, k, v):
            trace.append(["set", getattr(self, "_name", "?"), k,
                          v if isinstance(v, (str, int, list, dict, type(None))) else type(v).__name__])
            self.__dict__[k] = v
    class Lib:
        def __getattr__(self, name):
            if name in argument["symbols"]:
                func = Func(name)
                trace.append(["get", name])
                return func
            raise AttributeError(f"{name} missing")
    def wrapped(**kw):
        trace.append(["call", kw])
        return "wrapped"
    wrapped.__name__ = "wrapped_fn"
    import functools
    def wraps_shim(f):
        def apply(func):
            functools.WRAPPER_ASSIGNMENTS
            func.__wrapped__ = f
            trace.append(["wrap", getattr(func, "_name", "?")])
            return func
        return apply
    ext_ns = dict(functools=types.SimpleNamespace(wraps=wraps_shim),
                  _lib=Lib(), ctypes=__import__("ctypes"),
                  Any=object, Iterable=object, Union=object,
                  llama_cpp=types.SimpleNamespace(_lib=Lib()))
    execute([n for n in tree.body if isinstance(n, ast.FunctionDef)
             and n.name == "_ctypes_function_from_names"], ext_ns)
    deco = ext_ns["_ctypes_function_from_names"]
    def invoke():
        decorator = deco(argument["names"], argument["argtypes"], argument["restype"])
        func = decorator(wrapped)
        return dict(func=func if not hasattr(func, "argtypes") else dict(
            argtypes=func.argtypes, restype=func.restype, wrapped=True))
    return dict(result=capture(invoke), trace=trace)

for spec in [dict(symbols=["sym_b"], names=["sym_a", "sym_b"], argtypes=["int"], restype="void"),
             dict(symbols=[], names=["sym_a"], argtypes=[], restype="int")]:
    rows.append("\t".join(["ext", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_ext(spec))).decode()]))

# ---- llama_chat_format registry + decorators + protocols + tojson -----------------
tree = ast.parse((args.source_root / "llama_cpp/llama_chat_format.py").read_text(encoding="utf-8"))
nodes = [n for n in tree.body if isinstance(n, ast.ClassDef)
         and n.name in ["LlamaChatCompletionHandlerNotFoundException",
                        "LlamaChatCompletionHandlerRegistry", "ChatFormatter",
                        "LlamaChatCompletionHandler"]] \
       + [n for n in tree.body if isinstance(n, ast.FunctionDef)
          and n.name in ["get_chat_completion_handler", "register_chat_completion_handler",
                         "register_chat_format", "chat_formatter_to_chat_completion_handler"]] \
       + [n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Jinja2ChatFormatter"]
jinja_cls = next(n for n in nodes if isinstance(n, ast.ClassDef) and n.name == "Jinja2ChatFormatter")
jinja_cls.body = [m for m in jinja_cls.body if isinstance(m, ast.FunctionDef)
                  and m.name in ["strftime_now", "tojson"]]
cf_ns = {"Protocol": object, "Singleton": None, "Dict": dict, "List": list,
         "Optional": object, "Union": object, "Any": object, "Type": type,
         "Tuple": tuple, "Iterator": iter, "dataclasses": __import__("dataclasses"),
         "llama_types": types.SimpleNamespace(), "llama": types.SimpleNamespace(),
         "llama_grammar": types.SimpleNamespace(), "json": json,
         "datetime": datetime, "os": __import__("os"), "sys": sys,
         "jinja2": types.SimpleNamespace(), "abc": __import__("abc"),
         "Extension": object, "ImmutableSandboxedEnvironment": object,
         "Callable": object}
utils_tree = ast.parse((args.source_root / "llama_cpp/_utils.py").read_text(encoding="utf-8"))
utils_ns = dict(sys=sys, os=__import__("os"),
                outnull_file=open(__import__("os").devnull, "w"),
                errnull_file=open(__import__("os").devnull, "w"),
                STDOUT_FILENO=1, STDERR_FILENO=2, Any=object, Dict=dict)
execute([n for n in utils_tree.body if isinstance(n, ast.ClassDef)], utils_ns)
cf_ns["Singleton"] = utils_ns["Singleton"]
execute(nodes, cf_ns)
Registry = cf_ns["LlamaChatCompletionHandlerRegistry"]

def run_registry(argument):
    Registry._chat_handlers.clear()
    reg = Registry()
    results = []
    for step in argument["steps"]:
        def op():
            if step[0] == "reg": return reg.register_chat_completion_handler(step[1], f"h:{step[1]}", overwrite=step[2]) or "ok"
            if step[0] == "unreg": return reg.unregister_chat_handler(step[1]) or "ok"
            if step[0] == "get": return reg.get_chat_completion_handler_by_name(step[1])
            if step[0] == "getmod": return cf_ns["get_chat_completion_handler"](step[1])
            if step[0] == "deco":
                f = cf_ns["register_chat_completion_handler"](step[1])(lambda *a, **k: f"fn:{step[1]}")
                return "ok" if callable(f) else f
            if step[0] == "fmtdeco":
                f = cf_ns["register_chat_format"](step[1])(lambda *a, **k: f"fmt:{step[1]}")
                return "ok" if callable(f) else f
            raise AssertionError(step)
        res = capture(op)
        if callable(res):
            res = "callable"
        results.append(dict(result=res, handlers=list(Registry._chat_handlers)))
    return results

real_fmt_to_handler = cf_ns["chat_formatter_to_chat_completion_handler"]
cf_ns["chat_formatter_to_chat_completion_handler"] = lambda f: f"wrapped:{f.__name__ if hasattr(f,'__name__') else f}"
for case in [dict(steps=[["reg", "a", False], ["reg", "a", False], ["reg", "a", True], ["get", "a"], ["unreg", "a"], ["unreg", "a"]]),
             dict(steps=[["get", "missing"], ["reg", "a", False], ["reg", "b", False], ["get", "z"], ["getmod", "b"]]),
             dict(steps=[["deco", "d1"], ["fmtdeco", "d2"], ["get", "d1"], ["get", "d2"]])]:
    rows.append("\t".join(["reg", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(run_registry(case))).decode()]))

def run_protocols():
    return dict(handler=capture(lambda: cf_ns["LlamaChatCompletionHandler"]()()),
                formatter=capture(lambda: cf_ns["ChatFormatter"]()(messages=[])))
rows.append("\t".join(["proto", base64.b64encode(wire(None)).decode(),
                       base64.b64encode(wire(run_protocols())).decode()]))

Jinja = cf_ns["Jinja2ChatFormatter"]
def run_static(argument):
    out = []
    FakeNow.calls.clear()
    for step in argument["steps"]:
        if step[0] == "strftime":
            out.append(capture(lambda: Jinja.strftime_now(step[1])))
        else:
            out.append(capture(lambda: Jinja.tojson(step[1], **step[2])))
    return dict(result=out, calls=list(FakeNow.calls))

class FakeNow:
    calls = []
    def strftime(self, f):
        FakeNow.calls.append(["strftime", f])
        return "NOW(" + f + ")"
class FakeDatetime:
    @staticmethod
    def now():
        FakeNow.calls.append(["now"])
        return FakeNow()
cf_ns["datetime"] = FakeDatetime
for case in [dict(steps=[["strftime", "%H:%M"],
                         ["tojson", {"b": [1, 2.5, True, None, "中"]}, {}],
                         ["tojson", [1, 2], {"ensure_ascii": False, "indent": 2, "sort_keys": True}]])]:
    rows.append("\t".join(["static", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(run_static(case))).decode()]))

def run_handler(argument):
    trace = []
    def formatter(**kw):
        trace.append(["format", kw])
        return types.SimpleNamespace(prompt="PROMPT", stop=argument.get("result_stop"),
                                     added_special=argument.get("added_special", False),
                                     stopping_criteria=None)
    class Llama:
        verbose = False
        def tokenize(self, b, add_bos, special):
            trace.append(["tokenize", b.hex() if isinstance(b, bytes) else b, add_bos, special])
            return [1, 2]
        def create_completion(self, **kw):
            trace.append(["create", kw])
            return "CHUNKS"
    llama = Llama()
    class Gram:
        @staticmethod
        def from_json_schema(s, verbose):
            trace.append(["gs_schema", s, verbose])
            if argument.get("schema_fail"):
                raise ValueError("bad schema")
            return ("schema", s)
        @staticmethod
        def from_string(g, verbose):
            trace.append(["gs_string", g, verbose])
            return ("string", g)
    cf_ns["llama_grammar"].LlamaGrammar = Gram
    cf_ns["llama_grammar"].JSON_GBNF = "GBNF"
    cf_ns["_grammar_for_response_format"] = lambda rf, verbose: ("rf", rf["type"])
    cf_ns["_convert_completion_to_chat"] = lambda c, stream: ("conv", c, stream)
    cf_ns["_convert_completion_to_chat_function"] = lambda n, c, s: ("convf", n, c, s)
    cf_ns["llama"].verbose = False
    handler = real_fmt_to_handler(formatter)
    kwargs = dict(llama=llama, messages=[{"role": "u"}])
    kwargs.update(argument.get("call", {}))
    result = capture(lambda: handler(**kwargs))
    return dict(result=result, trace=trace)

for case in [dict(call={}, result_stop=None),
             dict(call={"stop": "s1", "stream": True, "response_format": {"type": "json_object"}},
                  result_stop=["r1"]),
             dict(call={"tools": [{"type": "function", "function": {"name": "f", "parameters": {"p": 1}}}],
                        "tool_choice": {"type": "function", "function": {"name": "f"}},
                        "logprobs": True, "top_logprobs": 3}),
             dict(call={"function_call": "auto", "functions": [{"name": "g"}],
                        "tool_choice": {"type": "function", "function": {"name": "missing"}}})]:
    rows.append("\t".join(["handler", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(run_handler(case))).decode()]))

# ---- server/app.py endpoints -------------------------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/app.py").read_text(encoding="utf-8"))
endpoints = [n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
             and n.name in ["authenticate", "tokenize", "detokenize",
                            "count_query_tokens", "get_models"]]
for fn in endpoints:
    fn.decorator_list = []
class HTTPException(Exception):
    def __init__(self, status_code=None, detail=None, **kw):
        super().__init__(detail)
        self.status_code, self.detail = status_code, detail
app_ns = {"Settings": object, "Depends": lambda f: f, "Optional": object,
          "bearer_scheme": None, "HTTPException": HTTPException,
          "status": types.SimpleNamespace(HTTP_401_UNAUTHORIZED=401),
          "Union": object, "LlamaProxy": object, "ModelList": dict,
          "TokenizeInputRequest": object, "TokenizeInputResponse": dict,
          "TokenizeInputCountResponse": dict, "DetokenizeInputRequest": object,
          "DetokenizeInputResponse": dict, "Dict": dict, "List": list,
          "get_server_settings": None, "get_llama_proxy": None}
execute(endpoints, app_ns)

def run_endpoints(argument):
    results = []
    for step in argument["steps"]:
        def op():
            if step[0] == "auth":
                settings = types.SimpleNamespace(api_key=step[1])
                auth = types.SimpleNamespace(credentials=step[2]) if step[2] is not None else None
                return asyncio.run(app_ns["authenticate"](settings, auth))
            if step[0] == "tokenize":
                body = types.SimpleNamespace(input=step[1], model=step[2])
                proxy = lambda model: LlamaStub()
                return asyncio.run(app_ns["tokenize"](body, proxy))
            if step[0] == "count":
                body = types.SimpleNamespace(input=step[1], model=step[2])
                proxy = lambda model: LlamaStub()
                return asyncio.run(app_ns["count_query_tokens"](body, proxy))
            if step[0] == "detok":
                body = types.SimpleNamespace(tokens=step[1], model=step[2])
                proxy = lambda model: LlamaStub()
                return asyncio.run(app_ns["detokenize"](body, proxy))
            if step[0] == "models":
                proxy = iter(step[1])
                return asyncio.run(app_ns["get_models"](proxy))
            raise AssertionError(step)
        results.append(capture(op))
    return results

class LlamaStub:
    def tokenize(self, data, add_bos=True, special=False):
        LlamaStub.trace.append(["tokenize", data.hex(), add_bos, special])
        return [b for b in data]
    def detokenize(self, tokens):
        LlamaStub.trace.append(["detokenize", list(tokens)])
        return bytes(tokens)
LlamaStub.trace = []

for case in [dict(steps=[["auth", None, "x"], ["auth", "k1", "k1"], ["auth", "k1", "bad"], ["auth", "k1", None]]),
             dict(steps=[["tokenize", "ab", "m"], ["count", "ab", "m"], ["detok", [97, 98], "m"]]),
             dict(steps=[["models", ["m1", "m2"]]])]:
    rows.append("\t".join(["ep", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(run_endpoints(case))).decode()]))

fixture = root / "src/test/resources/python-0.3.36-chatregistry-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=21,
                functions=["_ctypes_function_from_names", "_ctypes_function_from_names.decorator",
                           "LlamaChatCompletionHandler.__call__",
                           "LlamaChatCompletionHandlerRegistry.register_chat_completion_handler",
                           "LlamaChatCompletionHandlerRegistry.unregister_chat_handler",
                           "LlamaChatCompletionHandlerRegistry.get_chat_completion_handler_by_name",
                           "get_chat_completion_handler",
                           "register_chat_completion_handler",
                           "register_chat_completion_handler.decorator",
                           "register_chat_format", "register_chat_format.decorator",
                           "ChatFormatter.__call__",
                           "Jinja2ChatFormatter.strftime_now", "Jinja2ChatFormatter.tojson",
                           "chat_formatter_to_chat_completion_handler",
                           "chat_formatter_to_chat_completion_handler.chat_completion_handler",
                           "authenticate", "tokenize", "detokenize",
                           "count_query_tokens", "get_models"],
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Hash-verified pinned bodies over stub ctypes-lib/Singleton/jinja/format/llama-grammar/FastAPI ports; handler kwargs, tokenize flags and grammar fallback recorded verbatim.")
(root / "docs/python-chatregistry-contracts.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
