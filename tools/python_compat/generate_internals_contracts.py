"""Execute the pinned _internals.py class bodies over a traced deterministic native port."""

import argparse
import ast
import base64
import contextlib
import ctypes
import dataclasses
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
namespace = {"ExitStack": contextlib.ExitStack, "ctypes": ctypes,
             "dataclass": dataclasses.dataclass, "field": dataclasses.field,
             "Optional": object, "LlamaGrammar": object}


def execute(nodes):
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
                 "pinned-internals", "exec"), namespace)


tree = ast.parse((args.source_root / "llama_cpp/_internals.py").read_text(encoding="utf-8"))
wanted = {"LlamaModel", "LlamaContext", "LlamaBatch", "LlamaSampler", "LlamaTokenDataArray",
          "LlamaSamplingParams", "LlamaSamplingContext", "CustomSampler"}
execute([node for node in tree.body if isinstance(node, ast.ClassDef) and node.name in wanted])
execute([node for node in tree.body
         if isinstance(node, ast.FunctionDef) and node.name == "normalize_embedding"])


class _RecField(list):
    """numpy field access returning a list supporting slice assignment."""

    def __setitem__(self, key, value):
        if isinstance(key, slice):
            list.__setitem__(self, key, list(value))
        else:
            list.__setitem__(self, key, value)


class FakeRec:
    """np.recarray stand-in: id/logit/p fields plus the ctypes data_as hook."""

    def __init__(self, n):
        self.id = _RecField([0] * n)
        self.logit = _RecField([0.0] * n)
        self.p = _RecField([0.0] * n)
        self.ctypes = types.SimpleNamespace(data_as=lambda ptr: Handle("data_ptr"))


class NumpyStub:
    """Just enough numpy for normalize_embedding and LlamaTokenDataArray."""

    intc = "intc"
    single = "single"

    class linalg:
        @staticmethod
        def norm(values):
            return float(sum(float(v) * float(v) for v in values) ** 0.5)

    @staticmethod
    def dtype(*args, **kwargs):
        return Handle("dtype")

    @staticmethod
    def recarray(shape, dtype=None):
        return FakeRec(shape[0])

    @staticmethod
    def arange(n, dtype=None):
        return _RecField(range(n))

    @staticmethod
    def zeros(n, dtype=None):
        return _RecField([0.0] * n)


namespace["np"] = NumpyStub


def custom_apply(cur_p):
    pass


namespace["custom_apply"] = custom_apply


