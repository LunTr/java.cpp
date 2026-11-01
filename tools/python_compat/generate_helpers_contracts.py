"""Execute pinned _utils / llama_tokenizer base+HF / llama_speculative / server.errors bodies."""

import argparse
import ast
import asyncio
import base64
import contextlib
import hashlib
import io
import json
import pathlib
import re
import sys
import types
from contract_wire import wire

# Minimal numpy stub: the pinned speculative code only needs arrays, equality,
# sliding windows, all(axis=1), nonzero and intc.
class NpArray(list):
    @property
    def shape(self):
        if self and isinstance(self[0], (list, NpArray)):
            return (len(self), len(self[0]))
        return (len(self),)
    def __getitem__(self, key):
        value = super().__getitem__(key)
        return NpArray(value) if isinstance(key, slice) else value
    def __eq__(self, other):
        if self and isinstance(self[0], (list, NpArray)):
            return NpArray([NpArray([a == b for a, b in zip(row, other)]) for row in self])
        return NpArray([a == b for a, b in zip(self, other)])
class _Np:
    intc = int
    NDArray = NpArray
    def array(self, values, dtype=None):
        return NpArray(values)
    def all(self, values, axis=None):
        return NpArray([all(row) for row in values]) if axis == 1 else all(values)
    def nonzero(self, values):
        return (NpArray([i for i, v in enumerate(values) if v]),)
    class lib:
        class stride_tricks:
            @staticmethod
            def sliding_window_view(array, window):
                n = window[0]
                return NpArray([NpArray(array[i:i + n]) for i in range(len(array) - n + 1)])
np = _Np()

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
                 "pinned-helpers", "exec"), namespace)

def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))

rows = []

# ---- _utils.py: suppress_stdout_stderr over traced os/sys/null-file ports -----
tree = ast.parse((args.source_root / "llama_cpp/_utils.py").read_text(encoding="utf-8"))

def run_suppress(argument):
    trace = []
    class Os:
        def dup(self, n):
            trace.append(["dup", n])
            return 10 + n
        def dup2(self, source, target):
            trace.append(["dup2", source, target])
        def close(self, n):
            trace.append(["close", n])
    class FileObj:
        def __init__(self, fd):
            self.fd = fd
        def fileno(self):
            return self.fd
        def __repr__(self):
            return f"null{self.fd}"
    class Stream:
        def __init__(self, name):
            self.name = name
    class Sys:
        pass
    sys_port = Sys()
    sys_port.stdout, sys_port.stderr = Stream("OUT"), Stream("ERR")
    if argument["kernel"]:
        sys_port.stdout = Stream("KOUT")
        sys_port.stdout._original_stdstream_copy = 101
        sys_port.stderr = Stream("KERR")
        sys_port.stderr._original_stdstream_copy = 102
    outnull, errnull = FileObj(30), FileObj(31)
    ns = dict(sys=types.SimpleNamespace(stdout=sys_port.stdout, stderr=sys_port.stderr),
              os=Os(), outnull_file=outnull, errnull_file=errnull,
              STDOUT_FILENO=1, STDERR_FILENO=2)
    # Class-level `sys = sys`/`os = os` snapshot these stubs; bodies also read the
    # outnull_file globals from this namespace.
    execute([n for n in tree.body if isinstance(n, ast.ClassDef)], ns)
    cls = ns["suppress_stdout_stderr"]
    # self.sys/self.os must be OUR mutable ports, not the module copies.
    manager = cls(disable=argument["disable"])
    manager.sys = sys_port
    manager.os = ns["os"]

    def state():
        return dict(stdout=getattr(sys_port.stdout, "name", repr(sys_port.stdout)),
                    stderr=getattr(sys_port.stderr, "name", repr(sys_port.stderr)),
                    out_copy=getattr(sys_port.stdout, "_original_stdstream_copy", None),
                    err_copy=getattr(sys_port.stderr, "_original_stdstream_copy", None))
    enter_result = capture(manager.__enter__)
    if enter_result is manager:
        enter_result = "self"
    enter_state = state()
    exit_result = capture(manager.__exit__)
    exit_state = state()
    return dict(enter=dict(result=enter_result, state=enter_state),
                exit=dict(result=exit_result, state=exit_state), trace=trace)

for spec in [dict(disable=True, kernel=False), dict(disable=False, kernel=False),
             dict(disable=False, kernel=True)]:
    rows.append("\t".join(["suppress", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_suppress(spec))).decode()]))

