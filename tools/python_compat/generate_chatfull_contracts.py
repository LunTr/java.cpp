"""Execute the remaining pinned llama_chat_format.py bodies over deterministic stubs."""

import argparse
import ast
import base64
import collections.abc
import contextlib
import gc
import hashlib
import json
import pathlib
import sys
import types

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
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == source["sha"]

future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)


def execute(nodes, namespace):
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
               "pinned-chatfull", "exec"), namespace)


tree = ast.parse((args.source_root / "llama_cpp/llama_chat_format.py").read_text(encoding="utf-8"))
body = {getattr(node, "name", None): node for node in tree.body}
classes = {n.name: n for n in tree.body if isinstance(n, ast.ClassDef)}


class Handle:
    def __init__(self, name):
        self.name = name
        self.fields = {}


def show(value):
    if callable(value):
        return getattr(value, "__name__", "fn")
    if isinstance(value, bytes):
        return "hex:" + value.hex()
    if isinstance(value, Handle):
        return value.name
    if hasattr(value, "_hname"):
        return value._hname
    if isinstance(value, (list, tuple)):
        return [show(v) for v in value]
    if isinstance(value, dict):
        return {k: show(v) for k, v in value.items()}
    if value.__class__.__name__ == "c_size_t":
        return value.value
    if value.__class__.__name__ == "FakeArr":
        return value.data
    return value


import inspect


def capture(action):
    try:
        value = action()
        if inspect.isgenerator(value) or (isinstance(value, collections.abc.Iterator) and not isinstance(value, (list, tuple))):
            try:
                value = list(value)
            except TypeError:
                pass
        return value
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))


class JinjaParserStub:
    def __init__(self, trace):
        self.trace = trace
        self.stream = types.SimpleNamespace(skip=lambda n: trace.append(["skip", n]))

    def parse_statements(self, end_names, drop_needle=False):
        self.trace.append(["parse_statements", list(end_names), drop_needle])
        return "nodes"


class EnvStub:
    def __init__(self, trace, prompts):
        self.trace = trace
        self.prompts = prompts

    def from_string(self, template):
        self.trace.append(["from_string", len(template)])
        env = types.SimpleNamespace()
        env.render = lambda **ctx: self._render(ctx)
        return env

    def _render(self, ctx):
        self.trace.append(["render", show(ctx)])
        return self.prompts.pop(0)


def make_env_factory(trace, prompts):
    class Env:
        def __init__(self, **kwargs):
            trace.append(["env_init", sorted(kwargs)])
            self.filters = {}

        def from_string(self, template):
            trace.append(["from_string", len(template)])
            return types.SimpleNamespace(render=lambda **kw: render_rec(kw))

    def render_rec(ctx):
        trace.append(["render", show(ctx)])
        return prompts.pop(0)

    return Env


class Params(Handle):
    def __init__(self):
        super().__init__("params")