def show(value):
    if callable(value):
        return getattr(value, "__name__", "fn")
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
        ns.llama_vocab_type = self.recording(
            "llama_vocab_type", lambda vocab: self.case.get("vocabType", 2))
        ns.llama_model_rope_freq_scale_train = self.recording(
            "llama_model_rope_freq_scale_train",
            lambda model: self.case.get("ropeScale", 0.25))
        ns.llama_vocab_get_text = self.recording(
            "llama_vocab_get_text",
            lambda vocab, token: bytes.fromhex(self.case.get("textHex", "e4bda0")))
        ns.llama_vocab_get_score = self.recording(
            "llama_vocab_get_score", lambda vocab, token: self.case.get("score", -1.5))
        ns.llama_vocab_get_attr = self.recording(
            "llama_vocab_get_attr", lambda vocab, token: self.case.get("attr", 3))
        for name in ["llama_vocab_eos", "llama_vocab_sep", "llama_vocab_nl",
                     "llama_vocab_fim_pre", "llama_vocab_fim_mid", "llama_vocab_fim_suf",
                     "llama_vocab_eot"]:
            setattr(ns, name, self.recording(
                name, lambda vocab, n=name: self.case.get(n, 0)))
        ns.llama_vocab_get_add_bos = self.recording(
            "llama_vocab_get_add_bos", lambda vocab: self.case.get("addBos", True))
        ns.llama_vocab_get_add_eos = self.recording(
            "llama_vocab_get_add_eos", lambda vocab: self.case.get("addEos", False))
        ns.llama_memory_seq_rm = self.recording(
            "llama_memory_seq_rm",
            lambda mem, seq, p0, p1: self.queue("seqRm") if "seqRm" in self.queues else True)
        ns.llama_memory_seq_cp = self.recording(
            "llama_memory_seq_cp", lambda mem, src, dst, p0, p1: None)
        ns.llama_memory_seq_keep = self.recording(
            "llama_memory_seq_keep", lambda mem, seq: None)
        ns.llama_memory_seq_add = self.recording(
            "llama_memory_seq_add", lambda mem, seq, p0, p1, shift: None)
        ns.llama_state_get_size = self.recording(
            "llama_state_get_size", lambda ctx: self.case.get("stateSize", 1024))
        ns.llama_encode = self.recording("llama_encode", self.encode)
        ns.llama_set_n_threads = self.recording(
            "llama_set_n_threads", lambda ctx, n, nb: None)
        ns.llama_pooling_type = self.recording(
            "llama_pooling_type", lambda ctx: self.case.get("pooling", 1))
        for name in ["llama_get_logits", "llama_get_logits_ith", "llama_get_embeddings",
                     "llama_get_embeddings_ith", "llama_get_embeddings_seq"]:
            setattr(ns, name, self.recording(
                name, lambda *a, n=name: Handle(n.replace("llama_", "") + "_ptr")))
        ns.llama_perf_context_reset = self.recording(
            "llama_perf_context_reset", lambda ctx: None)
        ns.llama_perf_context_print = self.recording(
            "llama_perf_context_print", lambda ctx: None)
        for name in ["llama_sampler_init_typical", "llama_sampler_init_temp_ext",
                     "llama_sampler_init_xtc", "llama_sampler_init_top_n_sigma",
                     "llama_sampler_init_mirostat", "llama_sampler_init_mirostat_v2",
                     "llama_sampler_init_grammar", "llama_sampler_init_grammar_lazy_patterns",
                     "llama_sampler_init_dry", "llama_sampler_init_logit_bias",
                     "llama_sampler_init_infill"]:
            setattr(ns, name, self.recording(name, self.spawn(name)))
        ns.llama_sampler_chain_n = self.recording(
            "llama_sampler_chain_n", lambda chain: self.case.get("chainN", 4))
        ns.llama_sampler_accept = self.recording(
            "llama_sampler_accept", lambda chain, token: None)
        ns.llama_sampler_reset = self.recording(
            "llama_sampler_reset", lambda chain: None)
        ns.llama_sampler_clone = self.recording(
            "llama_sampler_clone", self.spawn("llama_sampler_clone"))
        ns.llama_sampler_i = self.recording(
            "llama_sampler_i", lambda: types.SimpleNamespace(_hname="sampler_i"))
        ns.llama_sampler = self.recording(
            "llama_sampler", lambda: types.SimpleNamespace(_hname="sampler"))
        for name in ["llama_sampler_i_apply", "llama_sampler_i_name",
                     "llama_sampler_i_accept", "llama_sampler_i_reset",
                     "llama_sampler_i_clone", "llama_sampler_i_free"]:
            setattr(ns, name, self.recording(
                name, lambda arg, n=name: Handle("cfunc_" + n.rsplit("_", 1)[1])))

        class LogitBias(ctypes.Structure):
            _fields_ = [("token", ctypes.c_int32), ("bias", ctypes.c_float)]

        ns.llama_logit_bias = LogitBias
        ns.llama_token_data_p = "token_data_p"

        def token_data_array(data=None, size=None, sorted=None):
            self.trace.append(["llama_token_data_array", show(data), size, sorted])
            return types.SimpleNamespace(data=data, size=size, sorted=sorted)

        ns.llama_token_data_array = token_data_array
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

    def encode(self, ctx, batch):
        return self.queue("encode")

    def sample(self, chain, ctx, index):
        return self.queue("sample")


def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))


class FakeGrammar:
    """llama_grammar.LlamaGrammar stand-in; reset() is observable."""

    def __init__(self, trace, grammar="root ::= 'ab'", root="root"):
        self._grammar = grammar
        self._root = root
        self.trace = trace

    def reset(self):
        self.trace.append(["grammar_reset"])