# ---- _utils.py: MetaSingleton / Singleton --------------------------------------
utils_ns = dict(sys=sys, os=__import__("os"),
                outnull_file=open(__import__("os").devnull, "w"),
                errnull_file=open(__import__("os").devnull, "w"),
                STDOUT_FILENO=1, STDERR_FILENO=2,
                Any=object, Dict=dict)
execute([n for n in tree.body if isinstance(n, ast.ClassDef)], utils_ns)

def run_singleton():
    MetaSingleton, Singleton = utils_ns["MetaSingleton"], utils_ns["Singleton"]
    MetaSingleton._instances.clear()
    A = MetaSingleton("A", (Singleton,), {})
    B = MetaSingleton("B", (Singleton,), {})
    bad = capture(lambda: A(1))
    if not isinstance(bad, dict):
        bad = "cached-instance"
    C = MetaSingleton("C", (Singleton,), {})
    return dict(first_second_same=A() is A(), ab_different=A() is not B(),
                cached_arg=bad, bad_arg=capture(lambda: C(1)))

rows.append("\t".join(["singleton", base64.b64encode(wire(None)).decode(),
                       base64.b64encode(wire(run_singleton())).decode()]))

# ---- llama_tokenizer.py: BaseLlamaTokenizer ABC + LlamaHFTokenizer --------------
tree = ast.parse((args.source_root / "llama_cpp/llama_tokenizer.py").read_text(encoding="utf-8"))
tk_ns = {"List": list, "Optional": object, "Any": object, "abc": __import__("abc"),
         "llama_cpp": types.SimpleNamespace()}
execute([n for n in tree.body if isinstance(n, ast.ClassDef)
         and n.name in ["BaseLlamaTokenizer", "LlamaHFTokenizer"]], tk_ns)
Base, HF = tk_ns["BaseLlamaTokenizer"], tk_ns["LlamaHFTokenizer"]

def tokenizer_subclass(overrides):
    impls = {}
    for name in overrides:
        impls[name] = (lambda n: (lambda self, *a: getattr(Base, n)(self, *a)))(name)
    return type("SubTok", (Base,), impls)
for spec in [dict(overrides=[], call=None), dict(overrides=["tokenize"], call=None),
             dict(overrides=["detokenize", "tokenize"], call=None),
             dict(overrides=["detokenize", "tokenize"], call="tokenize"),
             dict(overrides=["detokenize", "tokenize"], call="detokenize")]:
    cls = Base if not spec["overrides"] else tokenizer_subclass(spec["overrides"])
    def action():
        inst = cls()
        if spec["call"] is None: return "created"
        if spec["call"] == "tokenize": return inst.tokenize(b"x")
        return inst.detokenize([1])
    rows.append("\t".join(["base_tok", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(capture(action))).decode()]))

def run_hf(argument):
    trace = []
    class Hf:
        def encode(self, text, add_special_tokens):
            trace.append(["encode", text, add_special_tokens])
            return [ord(c) for c in text] + ([7] if add_special_tokens else [])
        def decode(self, tokens, skip_special_tokens):
            trace.append(["decode", list(tokens), skip_special_tokens])
            return "t" + "".join(chr(t) for t in tokens if not (skip_special_tokens and t == 7))
    inst = HF(Hf())
    events = []
    for step in argument["steps"]:
        def op():
            if step[0] == "tokenize":
                return inst.tokenize(bytes.fromhex(step[1]), add_bos=step[2], special=step[3])
            prev = None if step[2] is None else list(step[2])
            return inst.detokenize(list(step[1]), prev_tokens=prev, special=step[3]).hex()
        events.append(dict(result=capture(op), trace=list(trace)))
        trace.clear()
    return events

def run_hf_pretrained(argument):
    backup = dict(sys.modules)
    calls = []
    try:
        if argument["installed"]:
            auto = types.SimpleNamespace(from_pretrained=lambda **kw: (calls.append(kw), "HF:" + kw["pretrained_model_name_or_path"])[1])
            sys.modules["transformers"] = types.SimpleNamespace(AutoTokenizer=auto)
            result = capture(lambda: HF.from_pretrained(argument["path"]))
            made = None
            if not isinstance(result, dict):
                made, result = result.hf_tokenizer, "created"
            return dict(result=result, calls=calls, made=made)
        sys.modules.pop("transformers", None)
        return dict(result=capture(lambda: HF.from_pretrained(argument["path"])), calls=[])
    finally:
        sys.modules.clear()
        sys.modules.update(backup)

