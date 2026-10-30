"""Execute the pinned _internals.py class bodies over a traced deterministic native port."""

import argparse
import ast
import base64
import contextlib
import ctypes
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
namespace = {"ExitStack": contextlib.ExitStack, "ctypes": ctypes}


def execute(nodes):
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
                 "pinned-internals", "exec"), namespace)


tree = ast.parse((args.source_root / "llama_cpp/_internals.py").read_text(encoding="utf-8"))
wanted = {"LlamaModel", "LlamaContext", "LlamaBatch", "LlamaSampler"}
execute([node for node in tree.body if isinstance(node, ast.ClassDef) and node.name in wanted])


def show(value):
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, Handle):
        return value.name
    if isinstance(value, FakeBatch):
        return ["batch", *value.snapshot()]
    if isinstance(value, ctypes._SimpleCData):
        return value.value
    if isinstance(value, ctypes.Array):
        return "buf" + str(len(value))
    if isinstance(value, (list, tuple)):
        return [show(item) for item in value]
    return value


class Handle:
    def __init__(self, name):
        self.name = name


class FakeBatch:
    """ctypes llama_batch stand-in; lists reproduce negative index semantics."""

    def __init__(self, name, n_tokens, embd, n_seq_max):
        self.name = name
        self.capacity = n_tokens
        self.n_tokens = 0
        self.token = [0] * n_tokens if embd == 0 else None
        self.embd = [0.0] * (n_tokens * embd) if embd else None
        self.pos = [0] * n_tokens
        self.seq_id = [[0] * n_seq_max for _ in range(n_tokens)]
        self.n_seq_id = [0] * n_tokens
        self.logits = [False] * n_tokens

    def snapshot(self):
        return [self.n_tokens, list(self.token or []), list(self.pos),
                [list(seq) for seq in self.seq_id], list(self.n_seq_id), list(self.logits)]


