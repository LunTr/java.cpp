"""Execute pinned Llama.__init__ over stub ports recording assembly order/field writes."""

import argparse
import ast
import base64
import contextlib
import hashlib
import json
import pathlib
import sys
import types
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

tree = ast.parse((args.source_root / "llama_cpp/llama.py").read_text(encoding="utf-8"))
llama = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Llama")
init_fn = next(f for f in llama.body if isinstance(f, ast.FunctionDef) and f.name == "__init__")
init_fn.decorator_list = []
future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)

def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))


def mark(value):
    if isinstance(value, (bytes, bytearray)):
        return {"bytes": bytes(value).hex()}
    if isinstance(value, dict):
        return {mark(k): mark(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [mark(v) for v in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if hasattr(value, "fields"):
        return mark(dict(value.fields))
    return str(value)


def make_ns(arg, trace):
    arg = arg
    import numpy as np
    CONST = dict(LLAMA_SPLIT_MODE_LAYER=1, LLAMA_DEFAULT_SEED=4294967295,
                 LLAMA_ROPE_SCALING_TYPE_UNSPECIFIED=-1, LLAMA_POOLING_TYPE_UNSPECIFIED=-1,
                 LLAMA_ATTENTION_TYPE_UNSPECIFIED=-1, LLAMA_LOAD_MODE_MMAP_MLOCK=3,
                 LLAMA_LOAD_MODE_MLOCK=2, LLAMA_LOAD_MODE_MMAP=1, LLAMA_LOAD_MODE_NONE=0,
                 LLAMA_MAX_DEVICES=4, LLAMA_KV_OVERRIDE_TYPE_BOOL=0,
                 LLAMA_KV_OVERRIDE_TYPE_INT=1, LLAMA_KV_OVERRIDE_TYPE_FLOAT=2,
                 LLAMA_KV_OVERRIDE_TYPE_STR=3, LLAMA_FLASH_ATTN_TYPE_ENABLED=1,
                 LLAMA_FLASH_ATTN_TYPE_DISABLED=0, GGML_NUMA_STRATEGY_DISTRIBUTE=2,
                 GGML_NUMA_STRATEGY_DISABLED=0)

    class Params:
        def __init__(self, name):
            self.name = name
            self.fields = {}
        def __setattr__(self, key, value):
            if key in ("name", "fields"):
                object.__setattr__(self, key, value)
            else:
                self.fields[key] = value
                trace.append([self.name, key, mark(value)])
                object.__setattr__(self, key, value)

    class Struct:
        def __init__(self):
            object.__setattr__(self, "fields", {})
        def __setattr__(self, key, value):
            self.fields[key] = value
            object.__setattr__(self, key, value)
        def __getattr__(self, key):
            if key == "value":
                sub = Struct()
                self.fields["value"] = sub
                object.__setattr__(self, "value", sub)
                return sub
            return self.fields.get(key)

    def model_default_params():
        trace.append(["model_default_params"])
        return Params("model_params")
    def context_default_params():
        trace.append(["context_default_params"])
        return Params("context_params")

    class FakeModel:
        def __init__(self, path_model=None, params=None, verbose=None):
            trace.append(["LlamaModel", path_model, mark(dict(params.fields)), verbose])
            self.model = "MODEL"
        def __enter__(self): return self
        def __exit__(self, *a): trace.append(["model_close"]); return False
        def close(self): trace.append(["model_close"])
        def n_ctx_train(self):
            trace.append(["n_ctx_train"]); return arg.get("n_ctx_train", 64)
        def n_vocab(self):
            trace.append(["n_vocab"]); return arg.get("n_vocab", 4)
        def token_nl(self):
            trace.append(["token_nl"]); return arg.get("token_nl", 13)
        def token_eos(self):
            trace.append(["token_eos"]); return arg.get("token_eos", 2)
        def token_bos(self):
            trace.append(["token_bos"]); return arg.get("token_bos", 1)
        def metadata(self):
            trace.append(["metadata"])
            if arg.get("metadata_fail"): raise RuntimeError("meta fail")
            return dict(arg.get("metadata", {}))
        def token_get_text(self, token):
            trace.append(["token_get_text", token]); return arg.get("tok_text", {}).get(str(token), "T" + str(token))

    class FakeContext:
        def __init__(self, model=None, params=None, verbose=None):
            trace.append(["LlamaContext", getattr(model, "model", model),
                          mark(dict(params.fields)), verbose])
            self.ctx = "CTX"
        def __enter__(self): return self
        def __exit__(self, *a): trace.append(["ctx_close"]); return False
        def close(self): trace.append(["ctx_close"])
        def n_ctx(self):
            trace.append(["n_ctx"]); return arg.get("n_ctx_out", 64)

    class FakeBatch:
        def __init__(self, n_tokens=None, embd=None, n_seq_max=None, verbose=None):
            trace.append(["LlamaBatch", n_tokens, embd, n_seq_max, verbose])
        def __enter__(self): return self
        def __exit__(self, *a): trace.append(["batch_close"]); return False
        def close(self): trace.append(["batch_close"])

    class FakeCandidates:
        def __init__(self, n_vocab=None):
            trace.append(["candidates", n_vocab])

    def lora_init(model, path):
        trace.append(["lora_init", model, path])
        return None if arg.get("lora_fail") else "ADAPTER"
    def lora_free(adapter):
        trace.append(["lora_free", adapter])
    def set_adapters(ctx, adapters, count, scales):
        trace.append(["set_adapters", ctx, list(adapters), count, list(scales)])
        return arg.get("adapters_fail", 0)

    class ArrayMul:
        """Model `ctypes_type * n` returning a zero-arg/values callable."""
        def __init__(self, factory):
            self.factory = factory
        def __mul__(self, n):
            return self.factory(n)
    class CFloatType:
        def __call__(self, v=0.0):
            return types.SimpleNamespace(value=v)
        def __mul__(self, n):
            return lambda *v: list(v)

    chat_ns = types.SimpleNamespace(
        Jinja2ChatFormatter=lambda template=None, eos_token=None, bos_token=None,
            stop_token_ids=None: types.SimpleNamespace(
                to_chat_handler=lambda: {"jinja": template, "eos": eos_token,
                                         "bos": bos_token, "stop": stop_token_ids}),
        guess_chat_format_from_gguf_metadata=lambda meta: arg.get("guess"),
        LlamaChatCompletionHandler=object)

    llama_cpp = types.SimpleNamespace(
        **CONST,
        llama_backend_init=lambda: trace.append(["backend_init"]),
        llama_numa_init=lambda n: trace.append(["numa_init", n]),
        llama_model_default_params=model_default_params,
        llama_context_default_params=context_default_params,
        llama_model_kv_override=ArrayMul(
            lambda n: (lambda: [Struct() for _ in range(n)])),
        llama_model_kv_override_value=types.SimpleNamespace(
            val_str=types.SimpleNamespace(offset=0)),
        llama_max_parallel_sequences=lambda: arg.get("max_seq", 4),
        llama_adapter_lora_init=lora_init, llama_adapter_lora_free=lora_free,
        llama_set_adapters_lora=set_adapters,
        llama_adapter_lora_p_ctypes=ArrayMul(lambda n: (lambda *v: list(v))),
        llama_adapter_lora_p=object,
        llama_print_system_info=lambda: b"sysinfo",
        llama_model_is_recurrent=lambda m: int(arg.get("recurrent", 0)),
        llama_model_is_hybrid=lambda m: int(arg.get("hybrid", 0)))

    ctypes_ns = types.SimpleNamespace(
        c_float=CFloatType(),
        c_uint8=int,
        POINTER=lambda t: t,
        c_char=int,
        addressof=lambda x: 0,
        cast=lambda a, t: a,
        memmove=lambda d, s, n: trace.append(["memmove", n]),
        c_int32=int)

    class ExitStack(contextlib.ExitStack):
        def enter_context(self, cm):
            result = super().enter_context(cm)
            trace.append(["enter", type(cm).__name__])
            return result
        def callback(self, fn, *a, **kw):
            trace.append(["callback"])
            return super().callback(fn, *a, **kw)

    @contextlib.contextmanager
    def suppress(disable=None):
        trace.append(["suppress", disable])
        yield

    internals_ns = types.SimpleNamespace(
        LlamaModel=FakeModel, LlamaContext=FakeContext, LlamaBatch=FakeBatch,
        LlamaTokenDataArray=FakeCandidates)

    files = set(arg.get("files", []))
    os_ns = types.SimpleNamespace(
        path=types.SimpleNamespace(exists=lambda p: trace.append(["exists", p]) or p in files))

    stderr_lines = []
    def fake_print(*a, **kw):
        stderr_lines.append(" ".join(str(x) for x in a))

    ns = {
        "llama_cpp": llama_cpp, "internals": internals_ns, "os": os_ns,
        "sys": types.SimpleNamespace(stderr=sys.stderr),
        "multiprocessing": types.SimpleNamespace(cpu_count=lambda: arg.get("cpus", 8)),
        "contextlib": contextlib, "ctypes": ctypes_ns,
        "suppress_stdout_stderr": suppress,
        "set_verbose": lambda v: trace.append(["set_verbose", v]),
        "llama_chat_format": chat_ns,
        "LlamaTokenizer": lambda self_arg: trace.append(["LlamaTokenizer"]) or "TOK",
        "BaseLlamaTokenizer": object, "LlamaDraftModel": object,
        "Optional": object, "Dict": dict, "List": list, "Union": object,
        "npt": types.SimpleNamespace(NDArray=object), "np": np,
        "LlamaState": object, "print": fake_print,
        "typing": types.SimpleNamespace(cast=lambda t, v: v),
    }
    return ns, stderr_lines


def run_init(arg):
    trace = []
    ns, stderr_lines = make_ns(arg, trace)
    namespace = dict(ns)
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future, llama], type_ignores=[])),
                 "pinned-init", "exec"), namespace)
    Llama = namespace["Llama"]
    obj = Llama.__new__(Llama)
    kwargs = dict(arg["kwargs"])
    result = capture(lambda: Llama.__init__(obj, arg.get("model_path", "m.gguf"), **kwargs))
    snapshot = {}
    for key in ["verbose", "numa", "model_path", "tensor_split", "n_batch", "n_threads",
                "n_threads_batch", "_seed", "last_n_tokens_size", "lora_base", "lora_scale",
                "lora_path", "spm_infill", "_logits_all", "n_tokens", "_requires_eval",
                "chat_format", "_n_vocab", "_n_ctx", "_token_nl", "_token_eos", "_mirostat_mu",
                "_is_recurrent", "_is_hybrid", "tokenizer_"]:
        if hasattr(obj, key):
            value = getattr(obj, key)
            snapshot[key] = value.value if hasattr(value, "value") and key == "_mirostat_mu" else \
                value.tolist() if hasattr(value, "tolist") else value
    if hasattr(obj, "model_params"):
        snapshot["model_params"] = dict(obj.model_params.fields)
    if hasattr(obj, "context_params"):
        snapshot["context_params"] = dict(obj.context_params.fields)
    if hasattr(obj, "metadata"):
        snapshot["metadata"] = dict(obj.metadata) if isinstance(obj.metadata, dict) else str(obj.metadata)
    if hasattr(obj, "_chat_handlers"):
        snapshot["chat_handlers"] = {k: v for k, v in obj._chat_handlers.items()}
    if hasattr(obj, "cache"):
        snapshot["cache"] = obj.cache
    if hasattr(obj, "draft_model"):
        snapshot["draft_model"] = obj.draft_model
    if hasattr(obj, "_lora_adapter"):
        snapshot["lora_adapter"] = obj._lora_adapter
    if hasattr(obj, "kv_overrides"):
        snapshot["kv_overrides"] = obj.kv_overrides
    if hasattr(obj, "_kv_overrides_array"):
        snapshot["kvo_array"] = [item.fields for item in obj._kv_overrides_array]
    return {"result": result, "snapshot": mark(snapshot), "trace": mark(trace),
            "stderr": stderr_lines}