for case in [dict(steps=[["tokenize", "6162", True, True], ["tokenize", "ff61", True, False],
                         ["detokenize", [97, 98], None, True], ["detokenize", [97, 98], [99], False],
                         ["detokenize", [97, 98], None, False]]),
             dict(steps=[["detokenize", [7, 97], None, True], ["detokenize", [7, 97], None, False],
                         ["tokenize", "e4b8ad", False, True]])]:
    rows.append("\t".join(["hf", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(run_hf(case))).decode()]))
for spec in [dict(installed=True, path="org/model x"), dict(installed=False, path="org/model")]:
    rows.append("\t".join(["pretrained", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_hf_pretrained(spec))).decode()]))

# ---- llama_speculative.py ------------------------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/llama_speculative.py").read_text(encoding="utf-8"))
spec_ns = {"np": np, "npt": np, "abc": __import__("abc"), "Any": object}
execute([n for n in tree.body if isinstance(n, ast.ClassDef)], spec_ns)
Draft, Lookup = spec_ns["LlamaDraftModel"], spec_ns["LlamaPromptLookupDecoding"]

def run_draft(argument):
    if argument["kind"] == "abstract":
        return dict(result=capture(lambda: Draft()(np.array([], dtype=np.intc))))
    inst = Lookup(max_ngram_size=argument["ngram"], num_pred_tokens=argument["pred"])
    out = inst(np.array(argument["ids"], dtype=np.intc))
    return dict(result=list(int(t) for t in out))

for spec in [dict(kind="abstract"),
             dict(kind="call", ngram=2, pred=3, ids=[1, 2, 3, 1, 2, 3, 1, 2]),
             dict(kind="call", ngram=3, pred=5, ids=[5, 6, 7, 8, 5, 6, 7]),
             dict(kind="call", ngram=2, pred=4, ids=[1, 2, 3]),
             dict(kind="call", ngram=1, pred=2, ids=[9, 9, 9, 9]),
             dict(kind="call", ngram=5, pred=2, ids=[1, 2])]:
    rows.append("\t".join(["draft", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_draft(spec))).decode()]))

# ---- server/errors.py -----------------------------------------------------------
tree = ast.parse((args.source_root / "llama_cpp/server/errors.py").read_text(encoding="utf-8"))
err_nodes = [n for n in tree.body if isinstance(n, ast.ClassDef)
             and n.name in ["ErrorResponseFormatters", "RouteErrorHandler"]]

class ChatCompletionRequest:
    def __init__(self, **kw):
        self.__dict__.update(kw)
        self.max_tokens = kw.get("max_tokens")
        self.messages = kw.get("messages", [])
class CompletionRequest:
    def __init__(self, **kw):
        self.__dict__.update(kw)
        self.max_tokens = kw.get("max_tokens")
class EmbeddingRequest:
    def __init__(self, **kw):
        self.__dict__.update(kw)
class HttpExc(Exception):
    pass
times = iter([1000.0, 1000.25])
inner = {"handler": None}
class Request:
    def __init__(self, payload, fail_json=False):
        self.payload, self.fail_json = payload, fail_json
    async def json(self):
        if self.fail_json:
            raise ValueError("bad json")
        return self.payload
class JsonResponse:
    def __init__(self, body, status_code):
        self.body, self.status_code = body, status_code
class Resp:
    def __init__(self):
        self.headers = {}
err_ns = {"compile": re.compile, "Match": re.Match, "Pattern": re.Pattern,
          "Callable": object, "Coroutine": object, "Optional": object, "Tuple": tuple,
          "Union": object, "Dict": dict, "Request": Request, "Response": Resp,
          "HTTPException": HttpExc, "JSONResponse": JsonResponse,
          "APIRoute": object, "sys": sys, "traceback": __import__("traceback"),
          "time": types.SimpleNamespace(perf_counter=lambda: next(times)),
          "TypedDict": dict, "annotations": None,
          "CreateCompletionRequest": CompletionRequest,
          "CreateChatCompletionRequest": ChatCompletionRequest,
          "CreateEmbeddingRequest": EmbeddingRequest,
          "ErrorResponse": lambda **kw: dict(kw),
          "_orig": types.SimpleNamespace(
              get_route_handler=lambda: inner["handler"])}
for node in err_nodes:
    node.bases = [n for n in node.bases
                  if not (isinstance(n, ast.Name) and n.id == "APIRoute")]
    for item in node.body:
        if isinstance(item, ast.FunctionDef) and item.name == "get_route_handler":
            for sub in ast.walk(item):
                if isinstance(sub, ast.Call) and isinstance(sub.func, ast.Attribute) \
                        and isinstance(sub.func.value, ast.Call) \
                        and isinstance(sub.func.value.func, ast.Name) \
                        and sub.func.value.func.id == "super":
                    sub.func.value = ast.Name(id="_orig", ctx=ast.Load())