class Stubs:
    """Per-case scripted llama_cpp namespace recording every native call."""

    def __init__(self, case, trace):
        self.case = case
        self.trace = trace
        self.spawns = {}
        self.queues = {name: list(values) for name, values in case.get("queues", {}).items()}
        ns = types.SimpleNamespace()
        ns.llama_token = ctypes.c_int32
        for name in ["llama_model_default_params", "llama_context_default_params",
                     "llama_sampler_chain_default_params"]:
            setattr(ns, name, self.recording(name, lambda n=name: Handle(n)))
        ns.llama_model_load_from_file = self.recording(
            "llama_model_load_from_file",
            lambda path, params: Handle("model") if self.case.get("load", True) else None)
        ns.llama_model_get_vocab = self.recording(
            "llama_model_get_vocab",
            lambda model: Handle("vocab") if self.case.get("vocab", True) else None)
        ns.llama_model_free = self.recording("llama_model_free", lambda model: None)
        ns.llama_model_desc = self.recording(
            "llama_model_desc", self.write_c(self.case.get("desc", "qwen35")))
        ns.llama_model_n_ctx_train = self.recording(
            "llama_model_n_ctx_train", lambda model: self.case.get("nCtxTrain", 8))
        ns.llama_vocab_bos = self.recording("llama_vocab_bos", lambda vocab: self.case.get("bos", 11))
        ns.llama_tokenize = self.recording("llama_tokenize", self.tokenize)
        ns.llama_token_to_piece = self.recording("llama_token_to_piece", self.piece)
        ns.llama_model_meta_count = self.recording(
            "llama_model_meta_count", lambda model: len(self.case.get("metas", [])))
        ns.llama_model_meta_key_by_index = self.recording(
            "llama_model_meta_key_by_index", self.meta(0))
        ns.llama_model_meta_val_str_by_index = self.recording(
            "llama_model_meta_val_str_by_index", self.meta(1))
        ns.llama_init_from_model = self.recording(
            "llama_init_from_model",
            lambda model, params: Handle("ctx") if self.case.get("init", True) else None)
        ns.llama_get_memory = self.recording(
            "llama_get_memory", lambda ctx: Handle("mem") if self.case.get("memory", True) else None)
        ns.llama_memory_clear = self.recording("llama_memory_clear", lambda mem, data: None)
        ns.llama_free = self.recording("llama_free", lambda ctx: None)
        ns.llama_decode = self.recording("llama_decode", self.decode)
        ns.llama_batch_init = self.recording(
            "llama_batch_init",
            lambda n, embd, nseq: FakeBatch("batch", n, embd, nseq)
            if self.case.get("batchInit", True) else None)
        ns.llama_batch_free = self.recording("llama_batch_free", lambda batch: None)
        ns.llama_sampler_chain_init = self.recording(
            "llama_sampler_chain_init", lambda params: Handle("chain"))
        for name in ["llama_sampler_init_greedy", "llama_sampler_init_top_k",
                     "llama_sampler_init_top_p", "llama_sampler_init_min_p",
                     "llama_sampler_init_temp", "llama_sampler_init_dist",
                     "llama_sampler_init_penalties"]:
            setattr(ns, name, self.recording(name, self.spawn(name)))
        ns.llama_sampler_chain_add = self.recording("llama_sampler_chain_add",
                                                    lambda chain, child: None)
        ns.llama_sampler_chain_remove = self.recording("llama_sampler_chain_remove",
                                                       lambda chain, i: Handle("removed"))
        ns.llama_sampler_get_seed = self.recording(
            "llama_sampler_get_seed", lambda chain: self.case.get("seed", 0))
        ns.llama_sampler_sample = self.recording("llama_sampler_sample", self.sample)
        ns.llama_sampler_free = self.recording("llama_sampler_free", lambda chain: None)
        self.llama_cpp = ns
        self.os = types.SimpleNamespace(path=types.SimpleNamespace(exists=self.exists))
        self.warnings = types.SimpleNamespace(warn=self.warn)

    def exists(self, path):
        return self.case.get("exists", {}).get(path, False)

    def warn(self, message, category=None, stacklevel=1):
        self.trace.append(["warn", message, category.__name__])

    @contextlib.contextmanager
    def suppress(self, disable=True):
        self.trace.append(["suppress_stdout_stderr", disable])
        yield

    def recording(self, name, impl):
        def call(*values):
            self.trace.append([name] + [show(value) for value in values])
            return impl(*values)
        return call

    def queue(self, name):
        values = self.queues[name]
        if not values:
            raise AssertionError("empty queue " + name)
        return values.pop(0)

    def spawn(self, name):
        def make(*values):
            self.spawns[name] = self.spawns.get(name, 0) + 1
            return Handle(name + "#" + str(self.spawns[name]))
        return make

    def write_c(self, text):
        def fill(*values):
            data = text.encode("utf-8")
            buf = values[-2]
            buf[:len(data) + 1] = data + b"\0"
            return len(data)
        return fill

    def tokenize(self, vocab, text, n_text, tokens, n_max, add_bos, special):
        fill, result = self.queue("tokenize")
        for i, token in enumerate(fill):
            tokens[i] = token
        return result

    def piece(self, vocab, token, buf, size, lstrip, special):
        data_hex, result = self.queue("piece")
        data = bytes.fromhex(data_hex)
        buf[:len(data)] = data
        return result

    def meta(self, which):
        def read(model, index, buf, size):
            key, value, grow = self.case["metas"][index]
            text = (key if which == 0 else value).encode("utf-8")
            needed = len(text) + 1 if (grow >> which) & 1 else len(text)
            if needed > size:
                return needed
            buf[:len(text) + 1] = text + b"\0"
            return len(text)
        return read

    def decode(self, ctx, batch):
        return self.queue("decode")

    def sample(self, chain, ctx, index):
        return self.queue("sample")


def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))