def drive(case):
    trace = []
    stubs = Stubs(case, trace)
    namespace["llama_cpp"] = stubs.llama_cpp
    namespace["os"] = stubs.os
    namespace["warnings"] = stubs.warnings
    namespace["suppress_stdout_stderr"] = stubs.suppress
    real_pointer = ctypes.pointer
    ctypes.pointer = lambda obj: Handle("ptr:" + getattr(obj, "_hname", "obj"))
    results = []
    box = {}
    Model, Context, Batch, Sampler = (namespace[name] for name in
                                      ["LlamaModel", "LlamaContext", "LlamaBatch", "LlamaSampler"])
    TokenDataArray = namespace["LlamaTokenDataArray"]
    SamplingParams = namespace["LlamaSamplingParams"]
    SamplingContext = namespace["LlamaSamplingContext"]
    CustomSampler = namespace["CustomSampler"]
    grammar = FakeGrammar(trace)
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
            if name == "batchAdd":
                box["batch"].add_sequence(step[1], step[2], step[3])
                return "ok"
            if name == "batchDump":
                return box["batch"].batch.snapshot()
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
            if name == "vocabType": return box["model"].vocab_type()
            if name == "ropeScale": return box["model"].rope_freq_scale_train()
            if name == "getTensor": return box["model"].get_tensor(step[1])
            if name == "tokenText": return box["model"].token_get_text(step[1])
            if name == "tokenScore": return box["model"].token_get_score(step[1])
            if name == "tokenAttr": return box["model"].token_get_attr(step[1])
            if name == "tokenCls": return box["model"].token_cls()
            if name == "tokenSep": return box["model"].token_sep()
            if name == "tokenNl": return box["model"].token_nl()
            if name == "tokenPrefix": return box["model"].token_prefix()
            if name == "tokenMiddle": return box["model"].token_middle()
            if name == "tokenSuffix": return box["model"].token_suffix()
            if name == "tokenEot": return box["model"].token_eot()
            if name == "addBos": return box["model"].add_bos_token()
            if name == "addEos": return box["model"].add_eos_token()
            if name == "pooling": return box["ctx"].pooling_type()
            if name == "kvRm": return box["ctx"].kv_cache_seq_rm(*step[1:])
            if name == "kvCp":
                box["ctx"].kv_cache_seq_cp(*step[1:])
                return "ok"
            if name == "kvKeep":
                box["ctx"].kv_cache_seq_keep(*step[1:])
                return "ok"
            if name == "kvShift":
                box["ctx"].kv_cache_seq_shift(*step[1:])
                return "ok"
            if name == "stateSize": return box["ctx"].get_state_size()
            if name == "encode":
                box["ctx"].encode(box["batch"])
                return "ok"
            if name == "nThreads":
                box["ctx"].set_n_threads(*step[1:])
                return "ok"
            if name == "logits": return show(box["ctx"].get_logits())
            if name == "logitsIth": return show(box["ctx"].get_logits_ith(step[1]))
            if name == "emb": return show(box["ctx"].get_embeddings())
            if name == "embIth": return show(box["ctx"].get_embeddings_ith(step[1]))
            if name == "embSeq": return show(box["ctx"].get_embeddings_seq(step[1]))
            if name == "resetTimings":
                box["ctx"].reset_timings()
                return "ok"
            if name == "printTimings":
                box["ctx"].print_timings()
                return "ok"
            if name == "deprecated":
                return getattr(box["ctx"], step[1])(*step[2:])
            if name == "normalize":
                return namespace["normalize_embedding"](step[1])
            if name == "tda":
                box["tda"] = TokenDataArray(n_vocab=step[1])
                return "ok"
            if name == "tdaCopy":
                box["tda"].copy_logits(step[1])
                return "ok"
            if name == "tdaDump":
                tda = box["tda"]
                return [list(tda.candidates_data.id), list(tda.candidates_data.logit),
                        list(tda.candidates_data.p), tda.candidates.sorted, tda.candidates.size]
            if name == "sctx":
                box["sctx"] = SamplingContext(params=SamplingParams())
                return "ok"
            if name == "sctxGrammar":
                box["sctx"].grammar = grammar
                return "ok"
            if name == "sctxPrev":
                box["sctx"].prev.extend(step[1])
                return "ok"
            if name == "sctxReset":
                box["sctx"].reset()
                return "ok"
            if name == "sctxCp":
                box["sctx2"] = box["sctx"].cp()
                box["sctx2"].prev.append(99)
                return [list(box["sctx"].prev), list(box["sctx2"].prev),
                        box["sctx2"].params is box["sctx"].params]
            if name == "sctxLast":
                return box["sctx"].last()
            if name == "sctxPrevStr":
                return box["sctx"].prev_str(box["ctx"], step[1])
            if name == "sctxSample":
                return box["sctx"].sample(box["ctx"], step[1], None)
            if name == "sctxAccept":
                box["sctx"].accept(box["ctx"], step[1], step[2])
                return list(box["sctx"].prev)
            if name == "custom":
                box["custom"] = CustomSampler(apply_func=namespace["custom_apply"])
                return "ok"
            if name == "customGet":
                return show(box["custom"].get_sampler())
            if name == "typical": return box["sampler"].add_typical(*step[1:])
            if name == "tempExt": return box["sampler"].add_temp_ext(*step[1:])
            if name == "xtc": return box["sampler"].add_xtc(*step[1:])
            if name == "topNSigma": return box["sampler"].add_top_n_sigma(step[1])
            if name == "mirostat": return box["sampler"].add_mirostat(*step[1:])
            if name == "mirostatV2": return box["sampler"].add_mirostat_v2(*step[1:])
            if name == "grammarAdd":
                box["sampler"].add_grammar(box["model"], grammar)
                return "ok"
            if name == "grammarLazy":
                box["sampler"].add_grammar_lazy_patterns(
                    box["model"], grammar, step[1], step[2])
                return "ok"
            if name == "dry":
                box["sampler"].add_dry(box["model"], *step[1:])
                return "ok"
            if name == "logitBias":
                box["sampler"].add_logit_bias(step[1], step[2])
                return "ok"
            if name == "infill":
                box["sampler"].add_infill(box["model"])
                return "ok"
            if name == "addCustom":
                box["sampler"].add_custom(namespace["custom_apply"])
                return "ok"
            if name == "acceptTok":
                box["sampler"].accept(step[1])
                return "ok"
            if name == "resetSampler":
                box["sampler"].reset()
                return "ok"
            if name == "clone":
                box["sampler2"] = box["sampler"].clone()
                return "ok"
            raise AssertionError("unknown op " + name)
        results.append(capture(run))
    ctypes.pointer = real_pointer
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