execute(err_nodes, err_ns)
Fmt, Handler = err_ns["ErrorResponseFormatters"], err_ns["RouteErrorHandler"]

def body(kind, max_tokens):
    if kind == "chat": return ChatCompletionRequest(max_tokens=max_tokens)
    if kind == "completion": return CompletionRequest(max_tokens=max_tokens)
    return EmbeddingRequest()

def run_wrapper(argument):
    handler = Handler()
    writes = []
    class Err(io.StringIO):
        def write(self, v):
            writes.append(v)
            return len(v)
    with contextlib.redirect_stderr(Err()):
        result = capture(lambda: handler.error_message_wrapper(
            Exception(argument["error"]),
            None if argument["body"] is None else body(argument["body"], argument.get("max_tokens"))))
    return dict(result=result, stderr=writes)

for spec in [dict(error="Requested tokens (120) exceed context window of 512", body="chat", max_tokens=8),
             dict(error="Requested tokens (9) exceed context window of 4", body="completion", max_tokens=None),
             dict(error="Model path does not exist: /x/y.gguf", body="chat", max_tokens=1),
             dict(error="boom", body="chat", max_tokens=1),
             dict(error="Requested tokens (1) exceed context window of 2", body="embedding"),
             dict(error="boom", body=None)]:
    rows.append("\t".join(["errfmt", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_wrapper(spec))).decode()]))

def run_route(argument):
    global times
    times = iter([1000.0, 1000.25])
    async def inner_handler(request):
        return Resp()
    inner["handler"] = inner_handler
    handler = Handler()
    async def invoke():
        if argument["fail"] == "http":
            async def raise_http(request):
                raise HttpExc("unauthorized")
            inner["handler"] = raise_http
        elif argument["fail"] == "exc":
            async def raise_exc(request):
                raise Exception(argument["error"])
            inner["handler"] = raise_exc
        route = handler.get_route_handler()
        request = Request(argument["payload"], fail_json=argument.get("badjson", False))
        return await route(request)
    result = capture(lambda: asyncio.run(invoke()))
    if isinstance(result, Resp):
        result = dict(kind="resp", headers=result.headers)
    elif isinstance(result, JsonResponse):
        result = dict(kind="json", status=result.status_code, body=result.body)
    elif isinstance(result, dict) and "error" in result:
        result = dict(kind="exc", **result)
    return dict(result=result)

for spec in [dict(fail=None, payload={"messages": []}),
             dict(fail="http", payload={"messages": []}),
             dict(fail="exc", error="Model path does not exist: m.gguf", payload={"messages": [], "max_tokens": 4}),
             dict(fail="exc", error="Requested tokens (50) exceed context window of 10", payload={"prompt": "x", "max_tokens": 2}),
             dict(fail="exc", error="other", payload={"input": "x"}),
             dict(fail="exc", error="other", payload={}, badjson=True)]:
    rows.append("\t".join(["route", base64.b64encode(wire(spec)).decode(),
                           base64.b64encode(wire(run_route(spec))).decode()]))

fixture = root / "src/test/resources/python-0.3.36-helpers-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=20,
                functions=["suppress_stdout_stderr.__init__", "suppress_stdout_stderr.__enter__",
                           "suppress_stdout_stderr.__exit__", "MetaSingleton.__call__",
                           "Singleton.__init__", "BaseLlamaTokenizer.tokenize",
                           "BaseLlamaTokenizer.detokenize", "LlamaHFTokenizer.__init__",
                           "LlamaHFTokenizer.tokenize", "LlamaHFTokenizer.detokenize",
                           "LlamaHFTokenizer.from_pretrained", "LlamaDraftModel.__call__",
                           "LlamaPromptLookupDecoding.__init__",
                           "LlamaPromptLookupDecoding.find_candidate_pred_tokens",
                           "LlamaPromptLookupDecoding.__call__",
                           "ErrorResponseFormatters.context_length_exceeded",
                           "ErrorResponseFormatters.model_not_found",
                           "RouteErrorHandler.error_message_wrapper",
                           "RouteErrorHandler.get_route_handler",
                           "RouteErrorHandler.get_route_handler.custom_route_handler"],
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Hash-verified pinned bodies over stub os/sys/HF/transformers/FastAPI ports; dedup registries, ABC checks and stderr fragments recorded verbatim.")
(root / "docs/python-helpers-contracts.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