def drive(case):
    trace = []
    stubs = Stubs(case, trace)
    namespace["llama_cpp"] = stubs.llama_cpp
    namespace["os"] = stubs.os
    namespace["warnings"] = stubs.warnings
    namespace["suppress_stdout_stderr"] = stubs.suppress
    results = []
    box = {}
    Model, Context, Batch, Sampler = (namespace[name] for name in
                                      ["LlamaModel", "LlamaContext", "LlamaBatch", "LlamaSampler"])
    for step in case["ops"]:
        name = step[0]

        def run():
            if name == "model":
                box["model"] = Model(path_model=case.get("path", "m.gguf"),
                                     params=Handle("mparams"), verbose=case.get("verbose", True))
                return "ok"
            if name == "context":
                box["ctx"] = Context(model=box["model"], params=Handle("cparams"),
                                     verbose=case.get("verbose", True))
                return "ok"
            if name == "batch":
                box["batch"] = Batch(n_tokens=step[1], embd=step[2], n_seq_max=step[3],
                                     verbose=case.get("verbose", True))
                return "ok"
            if name == "sampler":
                box["sampler"] = Sampler()
                return "ok"
            if name == "desc": return box["model"].desc()
            if name == "metadata": return box["model"].metadata()
            if name == "modelParams": return Model.default_params().name
            if name == "contextParams": return Context.default_params().name
            if name == "tokenize": return box["model"].tokenize(bytes.fromhex(step[1]), step[2], step[3])
            if name == "piece": return box["model"].token_to_piece(step[1], step[2]).hex()
            if name == "detok": return box["model"].detokenize(step[1], step[2]).hex()
            if name == "kvClear": return box["ctx"].kv_cache_clear()
            if name == "decode":
                box["ctx"].decode(box["batch"])
                return "ok"
            if name == "batchSet":
                box["batch"].set_batch(step[1], step[2], step[3])
                return "ok"
            if name == "batchReset": return box["batch"].reset()
            if name == "batchTokens": return box["batch"].n_tokens()
            if name == "greedy": return box["sampler"].add_greedy()
            if name == "dist": return box["sampler"].add_dist(step[1])
            if name == "softmax": return box["sampler"].add_softmax()
            if name == "topK": return box["sampler"].add_top_k(step[1])
            if name == "topP": return box["sampler"].add_top_p(*step[1:])
            if name == "minP": return box["sampler"].add_min_p(*step[1:])
            if name == "temp": return box["sampler"].add_temp(step[1])
            if name == "penalties": return box["sampler"].add_penalties(*step[1:])
            if name == "getSeed": return box["sampler"].get_seed()
            if name == "sample": return box["sampler"].sample(box["ctx"])
            if name == "sampleAt": return box["sampler"].sample(box["ctx"], step[1])
            if name == "seedSamplers":
                target = box["model"] if step[1] == "model" else box["sampler"]
                target.sampler = Handle("ownerChain")
                for index, custom in step[2]:
                    target.custom_samplers.append((index, Handle(custom)))
                return "ok"
            if name == "close":
                box[step[1]].close()
                return "ok"
            raise AssertionError("unknown op " + name)
        results.append(capture(run))
    return dict(results=results, trace=trace)


cases = []


def add(kind, **case):
    case["kind"] = kind
    cases.append(case)


# LlamaModel.__init__ / close happy path plus close-before/after-close double call.
add("model", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False,
    metas=[["key.a", "v1", 0], ["key.b", "v2", 0]],
    queues={"tokenize": [[[5, 6, 7], 3]],
            "piece": [["e4bda0", 3], ["20", 1], ["61", 1], ["62", 1], ["63", 1], ["64", 1]]},
    ops=[["model"], ["desc"], ["metadata"], ["modelParams"], ["tokenize", "e4bda0e5a5bd", False, True],
         ["piece", 9, False], ["detok", [11, 2, 3], False], ["detok", [2, 3], True],
         ["close", "model"], ["close", "model"]])
# verbose=True keeps the suppress flag order; empty metadata and BOS detok strip.
add("model", path="m.gguf", exists={"m.gguf": True}, verbose=True, metas=[], bos=7,
    queues={"piece": [["20", 1], ["4142", 2]]},
    ops=[["model"], ["desc"], ["detok", [7, 8], False], ["close", "model"]])
# Missing path, failed load and failed vocab each raise ValueError with the original text.
add("model", path="models/missing.gguf", exists={}, verbose=True, ops=[["model"]])
add("model", path="models/null.gguf", exists={"models/null.gguf": True}, load=False, verbose=False,
    ops=[["model"]])
add("model", path="models/novocab.gguf", exists={"models/novocab.gguf": True}, vocab=False,
    verbose=True, ops=[["model"]])
# Tokenize negative-retry success and persistent failure with bytes repr in the message.
add("model", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False,
    nCtxTrain=2, queues={"tokenize": [[[], -4], [[1, 2, 3, 4], 4]]},
    ops=[["model"], ["tokenize", "61626364", True, False], ["close", "model"]])
add("model", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False,
    nCtxTrain=1, queues={"tokenize": [[[], -2], [[], -1]]},
    ops=[["model"], ["tokenize", "61220a", False, False]])
# token_to_piece keeps the full 32 byte buffer; detok asserts when n exceeds the buffer.
add("model", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False,
    queues={"piece": [["6162000000", 2], ["ff", 33]]},
    ops=[["model"], ["piece", 4, True], ["detok", [4], False], ["close", "model"]])