# Model vocab/accessor passthroughs and the unconditional get_tensor error.
add("modelAttrs", path="m.gguf", exists={"m.gguf": True}, verbose=False,
    vocabType=2, ropeScale=0.03125, score=-2.75, attr=5, textHex="e4bda0e5a5bd",
    llama_vocab_eos=2, llama_vocab_sep=13, llama_vocab_nl=10, llama_vocab_fim_pre=21,
    llama_vocab_fim_mid=22, llama_vocab_fim_suf=23, llama_vocab_eot=24,
    addBos=True, addEos=False, bos=1,
    ops=[["model"], ["vocabType"], ["ropeScale"], ["tokenText", 5], ["tokenScore", 5],
         ["tokenAttr", 5], ["tokenCls"], ["tokenSep"], ["tokenNl"], ["tokenPrefix"],
         ["tokenMiddle"], ["tokenSuffix"], ["tokenEot"], ["addBos"], ["addEos"],
         ["getTensor", "blk.0.weight"], ["close", "model"]])
# Context memory ops: assert gate, negative seq clamp, queue-driven seq_rm result.
add("contextKv", path="m.gguf", exists={"m.gguf": True}, verbose=False,
    stateSize=4096, pooling=2, queues={"seqRm": [True, False]},
    ops=[["model"], ["context"], ["pooling"], ["kvRm", 3, 0, -1], ["kvRm", -2, 0, -1],
         ["kvCp", 1, 2, 0, 5], ["kvKeep", 1], ["kvShift", 1, 2, 8, -3],
         ["stateSize"], ["close", "ctx"], ["close", "model"]])
# Missing memory asserts before any native call.
add("contextKv", path="m.gguf", exists={"m.gguf": True}, verbose=False, memory=False,
    ops=[["model"], ["context"], ["kvRm", 0, 0, -1], ["kvCp", 0, 1, 0, 1],
         ["kvKeep", 0], ["kvShift", 0, 0, 1, 1], ["close", "model"]])
# encode return-code contract, thread setter, pointer getters and timings.
add("contextOps", path="m.gguf", exists={"m.gguf": True}, verbose=False,
    queues={"encode": [0, -1]},
    ops=[["model"], ["context"], ["batch", 4, 0, 1],
         ["batchSet", [10, 11], 0, False], ["encode"], ["encode"], ["nThreads", 4, 4],
         ["logits"], ["logitsIth", 2], ["emb"], ["embIth", 1], ["embSeq", 0],
         ["resetTimings"], ["printTimings"], ["close", "ctx"], ["close", "model"]])