rows = []
base_kwargs = dict(verbose=False, n_gpu_layers=0, split_mode=1, main_gpu=0, tensor_split=None,
                   vocab_only=False, use_mmap=True, use_mlock=False, kv_overrides=None,
                   seed=7, n_ctx=512, n_batch=512, n_ubatch=512, n_threads=None,
                   n_threads_batch=None, rope_scaling_type=-1, pooling_type=-1,
                   attention_type=-1, rope_freq_base=0.0, rope_freq_scale=0.0,
                   yarn_ext_factor=-1.0, yarn_attn_factor=1.0, yarn_beta_fast=32.0,
                   yarn_beta_slow=1.0, yarn_orig_ctx=0, logits_all=False, embedding=False,
                   offload_kqv=True, flash_attn=False, op_offload=None, swa_full=None,
                   no_perf=False, last_n_tokens_size=64, lora_base=None, lora_scale=1.0,
                   lora_path=None, numa=False, chat_format=None, chat_handler=None,
                   draft_model=None, tokenizer=None, type_k=None, type_v=None,
                   spm_infill=False)
cases = [
    dict(kwargs=dict(base_kwargs), files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "n_gpu_layers": -1, "numa": True}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "tensor_split": [0.5, 0.5]}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "tensor_split": [0.2] * 5}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "use_mlock": True}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "use_mmap": False}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "kv_overrides": {"a": True, "b": 3, "c": 0.5, "d": "x"}},
         files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "kv_overrides": {"k": "x" * 200}}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "kv_overrides": {"k": [1]}}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "n_ctx": 0}, files=["m.gguf"], n_ctx_train=100),
    dict(kwargs={**base_kwargs, "embedding": True}, files=["m.gguf"], max_seq=2),
    dict(kwargs=dict(base_kwargs), files=[]),
    dict(kwargs={**base_kwargs, "lora_path": "l.bin", "lora_scale": 0.5}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "lora_path": "l.bin"}, files=["m.gguf"], lora_fail=True),
    dict(kwargs={**base_kwargs, "lora_path": "l.bin"}, files=["m.gguf"], adapters_fail=1),
    dict(kwargs={**base_kwargs, "flash_attn": True, "op_offload": True, "swa_full": True,
                 "type_k": 2, "type_v": 8}, files=["m.gguf"]),
    dict(kwargs={**base_kwargs, "verbose": True},
         files=["m.gguf"], metadata={"tokenizer.chat_template": "TPL"}),
    dict(kwargs=dict(base_kwargs), files=["m.gguf"],
         metadata={"tokenizer.chat_template": "TPL"}, guess="chatml"),
    dict(kwargs=dict(base_kwargs), files=["m.gguf"],
         metadata={"tokenizer.chat_template.x": "XT"}, guess=None),
    dict(kwargs={**base_kwargs, "chat_format": "chatml", "chat_handler": "H"},
         files=["m.gguf"], metadata_fail=True),
    dict(kwargs={**base_kwargs, "draft_model": "D", "tokenizer": "TK"}, files=["m.gguf"]),
]
for case in cases:
    rows.append("\t".join(["init", base64.b64encode(wire(case)).decode(),
        base64.b64encode(wire(run_init(case))).decode()]))

fixture = root / "src/test/resources/python-0.3.36-init-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=1,
                functions=["Llama.__init__"],
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Pinned __init__ body over stub llama_cpp/internals/os/chat_format ports; param field writes, ExitStack enter order, prints and failure branches recorded.")
(root / "docs/python-init-contracts.json").write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
