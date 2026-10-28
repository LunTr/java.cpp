"""Execute pinned eval/sample/generate/save/load with stateful native test doubles."""
import argparse
import ast
import base64
import ctypes
import hashlib
import json
import pathlib
import sys
import types
import contextlib
import io
import numpy as np
from contract_wire import wire

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for item in audit["baseline"]["files"]:
    data = (args.source_root / item["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == item["sha"]
names = {"_input_ids", "_scores", "reset", "eval", "sample", "generate", "save_state", "load_state"}
tree = ast.parse((args.source_root / "llama_cpp/llama.py").read_text(encoding="utf-8"))
llama = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Llama")
llama.body = [n for n in llama.body if isinstance(n, ast.FunctionDef) and n.name in names]
state = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "LlamaState")
native = types.SimpleNamespace(ctypes=ctypes)
namespace = {"np": np, "ctypes": ctypes, "llama_cpp": native, "sys": sys}
future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future, llama, state], type_ignores=[])), "pinned-llama-execution", "exec"), namespace)
Llama = namespace["Llama"]
rows = []
counts = {}


def capture(fn):
    try: return fn()
    except Exception as error: return {"error": type(error).__name__, "message": str(error)}


def run(arg):
    obj = Llama.__new__(Llama)
    trace, logs = [], io.StringIO()
    obj.input_ids = np.array(arg["ids"], dtype=np.intc)
    obj.scores = np.array(arg["scores"], dtype=np.single)
    obj.n_tokens, obj.n_batch, obj._n_ctx, obj._n_vocab = arg["cursor"], arg["batch"], len(arg["ids"]), len(arg["scores"][0])
    obj._requires_eval, obj._logits_all, obj.verbose = False, arg["all"], True
    obj._is_recurrent, obj._is_hybrid, obj._seed = False, False, int(arg["seed"])
    def draft(tokens):
        trace.append(["draft", tokens.tolist()])
        if arg["failure"] == "draft": raise RuntimeError("draft failed")
        return np.array(arg["draft"], dtype=np.intc)
    obj.draft_model = draft if arg["draft"] is not None else None
    decoded, sampled = [], 0

    def remove(seq, start, end):
        trace.append(["remove", start]); return arg["remove"]
    def setBatch(batch, n_past, logits_all):
        decoded[:] = batch
        trace.append(["decode", list(batch), n_past, logits_all])
    def decode(batch):
        if arg["failure"] == "decode": raise RuntimeError("llama_decode returned -7")
    def logits():
        trace.append(["logits"])
        if arg["failure"] == "logits": raise RuntimeError("logits unavailable")
        values = [float(t) + col / 4 for t in decoded for col in range(obj._n_vocab)]
        obj._live = (ctypes.c_float * len(values))(*values)
        return obj._live
    def sample(ctx, index):
        nonlocal sampled
        trace.append(["sample", index])
        if arg["failure"] == "sample": raise RuntimeError("sample failed")
        value = arg["sampled"][sampled % len(arg["sampled"])]
        sampled += 1
        return value
    def factory(**kwargs):
        trace.append(["factory"]); return types.SimpleNamespace(sample=sample)
    obj._ctx = types.SimpleNamespace(ctx=object(), kv_cache_seq_rm=remove, decode=decode, get_logits=logits)
    obj.ctx = obj._ctx.ctx
    obj._batch = types.SimpleNamespace(set_batch=setBatch)
    obj._init_sampler = factory
    obj._sampler = None

    def stateSize(ctx): trace.append(["stateSize"]); return arg["size"]
    def stateCopy(ctx, target, size):
        trace.append(["copy", size])
        for i in range(size): target[i] = i + 1
        return arg["count"]
    def stateSet(ctx, data, size): trace.append(["restore", bytes(data).hex()]); return arg["count"]
    native.llama_state_get_size, native.llama_state_get_data, native.llama_state_set_data = stateSize, stateCopy, stateSet

    def stateValue(saved):
        return {"ids": saved.input_ids.tolist(), "scores": saved.scores.tolist(), "cursor": saved.n_tokens,
                "data": saved.llama_state.hex(), "size": str(saved.llama_state_size), "seed": str(saved.seed)}

    result = None
    with contextlib.redirect_stderr(logs):
        kind = arg["kind"]
        if kind == "eval": result = capture(lambda: obj.eval(arg["tokens"]))
        elif kind == "sample":
            result = [capture(lambda: obj.sample(idx=arg["index"])) for _ in range(2)]
        elif kind == "save": result = capture(lambda: stateValue(obj.save_state()))
        elif kind == "load":
            saved = namespace["LlamaState"](np.array(arg["savedIds"], dtype=np.intc), np.array(arg["savedScores"], dtype=np.single),
                arg["savedCursor"], bytes.fromhex(arg["data"]), arg["size"], int(arg["seed"]))
            result = capture(lambda: obj.load_state(saved))
        elif kind == "generate":
            def stopping(ids, scores):
                trace.append(["stop", ids.tolist(), scores.tolist()])
                if arg["failure"] == "stop": raise RuntimeError("stop failed")
                return len(ids) >= arg["stopAfter"]
            gen = obj.generate(arg["tokens"], reset=arg["reset"], stopping_criteria=stopping if arg["stopAfter"] is not None else None)
            result = []
            for sent in arg["sends"]:
                def advance():
                    try: return gen.send(sent)
                    except StopIteration: return None
                result.append(capture(advance))
            gen.close()
    return {"result": result, "trace": trace, "logs": logs.getvalue().splitlines(),
            "ids": obj.input_ids.tolist(), "scores": obj.scores.tolist(), "cursor": obj.n_tokens,
            "requiresEval": obj._requires_eval, "seed": str(obj._seed), "sampler": obj._sampler is not None}