def drive(case):
    trace = []
    prompts = list(case.get("prompts", []))
    stubs_trace = trace

    # mtmd_cpp stub
    mtmd = types.SimpleNamespace()
    mtmd.mtmd_context_params_default = lambda: (trace.append(["params_default"]) or Params())
    def rec(name):
        def f(*a, **kw):
            trace.append([name] + [show(v) for v in a] + ([show(kw)] if kw else []))
            defaults = {"mtmd_tokenize": 0, "mtmd_input_chunks_size": 0,
                        "mtmd_input_chunk_get_type": 0, "mtmd_input_chunk_get_n_tokens": 0,
                        "mtmd_helper_eval_chunk_single": 0, "mtmd_support_vision": True}
            if name in defaults:
                return case.get("returns", {}).get(name, defaults[name])
            return case.get("returns", {}).get(name, Handle(name))
        return f
    for name in ["mtmd_init_from_file", "mtmd_support_vision", "mtmd_free",
                 "mtmd_helper_bitmap_init_from_buf", "mtmd_input_text",
                 "mtmd_input_chunks_init", "mtmd_tokenize", "mtmd_input_chunks_size",
                 "mtmd_input_chunks_get", "mtmd_input_chunk_get_type",
                 "mtmd_input_chunk_get_tokens_text", "mtmd_input_chunk_get_n_tokens",
                 "mtmd_helper_eval_chunk_single", "mtmd_input_chunks_free",
                 "mtmd_bitmap_free"]:
        setattr(mtmd, name, rec(name))
    mtmd.mtmd_default_marker = lambda: b"<marker>"
    class _CArrayFactory:
        def __mul__(self, n):
            return lambda *items: Handle("bmap_arr")
    mtmd.mtmd_bitmap_p_ctypes = _CArrayFactory()
    _orig_input_text = mtmd.mtmd_input_text
    def _text():
        t = types.SimpleNamespace()
        t._hname = "input_text"
        def setitem(k, v):
            trace.append(["input_text." + k, show(v)])
        t.__setattr__ = lambda k, v: trace.append(["input_text." + k, show(v)])
        return t
    mtmd.mtmd_input_text = _text
    mtmd.MTMD_INPUT_CHUNK_TYPE_TEXT = 0
    mtmd.MTMD_INPUT_CHUNK_TYPE_IMAGE = 1
    mtmd.MTMD_INPUT_CHUNK_TYPE_AUDIO = 2
    if "init_ctx" in case.get("returns", {}):
        pass

    # llama_cpp constants
    llama_cpp = types.SimpleNamespace(
        LLAMA_FLASH_ATTN_TYPE_ENABLED="ENABLED",
        LLAMA_FLASH_ATTN_TYPE_DISABLED="DISABLED",
        llama_pos=lambda v: v, llama_seq_id=lambda v: v)

    # llama_grammar
    lg = types.SimpleNamespace(JSON_GBNF="JSON_GBNF_TEXT")
    def from_string(g, **kwargs):
        trace.append(["from_string", g] + ([kwargs] if kwargs else []))
        return Handle("grammar")
    def from_json_schema(s, **kwargs):
        trace.append(["from_json_schema", s] + ([kwargs] if kwargs else []))
        if case.get("schemaFail"):
            raise ValueError("bad schema")
        return Handle("schema_grammar")
    def j2g(j):
        trace.append(["json_schema_to_gbnf", j])
        return "gbnf:" + str(len(j))
    lg.LlamaGrammar = types.SimpleNamespace(from_string=from_string, from_json_schema=from_json_schema)
    lg.json_schema_to_gbnf = j2g

    # llama stub
    class Llama:
        _hname = "llama"
        def __init__(self):
            self.verbose = case.get("verbose", False)
            self.tokenizer_ = types.SimpleNamespace(
                hf_tokenizer=types.SimpleNamespace(
                    additional_special_tokens=case.get("special_tokens", []),
                    apply_chat_template=lambda m, tokenize=False: case.get("tokenizer", "T")))
            self.n_threads = 4
            self.context_params = types.SimpleNamespace(flash_attn_type="ENABLED")
            self.model = Handle("model")
            self.n_tokens = case.get("n_tokens", 0)
            self.n_batch = 512
            self.metadata = case.get("metadata", {})
            self.input_ids = FakeArr(case.get("input_ids", []))
            self._ctx = types.SimpleNamespace(ctx=Handle("ctx"),
                kv_cache_clear=lambda: trace.append(["kv_cache_clear"]))
            self._stack = types.SimpleNamespace(callback=lambda f: case["callbacks"].append(f))
            self._calls = iter(case.get("completions", []))

        def create_completion(self, **kwargs):
            trace.append(["create_completion", show(kwargs)])
            nxt = next(self._calls, None)
            if isinstance(nxt, list) and nxt and nxt[0] == "STREAM":
                return iter(nxt[1])
            if callable(nxt):
                return nxt(kwargs)
            return nxt

        def detokenize(self, tokens):
            trace.append(["detokenize", tokens])
            d = case.get("detok", "545854")
            return bytes.fromhex(d) if isinstance(d, str) else d

        def token_eos(self):
            return case.get("eos", 2)

        def token_bos(self):
            return case.get("bos", 1)

        def n_ctx(self):
            return case.get("n_ctx", 4096)

        def reset(self):
            trace.append(["reset"])

        def eval(self, tokens):
            trace.append(["eval", tokens])

    class FakeArr:
        _hname = "input_ids"
        def __init__(self, data):
            self.data = list(data)
        def fill(self, v):
            trace.append(["fill", v])
        def __getitem__(self, s):
            return FakeArr(self.data[s])
        def tolist(self):
            return self.data

    @contextlib.contextmanager
    def suppress(disable=True):
        trace.append(["suppress_stdout_stderr", disable])
        yield

    def print_rec(*a, **kw):
        if "file" in kw:
            trace.append(["print_err", *[show(v) for v in a]])
        else:
            trace.append(["print", *[show(v) for v in a]])

    class _Sub:
        def __class_getitem__(cls, item):
            return object
    ns = {
        "List": list, "Dict": dict, "Optional": _Sub, "Union": _Sub, "Any": object,
        "Iterator": object, "Tuple": tuple, "Literal": _Sub, "json": json,
        "llama_grammar": lg, "llama_cpp": llama_cpp, "ctypes": types.SimpleNamespace(
            c_uint8=lambda n: types.SimpleNamespace(from_buffer=lambda b: Handle("cbuf")),
            c_size_t=lambda *a: types.SimpleNamespace(value=a[0] if a else 0),
            byref=lambda v: v),
        "os": types.SimpleNamespace(path=types.SimpleNamespace(
            exists=lambda p: case.get("exists", True), join=lambda *a: "/".join(a))),
        "sys": types.SimpleNamespace(stderr=Handle("stderr")),
        "jinja2": types.SimpleNamespace(
            BaseLoader=lambda: Handle("loader"),
            ext=types.SimpleNamespace(loopcontrols=Handle("loopcontrols")),
            parser=types.SimpleNamespace(Parser=object),
            select_autoescape=lambda v: v,
            StrictUndefined=Handle("strict")),
        "suppress_stdout_stderr": suppress,
        "print": print_rec,
        "random": types.SimpleNamespace(choice=lambda s: "x"),
        "string": types.SimpleNamespace(ascii_letters="a", digits="0"),
        "cast": lambda t, v: v,
        "ChatFormatterResponse": lambda **kw: dict(prompt=kw.get("prompt"), stop=kw.get("stop"),
            stopping_criteria=kw.get("stopping_criteria"), added_special=kw.get("added_special")),
        "ChatFormatter": object, "Extension": object,
        "LlamaChatCompletionHandler": object,
        "llama_types": types.SimpleNamespace(
            ChatCompletionRequestSystemMessage=lambda role, content: {"role": role, "content": content},
            ChatCompletionRequestAssistantMessage=lambda role, content: {"role": role, "content": content},
            ChatCompletionStreamResponse=dict,
            ChatCompletionRequestMessage=dict, Completion=dict,
            CreateCompletionResponse=dict, CreateCompletionStreamResponse=dict,
            CreateChatCompletionStreamResponse=dict, CreateChatCompletionResponse=dict),
        "StoppingCriteriaList": lambda items: ("stopping", items),
        "npt": types.SimpleNamespace(NDArray=object),
        "np": types.SimpleNamespace(intc=int, single=float),
        "datetime": types.SimpleNamespace(now=lambda: types.SimpleNamespace(
            strftime=lambda f: "NOW")),
    }
    ns["llama"] = types.SimpleNamespace(StoppingCriteriaList=ns["StoppingCriteriaList"])
    ns["register_chat_completion_handler"] = lambda name: (lambda f: f)
    llpkg = types.ModuleType("llama_cpp")
    llpkg.mtmd_cpp = mtmd
    sys.modules["llama_cpp"] = llpkg
    sys.modules["llama_cpp.mtmd_cpp"] = mtmd
    tf = types.ModuleType("transformers")
    tf.AutoTokenizer = Handle("AutoTokenizer")
    sys.modules["transformers"] = tf
    ns["llama_cpp_pkg"] = llpkg

    # _convert helpers the bodies call (already covered; exec real bodies)
    for name in ["_convert_text_completion_logprobs_to_chat", "_convert_completion_to_chat",
                 "_convert_completion_to_chat_function", "chat_formatter_to_chat_completion_handler",
                 "_get_system_message", "_grammar_for_response_format",
                 "_convert_text_completion_to_chat"]:
        execute([body[name]], ns)
    ns["llama_grammar"] = lg
    Immutable = make_env_factory(trace, prompts)
    ns["ImmutableSandboxedEnvironment"] = Immutable
    # re-exec the formatter so its env class binds the recording one
    execute([classes["Jinja2ChatFormatter"], classes["Llava15ChatHandler"],
             classes["MTMDChatHandler"], classes["Qwen25VLChatHandler"]],
            {**ns, "Extension": object})
    Jinja2 = None
    ns2 = ns
    # execute class defs with Extension + Immutable bound
    exec_ns = dict(ns)
    exec_ns["ImmutableSandboxedEnvironment"] = Immutable
    exec_ns["llama"] = ns["llama"]
    execute([classes["Jinja2ChatFormatter"]], exec_ns)
    Jinja2 = exec_ns["Jinja2ChatFormatter"]
    execute([classes["Llava15ChatHandler"], classes["MTMDChatHandler"]], exec_ns)
    Llava = exec_ns["Llava15ChatHandler"]
    MTMD = exec_ns["MTMDChatHandler"]

    results = []
    box = {"callbacks": []}

    llama_singleton = [None]
    def get_llama():
        if llama_singleton[0] is None:
            llama_singleton[0] = Llama()
        return llama_singleton[0]

    def run(step):
        name = step[0]
        if name == "j2parse":
            return show(Jinja2.IgnoreGenerationTags().parse(JinjaParserStub(trace)))
        if name == "j2init":
            box["fmt"] = Jinja2(step[1], "EOS", "BOS", step[2], step[3])
            return "ok"
        if name == "j2call":
            return show(box["fmt"](**step[1]))
        if name == "j2handler":
            return show(box["fmt"].to_chat_handler())
        if name == "fnV1":
            llama = get_llama()
            sub = dict(exec_ns)
            sub["llama"] = llama
            execute([body["functionary_chat_handler"]], sub)
            return show(sub["functionary_chat_handler"](llama=llama, **step[1]))
        if name == "fnV2":
            llama = get_llama()
            sub = dict(exec_ns)
            sub["llama"] = llama
            tok = types.SimpleNamespace(hf_tokenizer=types.SimpleNamespace(
                apply_chat_template=lambda m, tokenize=False: case["tokenizer"]))
            execute([body["functionary_v1_v2_chat_handler"]], sub)
            return show(sub["functionary_v1_v2_chat_handler"](llama=llama, tokenizer=tok, **step[1]))
        if name == "lvinit":
            try:
                h = object.__new__(Llava)
                Llava.__init__(h, step[1], step[2])
                box["h"] = h
                return "ok"
            except Exception as e:
                return dict(error=type(e).__name__, message=str(e))
        if name == "mtinit":
            try:
                h = object.__new__(MTMD)
                MTMD.__init__(h, step[1], step[2], step[3])
                box["h"] = h
                return "ok"
            except Exception as e:
                return dict(error=type(e).__name__, message=str(e))
        if name == "initCtx":
            box["h"]._init_mtmd_context(get_llama())
            return "ok"
        if name == "mtmdFree":
            for cb in case.get("callbacks", []):
                cb()
            return "ok"
        if name == "loadImg":
            return show(box["h"].load_image(step[1]))
        if name == "loadImgStatic":
            return show(Llava._load_image(step[1]))
        if name == "bitmap":
            return show(box["h"]._create_bitmap_from_bytes(bytes.fromhex(step[1])))
        if name == "getTmpl":
            return box["h"]._get_chat_template(get_llama())
        if name == "lvCall":
            return show(box["h"](llama=get_llama(), **step[1]))
        if name == "mtCall":
            return show(box["h"](llama=get_llama(), **step[1]))
        if name == "chatml":
            sub = dict(exec_ns)
            llama = get_llama()
            sub["llama"] = llama
            execute([body["chatml_function_calling"]], sub)
            return show(sub["chatml_function_calling"](llama=llama, **step[1]))
        if name == "fromPre":
            mod = types.SimpleNamespace()
            mod.hf_hub_download = lambda **kw: (trace.append(["hf_hub_download", show(kw)]) or case.get("dl", "/dl/f"))
            mod.HfFileSystem = lambda: types.SimpleNamespace(
                ls=lambda r: (trace.append(["ls", r]) or case.get("files", [])))
            mod.validate_repo_id = lambda r: trace.append(["validate", r])
            sys.modules["huggingface_hub"] = mod
            sub = types.ModuleType("huggingface_hub.utils")
            sub.validate_repo_id = mod.validate_repo_id
            sys.modules["huggingface_hub.utils"] = sub
            cls = Llava if step[2] == "lv" else MTMD
            return show(cls.from_pretrained(step[1], case["pat"], *step[3:]))
        raise AssertionError("unknown op " + name)

    for step in case["ops"]:
        results.append(capture(lambda s=step: run(s)))
    return dict(results=results, trace=trace)