# Deprecated sampling entry points raise NotImplementedError unchanged.
add("deprecated", path="m.gguf", exists={"m.gguf": True}, verbose=False,
    ops=[["model"], ["context"],
         ["deprecated", "set_rng_seed", 7],
         ["deprecated", "sample_repetition_penalties", "cand", "last", 8, 1.1, 0.0, 0.0],
         ["deprecated", "sample_softmax", "cand"],
         ["deprecated", "sample_top_k", "cand", 40, 1],
         ["deprecated", "sample_top_p", "cand", 0.9, 1],
         ["deprecated", "sample_min_p", "cand", 0.05, 1],
         ["deprecated", "sample_typical", "cand", 1.0, 1],
         ["deprecated", "sample_temp", "cand", 0.8],
         ["deprecated", "sample_grammar", "cand", "gram"],
         ["deprecated", "sample_token_mirostat", "cand", 5.0, 0.1, 100, "mu"],
         ["deprecated", "sample_token_mirostat_v2", "cand", 5.0, 0.1, "mu"],
         ["deprecated", "sample_token_greedy", "cand"],
         ["deprecated", "sample_token", "cand"],
         ["deprecated", "grammar_accept_token", "gram", 5],
         ["close", "ctx"], ["close", "model"]])
# add_sequence appends with its own seq_id and restarts positions.
add("batch", verbose=False,
    ops=[["batch", 8, 0, 2], ["batchSet", [10, 11], 3, False], ["batchAdd", [20, 21, 22], 5, True],
         ["batchDump"], ["close", "batch"]])
# normalize_embedding: zero norm passes through, otherwise scaled elementwise.
add("normalize", ops=[["normalize", [3.0, 4.0]], ["normalize", [0.0, 0.0]],
                      ["normalize", [1.5, -2.0, 0.5]]])
# LlamaTokenDataArray: recarray layout, copy_logits resets id/p/sorted/size.
add("tokenDataArray",
    ops=[["tda", 4], ["tdaDump"], ["tdaCopy", [0.5, -1.0, 2.5, 0.0]], ["tdaDump"],
         ["tdaCopy", [9.0, 8.0, 7.0, 6.0]], ["tdaDump"]])
# LlamaSamplingContext: reset rebinds and resets grammar; cp shares scalars, copies lists.
add("samplingContext", path="m.gguf", exists={"m.gguf": True}, verbose=False,
    queues={"piece": [["e4bda0", 3], ["e5a5bd", 3], ["41", 1]]},
    ops=[["model"], ["context"], ["sctx"], ["sctxPrev", [10, 11, 12]], ["sctxLast"],
         ["sctxPrevStr", 2], ["sctxAccept", 13, True], ["sctxCp"], ["sctxGrammar"],
         ["sctxReset"], ["sctxLast"], ["sctxSample", 0],
         ["close", "ctx"], ["close", "model"]])
# CustomSampler: sampler_i field order, literal-0 optional slots, pointer plumbing.
add("customSampler", chainN=4,
    ops=[["sampler"], ["addCustom"], ["close", "sampler"]])
add("customSampler", chainN=1,
    ops=[["custom"], ["customGet"]])
# Remaining add_* passthroughs in call order; dict order kept for logit_bias.
add("samplerAdd", path="m.gguf", exists={"m.gguf": True}, verbose=False, chainN=3,
    ops=[["model"], ["sampler"], ["typical", 0.9], ["typical", 0.9, 2],
         ["tempExt", 0.7, 0.1, 1.2], ["xtc", 0.4, 0.8, 5, 4242],
         ["topNSigma", 2.5], ["mirostat", 32000, 99, 5.0, 0.1, 100],
         ["mirostatV2", 1001, 5.0, 0.1], ["grammarAdd"],
         ["grammarLazy", ["abc", "d"], [7, 8]],
         ["dry", 0.8, 1.75, 2, 20, ["\n", "##"]],
         ["logitBias", 6, {5: -1.5, 2: 3.25}], ["infill"],
         ["acceptTok", 17], ["resetSampler"], ["close", "sampler"], ["close", "model"]])