def plain(value):
    if isinstance(value, np.generic): return value.item()
    if isinstance(value, dict): return {key: plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)): return [plain(item) for item in value]
    return value


def record(arg):
    result = run(arg)
    rows.append("\t".join(base64.b64encode(wire(plain(value))).decode() for value in [arg, result]))
    counts[arg["kind"]] = counts.get(arg["kind"], 0) + 1


base = {"ids": [1, 2, 3, 4, 5, 6, 7, 8], "scores": [[-1, 2, 0], [3, -4, 5], [6, 0, -7], [8, 9, 10],
        [1, 1, 1], [2, 2, 2], [3, 3, 3], [4, 4, 4]], "cursor": 0, "batch": 2, "all": True,
        "seed": str(2**100), "remove": True, "failure": "", "tokens": [9, 10, 11], "index": None,
        "sampled": [3, 5, 7], "size": 4, "count": 3, "savedIds": [9, 8, 7, 6], "savedScores": [[11, 12, 13], [14, 15, 16]],
        "savedCursor": 2, "data": "0102030405", "reset": True, "sends": [None, None, None], "draft": None, "stopAfter": None}
for cursor in [0, 2, 7]:
    for batch in [0, -1, 1, 2, 8]:
        for allLogits in [False, True]:
            for failure in ["", "decode", "logits"]:
                record(dict(base, kind="eval", cursor=cursor, batch=batch, all=allLogits, failure=failure))
for cursor in [0, 1, 4]:
    for index in [None, -1, 0, 5]:
        for failure in ["", "sample"]:
            record(dict(base, kind="sample", cursor=cursor, index=index, failure=failure))
for count in [0, 2, 4, 5]:
    for cursor in [0, 2, 8]: record(dict(base, kind="save", cursor=cursor, count=count))
for size in [-1, 0, 3, 5, 6]:
    for count in [0, 3, 5]: record(dict(base, kind="load", size=size, count=count))
for shape in [[[1, 2, 3]], [[1]], [[1, 2], [3, 4]], [[1, 2, 3]] * 3]:
    record(dict(base, kind="load", savedScores=shape))
for cursor in [0, 2, 3]:
    for prompt in [[1, 2, 3], [1, 2], [9, 10]]:
        for remove in [False, True]:
            for reset in [False, True]:
                record(dict(base, kind="generate", cursor=cursor, tokens=prompt, remove=remove, reset=reset))
record(dict(base, kind="generate", sends=[[1], None, None]))
record(dict(base, kind="generate", sends=[None, [8, 9], None]))
for draft in [[], [3], [5, 7], [9, 9, 9]]:
    for stop in [None, 2, 4, 8]:
        record(dict(base, kind="generate", draft=draft, stopAfter=stop))
for failure in ["decode", "logits", "sample", "stop", "draft"]:
    record(dict(base, kind="generate", draft=[5], stopAfter=8, failure=failure))

fixture = root / "src/test/resources/python-0.3.36-execution-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = {"sourceCommit": audit["baseline"]["commit"], "fixtureRuntime": sys.version, "numpyVersion": np.__version__,
            "caseCount": len(rows), "casesByOperation": counts, "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "method": "Complete pinned eval/sample/generate/save/load bodies; tracing execution and sampler doubles; compare errors, diagnostics, final arrays, cursor, seed and sampler retention. Sampling-policy construction remains separately mapped."}
(root / "docs/python-execution-contracts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