cases = []


def add(kind, **case):
    case["kind"] = kind
    cases.append(case)


completion = {"id": "c1", "created": 7, "model": "m",
              "choices": [{"index": 0, "text": "text", "logprobs": None, "finish_reason": "stop"}],
              "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}}

add("j2", prompts=["PROMPTED"],
    ops=[["j2parse"], ["j2init", "TPL", True, [1, 2]],
         ["j2call", {"messages": [{"role": "user", "content": "hi"}]}],
         ["j2call", {"messages": [], "extra": 1}],
         ["j2handler"]])
add("j2", prompts=["P"], ops=[["j2init", "T", False, None],
                              ["j2call", {"messages": []}]])
# functionary v1 outer: no functions -> plain completion; with function_call
add("fnV1", completions=[completion],
    ops=[["fnV1", {"messages": [{"role": "user", "content": "hi"}],
                   "functions": None, "tools": None, "tool_choice": None,
                   "function_call": None, "temperature": 0.2, "top_p": 0.95, "top_k": 40,
                   "min_p": 0.05, "typical_p": 1.0, "stream": False, "max_tokens": 10,
                   "presence_penalty": 0.0, "frequency_penalty": 0.0, "repeat_penalty": 1.1,
                   "tfs_z": 1.0, "mirostat_mode": 0, "mirostat_tau": 5.0, "mirostat_eta": 0.1,
                   "model": None, "logits_processor": None, "grammar": None}]])
add("fnV1", completions=[{"id": "a", "created": 1, "model": "m",
                          "choices": [{"index": 0, "text": "to=functions.fn:", "logprobs": None}],
                          "usage": {}},
                         completion],
    ops=[["fnV1", {"messages": [{"role": "user", "content": "hi"}],
                   "functions": [{"name": "fn", "parameters": {"type": "object"}}],
                   "tools": None, "tool_choice": None, "function_call": "auto",
                   "temperature": 0.2, "top_p": 0.95, "top_k": 40, "min_p": 0.05,
                   "typical_p": 1.0, "stream": False, "max_tokens": 10,
                   "presence_penalty": 0.0, "frequency_penalty": 0.0, "repeat_penalty": 1.1,
                   "tfs_z": 1.0, "mirostat_mode": 0, "mirostat_tau": 5.0, "mirostat_eta": 0.1,
                   "model": None, "logits_processor": None, "grammar": None}]])
# functionary v2 outer paths
add("fnV2", tokenizer="T",
    completions=[completion],
    ops=[["fnV2", {"messages": [{"role": "user", "content": "hi"}],
                   "tools": None, "tool_choice": "none", "function_call": None,
                   "functions": None, "temperature": 0.2, "top_p": 0.95, "top_k": 40,
                   "min_p": 0.05, "typical_p": 1.0, "stream": False, "stop": [],
                   "max_tokens": 10, "presence_penalty": 0.0, "frequency_penalty": 0.0,
                   "repeat_penalty": 1.1, "tfs_z": 1.0, "mirostat_mode": 0, "mirostat_tau": 5.0,
                   "mirostat_eta": 0.1, "model": None, "logits_processor": None, "grammar": None}]])
add("fnV2", tokenizer="T",
    completions=[{"id": "a", "created": 1, "model": "m",
                  "choices": [{"index": 0, "text": "  stripped", "logprobs": None}], "usage": {}}],
    ops=[["fnV2", {"messages": [{"role": "user", "content": "hi"}],
                   "tools": None, "tool_choice": None, "function_call": None,
                   "functions": None, "temperature": 0.2, "top_p": 0.95, "top_k": 40,
                   "min_p": 0.05, "typical_p": 1.0, "stream": False, "stop": [],
                   "max_tokens": 10, "presence_penalty": 0.0, "frequency_penalty": 0.0,
                   "repeat_penalty": 1.1, "tfs_z": 1.0, "mirostat_mode": 0, "mirostat_tau": 5.0,
                   "mirostat_eta": 0.1, "model": None, "logits_processor": None, "grammar": None}]])
# v1 streaming assert + v2 streaming dict-function_call path
chunks = [{"id": "k", "created": 3, "model": "m",
           "choices": [{"index": 0, "text": "abc ", "logprobs": None}]},
          {"id": "k", "created": 3, "model": "m",
           "choices": [{"index": 0, "text": "def", "logprobs": None}]}]
add("fnV2s", tokenizer="T",
    completions=[["STREAM", chunks]],
    ops=[["fnV2", {"messages": [{"role": "user", "content": "hi"}],
                   "tools": None, "tool_choice": {"type": "function", "function": {"name": "fn"}},
                   "function_call": None,
                   "functions": [{"name": "fn", "parameters": {"type": "object"}}],
                   "temperature": 0.2, "top_p": 0.95, "top_k": 40, "min_p": 0.05,
                   "typical_p": 1.0, "stream": True, "stop": [], "max_tokens": 10,
                   "presence_penalty": 0.0, "frequency_penalty": 0.0, "repeat_penalty": 1.1,
                   "tfs_z": 1.0, "mirostat_mode": 0, "mirostat_tau": 5.0, "mirostat_eta": 0.1,
                   "model": None, "logits_processor": None, "grammar": None}]])
# media handler lifecycle
add("mediaLv", exists=True,
    ops=[["lvinit", "/mm.gguf", True], ["initCtx"], ["mtmdFree"]])
add("mediaLv", exists=False, ops=[["lvinit", "/no.gguf", True]])
add("mediaMt", exists=True, verbose=False,
    ops=[["mtinit", "/mm.gguf", True, True], ["initCtx"], ["mtmdFree"]])
add("mediaLv", ops=[["loadImgStatic", "data:image/png;base64,aGk="],
                    ["loadImgStatic", "data:x;base64,AAE="]])
add("mediaLv", exists=True,
    ops=[["lvinit", "/mm.gguf", False], ["bitmap", "504e47"], ["initCtx"],
         ["bitmap", "504e47"], ["loadImg", "data:x;base64,AA=="]])
add("mediaMt", exists=True, metadata={"tokenizer.chat_template": "TPL"},
    ops=[["mtinit", "/mm.gguf", False, True], ["getTmpl"]])
add("mediaMt", exists=True, metadata={"x": "y"},
    ops=[["mtinit", "/mm.gguf", False, True], ["getTmpl"]])
# media __call__ no-image llava
add("lvCall", exists=True, verbose=False, detok="545854", eos=2, bos=1,
    completions=[completion], prompts=["P "],
    ops=[["lvinit", "/mm.gguf", False], ["lvCall", {
        "messages": [{"role": "user", "content": "hi"}], "stream": False, "stop": [],
        "seed": None, "response_format": None, "max_tokens": 10, "presence_penalty": 0.0,
        "frequency_penalty": 0.0, "repeat_penalty": 1.1, "tfs_z": 1.0, "mirostat_mode": 0,
        "mirostat_tau": 5.0, "mirostat_eta": 0.1, "model": None, "logits_processor": None,
        "grammar": None, "logit_bias": None, "logprobs": None, "top_logprobs": None,
        "temperature": 0.2, "top_p": 0.95, "top_k": 40, "min_p": 0.05, "typical_p": 1.0,
        "functions": None, "function_call": None, "tools": None, "tool_choice": None}]])
# mtmd __call__ no-image
add("mtCall", exists=True, verbose=False, detok="545854", eos=2, bos=1,
    metadata={"tokenizer.chat_template": "TPL"}, completions=[completion], prompts=["P "],
    ops=[["mtinit", "/mm.gguf", False, True], ["mtCall", {
        "messages": [{"role": "user", "content": "hi"}], "stream": False, "stop": [],
        "seed": None, "response_format": None, "max_tokens": 10, "presence_penalty": 0.0,
        "frequency_penalty": 0.0, "repeat_penalty": 1.1, "tfs_z": 1.0, "mirostat_mode": 0,
        "mirostat_tau": 5.0, "mirostat_eta": 0.1, "model": None, "logits_processor": None,
        "grammar": None, "logit_bias": None, "logprobs": None, "top_logprobs": None,
        "temperature": 0.2, "top_p": 0.95, "top_k": 40, "min_p": 0.05, "typical_p": 1.0,
        "functions": None, "function_call": None, "tools": None, "tool_choice": None}]])
# chatml cases 1/2/3
add("chatml", prompts=["CP"], completions=[completion],
    ops=[["chatml", {"messages": [{"role": "user", "content": "hi"}],
                     "tools": None, "tool_choice": None, "functions": None,
                     "function_call": None, "temperature": 0.2, "top_p": 0.95, "top_k": 40,
                     "min_p": 0.05, "typical_p": 1.0, "stream": False, "stop": [],
                     "response_format": None, "max_tokens": 10, "presence_penalty": 0.0,
                     "frequency_penalty": 0.0, "repeat_penalty": 1.1, "tfs_z": 1.0,
                     "mirostat_mode": 0, "mirostat_tau": 5.0, "mirostat_eta": 0.1,
                     "model": None, "logits_processor": None, "grammar": None,
                     "logprobs": None, "top_logprobs": None}]])
add("chatml", prompts=["CP"], completions=[completion],
    ops=[["chatml", {"messages": [{"role": "user", "content": "hi"}],
                     "tools": [{"type": "function", "function": {"name": "fn",
                                "parameters": {"type": "object"}}}],
                     "tool_choice": {"type": "function", "function": {"name": "fn"}},
                     "functions": None, "function_call": None, "temperature": 0.2,
                     "top_p": 0.95, "top_k": 40, "min_p": 0.05, "typical_p": 1.0,
                     "stream": False, "stop": [], "response_format": None, "max_tokens": 10,
                     "presence_penalty": 0.0, "frequency_penalty": 0.0, "repeat_penalty": 1.1,
                     "tfs_z": 1.0, "mirostat_mode": 0, "mirostat_tau": 5.0, "mirostat_eta": 0.1,
                     "model": None, "logits_processor": None, "grammar": None,
                     "logprobs": None, "top_logprobs": None}]])
add("chatml", prompts=["CP"],
    completions=[
        {"id": "c1", "created": 7, "model": "m",
         "choices": [{"index": 0, "text": "functions.fn", "logprobs": None}], "usage": {}},
        {"id": "c2", "created": 7, "model": "m",
         "choices": [{"index": 0, "text": "{a:1}", "logprobs": None}],
         "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}},
        {"id": "c3", "created": 7, "model": "m",
         "choices": [{"index": 0, "text": "functions.gn", "logprobs": None}],
         "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}},
        {"id": "c4", "created": 7, "model": "m",
         "choices": [{"index": 0, "text": "{b:2}", "logprobs": None}],
         "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}},
        {"id": "c5", "created": 7, "model": "m",
         "choices": [{"index": 0, "text": "functions.none", "logprobs": None}], "usage": {}}],
    ops=[["chatml", {"messages": [{"role": "user", "content": "hi"}],
                     "tools": [{"type": "function", "function": {"name": "fn",
                                "parameters": {"type": "object"}}},
                               {"type": "function", "function": {"name": "gn",
                                "parameters": {"type": "object"}}}],
                     "tool_choice": "auto", "functions": None, "function_call": None,
                     "temperature": 0.2, "top_p": 0.95, "top_k": 40, "min_p": 0.05,
                     "typical_p": 1.0, "stream": False, "stop": [], "response_format": None,
                     "max_tokens": 10, "presence_penalty": 0.0, "frequency_penalty": 0.0,
                     "repeat_penalty": 1.1, "tfs_z": 1.0, "mirostat_mode": 0,
                     "mirostat_tau": 5.0, "mirostat_eta": 0.1, "model": None,
                     "logits_processor": None, "grammar": None,
                     "logprobs": None, "top_logprobs": None}]])
add("chatml", prompts=["CP"], completions=[completion],
    ops=[["chatml", {"messages": [{"role": "user", "content": "hi"}],
                     "tools": [{"type": "function", "function": {"name": "fn",
                                "parameters": {}}}],
                     "tool_choice": "auto", "functions": None, "function_call": None,
                     "temperature": 0.2, "top_p": 0.95, "top_k": 40, "min_p": 0.05,
                     "typical_p": 1.0, "stream": True, "stop": [], "response_format": None,
                     "max_tokens": 10, "presence_penalty": 0.0, "frequency_penalty": 0.0,
                     "repeat_penalty": 1.1, "tfs_z": 1.0, "mirostat_mode": 0,
                     "mirostat_tau": 5.0, "mirostat_eta": 0.1, "model": None,
                     "logits_processor": None, "grammar": None,
                     "logprobs": None, "top_logprobs": None}]])
# from_pretrained
add("pre", files=["repo/x/aa.gguf", "repo/x/bb.gguf"], pat="*.gguf", dl="/dl/bb.gguf",
    ops=[["fromPre", "repo/x", "lv"]])
add("pre", files=["repo/x/aa.txt"], pat="*.gguf", ops=[["fromPre", "repo/x", "lv"]])
add("pre", files=["repo/x/a.gguf", "repo/x/b.gguf"], pat="*b*.gguf",
    ops=[["fromPre", "repo/x", "lv"]])
add("pre", files=["repo/x/only.mmproj"], pat="*.mmproj", dl="/dl/only.mmproj",
    ops=[["fromPre", "repo/x", "mt", "/ld", "auto", "/cache"]])

gc.disable()
rows = []
for index, case in enumerate(cases):
    try:
        gc.collect()
        case.setdefault("callbacks", [])
        rows.append("\t".join([case["kind"], base64.b64encode(wire(case)).decode(),
                               base64.b64encode(wire(drive(case))).decode()]))
    except Exception:
        print("case", index, case["kind"], file=sys.stderr)
        raise
fixture = root / "src/test/resources/python-0.3.36-chatfull-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
functions = [
    "Jinja2ChatFormatter.IgnoreGenerationTags.parse", "Jinja2ChatFormatter.__init__",
    "Jinja2ChatFormatter.__call__", "Jinja2ChatFormatter.__call__.raise_exception",
    "Jinja2ChatFormatter.__call__.stop_on_last_token", "Jinja2ChatFormatter.to_chat_handler",
    "functionary_chat_handler", "functionary_v1_v2_chat_handler",
    "functionary_v1_v2_chat_handler.create_completion",
    "functionary_v1_v2_chat_handler.generate_streaming",
    "Llava15ChatHandler.__init__", "Llava15ChatHandler._init_mtmd_context",
    "Llava15ChatHandler._init_mtmd_context.mtmd_free", "Llava15ChatHandler.load_image",
    "Llava15ChatHandler._create_bitmap_from_bytes", "Llava15ChatHandler.__call__",
    "Llava15ChatHandler._load_image", "Llava15ChatHandler.from_pretrained",
    "MTMDChatHandler.__init__", "MTMDChatHandler._init_mtmd_context",
    "MTMDChatHandler._init_mtmd_context.mtmd_free", "MTMDChatHandler.load_image",
    "MTMDChatHandler._create_bitmap_from_bytes", "MTMDChatHandler._get_chat_template",
    "MTMDChatHandler.__call__", "MTMDChatHandler.__call__.raise_exception",
    "MTMDChatHandler._load_image", "MTMDChatHandler.from_pretrained",
    "chatml_function_calling",
]
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=len(functions),
                functions=functions,
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Complete hash-verified llama_chat_format handler bodies executed over deterministic jinja/mtmd/llama/huggingface_hub stubs; call order and dict shapes compared byte for byte.")
(root / "docs/python-chatfull-contracts.json").write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(dict(caseCount=len(rows), uniqueFunctions=len(functions)), indent=2))