# clone succeeds without custom samplers and frees only the clone on close.
add("samplerClone",
    ops=[["sampler"], ["clone"], ["close", "sampler2"], ["close", "sampler"]])
# clone with custom samplers raises NotImplementedError before the native call.
add("samplerClone", chainN=2,
    ops=[["sampler"], ["addCustom"], ["clone"], ["close", "sampler"]])

gc.disable()
rows = []
for index, case in enumerate(cases):
    try:
        # Flush cycles from earlier cases into the previous stub trace so cyclic
        # garbage collection can never leak frees into this case's recording.
        gc.collect()
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
    "LlamaModel.__init__.free_model", "LlamaModel.__del__", "LlamaModel.vocab_type",
    "LlamaModel.rope_freq_scale_train", "LlamaModel.get_tensor",
    "LlamaModel.token_get_text", "LlamaModel.token_get_score", "LlamaModel.token_get_attr",
    "LlamaModel.token_cls", "LlamaModel.token_sep", "LlamaModel.token_nl",
    "LlamaModel.token_prefix", "LlamaModel.token_middle", "LlamaModel.token_suffix",
    "LlamaModel.token_eot", "LlamaModel.add_bos_token", "LlamaModel.add_eos_token",
    "LlamaContext.__init__.free_ctx", "LlamaContext.__del__", "LlamaContext.pooling_type",
    "LlamaContext.kv_cache_seq_rm", "LlamaContext.kv_cache_seq_cp",
    "LlamaContext.kv_cache_seq_keep", "LlamaContext.kv_cache_seq_shift",
    "LlamaContext.get_state_size", "LlamaContext.encode", "LlamaContext.set_n_threads",
    "LlamaContext.get_logits", "LlamaContext.get_logits_ith", "LlamaContext.get_embeddings",
    "LlamaContext.get_embeddings_ith", "LlamaContext.get_embeddings_seq",
    "LlamaContext.set_rng_seed", "LlamaContext.sample_repetition_penalties",
    "LlamaContext.sample_softmax", "LlamaContext.sample_top_k", "LlamaContext.sample_top_p",
    "LlamaContext.sample_min_p", "LlamaContext.sample_typical", "LlamaContext.sample_temp",
    "LlamaContext.sample_grammar", "LlamaContext.sample_token_mirostat",
    "LlamaContext.sample_token_mirostat_v2", "LlamaContext.sample_token_greedy",
    "LlamaContext.sample_token", "LlamaContext.grammar_accept_token",
    "LlamaContext.reset_timings", "LlamaContext.print_timings",
    "LlamaBatch.__init__.free_batch", "LlamaBatch.__del__", "LlamaBatch.n_tokens",
    "LlamaBatch.add_sequence", "normalize_embedding",
    "LlamaTokenDataArray.__init__", "LlamaTokenDataArray.copy_logits",
    "LlamaSamplingContext.reset", "LlamaSamplingContext.cp", "LlamaSamplingContext.last",
    "LlamaSamplingContext.prev_str", "LlamaSamplingContext.sample",
    "LlamaSamplingContext.accept", "CustomSampler.__init__",
    "CustomSampler.__init__.apply_wrapper", "CustomSampler.__init__.free_wrapper",
    "CustomSampler.get_sampler", "LlamaSampler.__init__.free_sampler",
    "LlamaSampler.__del__", "LlamaSampler.add_typical", "LlamaSampler.add_temp_ext",
    "LlamaSampler.add_xtc", "LlamaSampler.add_top_n_sigma", "LlamaSampler.add_mirostat",
    "LlamaSampler.add_mirostat_v2", "LlamaSampler.add_grammar",
    "LlamaSampler.add_grammar_lazy_patterns", "LlamaSampler.add_dry",
    "LlamaSampler.add_logit_bias", "LlamaSampler.add_infill", "LlamaSampler.add_custom",
    "LlamaSampler.accept", "LlamaSampler.clone", "LlamaSampler.clone.free_sampler",
]
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=len(functions),
                functions=functions,
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Complete hash-verified _internals class bodies over a traced deterministic native port; call order, buffers and error texts come from the original functions. Real llama.cpp behavior continues to be verified natively.")
(root / "docs/python-internals-contracts.json").write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