# Metadata growth on both the key and the value side.
add("model", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False,
    metas=[["k" * 2000, "v", 1], ["kk", "w" * 3000, 2]],
    ops=[["model"], ["metadata"], ["close", "model"]])
# Model close removes externally attached custom samplers in reverse before free.
add("model", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False,
    ops=[["model"], ["seedSamplers", "model", [[0, "cs0"], [3, "cs1"]]], ["close", "model"]])
# Context: kv clear with and without memory, decode codes, close order via model.
add("context", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False,
    queues={"decode": [0, 1]},
    ops=[["model"], ["context"], ["kvClear"], ["batch", 4, 0, 1],
         ["batchSet", [10, 11], 3, False], ["decode"], ["decode"], ["close", "ctx"],
         ["close", "model"]])
add("context", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=True,
    memory=False, queues={"decode": [-1]},
    ops=[["model"], ["context"], ["kvClear"], ["batch", 2, 0, 1],
         ["batchSet", [5], 0, True], ["decode"], ["modelParams"], ["contextParams"],
         ["close", "model"]])
add("context", path="models/ok.gguf", exists={"models/ok.gguf": True}, verbose=False, init=False,
    ops=[["model"], ["context"], ["close", "model"]])
# LlamaBatch field writes: seq/pos/logits including the final forced flag and reset.
add("batch", verbose=False, queues={},
    ops=[["batch", 4, 0, 1], ["batchSet", [10, 11], 5, False], ["batchTokens"], ["batchReset"],
         ["batchTokens"], ["close", "batch"]])
add("batch", verbose=True,
    ops=[["batch", 2, 0, 2], ["batchSet", [1, 2, 3, 4], 0, True], ["close", "batch"]])
# Sampler chain construction order, seed/sample passthrough and deprecation warning.
add("sampler", path="m.gguf", exists={"m.gguf": True}, verbose=False, seed=4294967295,
    queues={"sample": [42, 7]},
    ops=[["model"], ["context"], ["sampler"], ["greedy"], ["dist", 1234], ["softmax"], ["topK", 9],
         ["topP", 0.5], ["topP", 0.5, 2], ["minP", 0.1, 3], ["temp", 0.8],
         ["penalties", 100, 8, 1.5, 0.2, 0.0], ["getSeed"], ["sample"], ["sampleAt", 0],
         ["close", "sampler"]])
add("sampler", path="m.gguf", exists={"m.gguf": True}, verbose=True, seed=0, queues={"sample": [0]},
    ops=[["sampler"], ["seedSamplers", "sampler", [[1, "cs0"], [0, "cs1"]]], ["close", "sampler"]])

rows = []
for index, case in enumerate(cases):
    try:
        rows.append("\t".join([case["kind"], base64.b64encode(wire(case)).decode(),
                               base64.b64encode(wire(drive(case))).decode()]))
    except Exception:
        print("case", index, case["kind"], file=sys.stderr)
        raise
fixture = root / "src/test/resources/python-0.3.36-internals-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
functions = [
    "LlamaModel.__init__", "LlamaModel.close", "LlamaModel.desc", "LlamaModel.tokenize",
    "LlamaModel.token_to_piece", "LlamaModel.detokenize", "LlamaModel.metadata",
    "LlamaModel.default_params", "LlamaContext.__init__", "LlamaContext.close",
    "LlamaContext.kv_cache_clear", "LlamaContext.decode", "LlamaContext.default_params",
    "LlamaBatch.__init__", "LlamaBatch.close", "LlamaBatch.reset", "LlamaBatch.set_batch",
    "LlamaSampler.__init__", "LlamaSampler.close", "LlamaSampler.add_greedy",
    "LlamaSampler.add_dist", "LlamaSampler.add_softmax", "LlamaSampler.add_top_k",
    "LlamaSampler.add_top_p", "LlamaSampler.add_min_p", "LlamaSampler.add_temp",
    "LlamaSampler.add_penalties", "LlamaSampler.get_seed", "LlamaSampler.sample",
]
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=len(functions),
                functions=functions,
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Complete hash-verified _internals class bodies over a traced deterministic native port; call order, buffers and error texts come from the original functions. Real llama.cpp behavior continues to be verified natively.")
(root / "docs/python-internals-contracts.json").write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
