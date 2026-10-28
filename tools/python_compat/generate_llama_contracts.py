"""Execute eight complete, hash-verified Llama methods with explicit state/native test doubles."""

import argparse
import ast
import base64
import collections
import hashlib
import json
import pathlib
import random
import sys
import types
import warnings

import numpy as np

from contract_wire import wire

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
assert sys.version_info[:2] == (3, 13)
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for item in audit["baseline"]["files"]:
    data = (args.source_root / item["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"], item["path"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == item["sha"], item["path"]

names = {"_input_ids", "_scores", "eval_tokens", "eval_logits", "set_cache", "set_seed", "reset", "logits_to_logprobs"}
tree = ast.parse((args.source_root / "llama_cpp/llama.py").read_text(encoding="utf-8"))
llama = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Llama")
methods = [node for node in llama.body if isinstance(node, ast.FunctionDef) and node.name in names]
assert len(methods) == len(names) == 8
llama.body = methods
future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
native = types.SimpleNamespace()
namespace = {"np": np, "deque": collections.deque, "llama_cpp": native}
exec(compile(ast.fix_missing_locations(ast.Module(body=[future, llama], type_ignores=[])),
             "pinned-python-0.3.36-llama", "exec"), namespace)
Llama = namespace["Llama"]
rows = []
counts = collections.Counter()


def capture(operation):
    try:
        return operation()
    except Exception as error:
        return {"error": type(error).__name__, "message": str(error)}


def record(kind, argument, operation):
    with warnings.catch_warnings(), np.errstate(all="ignore"):
        warnings.simplefilter("ignore", RuntimeWarning)
        value = capture(operation)
    rows.append("\t".join([kind, base64.b64encode(wire(argument)).decode(), base64.b64encode(wire(value)).decode()]))
    counts[kind] += 1


def stateCase(arg):
    obj = Llama.__new__(Llama)
    obj.input_ids = np.array(arg["ids"], dtype=np.intc)
    obj.scores = np.array(arg["scores"], dtype=np.single)
    obj.n_tokens, obj._n_ctx, obj._logits_all = arg["cursor"], arg["context"], arg["all"]
    ids, scores = obj._input_ids, obj._scores
    tokens = capture(lambda: list(obj.eval_tokens))
    logits = capture(lambda: list(obj.eval_logits))
    if ids.size:
        ids[0] = 123
    if scores.size:
        scores[0, 0] = 12.5
    # Existing views observe parent writes; snapshots keep their original values.
    obj.input_ids[-1] = -456
    obj.scores[-1, -1] = -25.25
    return {"idsView": ids.tolist(), "scoresView": scores.tolist(), "tokens": tokens, "logits": logits,
            "ids": obj.input_ids.tolist(), "scores": obj.scores.tolist()}


rng = random.Random(33628)
for cursor in [-2147483648, -9, -4, -1, 0, 1, 3, 7, 2147483647]:
    for context in [-1, 0, 1, 3, 9]:
        for allLogits in [False, True]:
            arg = {"ids": [1, 2, 3, 4], "scores": [[i + j / 4 for j in range(3)] for i in range(5)],
                   "cursor": cursor, "context": context, "all": allLogits}
            record("views", arg, lambda: stateCase(arg))
for _ in range(60):
    arg = {"ids": [rng.randrange(-100, 100) for _ in range(rng.randrange(1, 15))],
           "scores": [[rng.randrange(-100, 100) / 4 for _ in range(4)] for _ in range(rng.randrange(1, 15))],
           "cursor": rng.randrange(-20, 20), "context": rng.randrange(10), "all": bool(rng.randrange(2))}
    record("views", arg, lambda: stateCase(arg))


def settingsCase(arg):
    obj = Llama.__new__(Llama)
    obj._seed, obj.cache, obj.n_tokens, obj._requires_eval = 42, None, 3, False
    cache = object()
    events = []
    for seed, useCache in zip(arg["seeds"], [True, False, True, True]):
        obj.set_seed(int(seed))
        obj.set_cache(cache if useCache else None)
        events.append({"seed": str(obj._seed), "sameCache": obj.cache is cache,
                       "nullCache": obj.cache is None, "cursor": obj.n_tokens, "requiresEval": obj._requires_eval})
    return events


for _ in range(12):
    arg = {"seeds": ["0", "-1", str(2**100), str(rng.randrange(-2**120, 2**120))]}
    record("settings", arg, lambda: settingsCase(arg))


def resetCase(arg):
    obj = Llama.__new__(Llama)
    obj.n_tokens, obj._requires_eval = arg["cursor"], False
    obj._is_recurrent, obj._is_hybrid = arg["recurrent"], arg["hybrid"]
    obj._ctx = types.SimpleNamespace(ctx=object())
    obj.input_ids = [1, 2]
    obj.scores = [[3.0]]
    obj._seed = 99
    obj.cache = cache = object()
    trace = []

    def getMemory(ctx):
        trace.append(["get", ctx is obj._ctx.ctx, obj.n_tokens, obj._requires_eval])
        if arg["memory"] == "getFail":
            raise RuntimeError("get memory failed")
        return None if arg["memory"] == "none" else memory

    def clearMemory(mem, data):
        trace.append(["clear", mem is memory, data, obj.n_tokens, obj._requires_eval])
        if arg["memory"] == "clearFail":
            raise RuntimeError("clear memory failed")

    memory = object()
    native.llama_get_memory, native.llama_memory_clear = getMemory, clearMemory
    results = []
    for _ in range(2):
        result = capture(obj.reset)
        results.append({"result": result, "cursor": obj.n_tokens, "requiresEval": obj._requires_eval})
    return {"results": results, "trace": trace, "ids": obj.input_ids, "scores": obj.scores,
            "seed": str(obj._seed), "sameCache": obj.cache is cache}


for recurrent in [False, True]:
    for hybrid in [False, True]:
        for memory in ["present", "none", "getFail", "clearFail"]:
            for cursor in [-1, 0, 5]:
                arg = {"recurrent": recurrent, "hybrid": hybrid, "memory": memory, "cursor": cursor}
                record("reset", arg, lambda: resetCase(arg))


def numericalCase(arg):
    array = np.array(arg["values"], dtype=np.single)
    return Llama.logits_to_logprobs(array, axis=arg["axis"]).tolist()


vectors = [[], [0], [1, 1], [10000, 9999, -10000], [np.inf, 1], [-np.inf, -np.inf],
           [-np.inf, 0, 1], [np.nan, 1], [np.inf, -np.inf], [0.0, -0.0]]
for size in [2, 7, 8, 9, 127, 128, 129, 255, 1024]:
    for scale in [0.001, 1, 100, 1e30]:
        vectors.append([rng.uniform(-scale, scale) for _ in range(size)])
for values in vectors:
    for axis in [-1, 0]:
        arg = {"values": values, "axis": axis}
        record("vector", arg, lambda: numericalCase(arg))
matrices = [[[1, 2], [3, 4]], [[np.inf, 1], [-np.inf, np.nan]], [[], []]]
for height, width in [(1, 1), (3, 7), (8, 9), (129, 3), (2, 129)]:
    for scale in [0.001, 1, 100]:
        matrices.append([[rng.uniform(-scale, scale) for _ in range(width)] for _ in range(height)])
for values in matrices:
    for axis in [-2, -1, 0, 1]:
        arg = {"values": values, "axis": axis}
        record("matrix", arg, lambda: numericalCase(arg))

fixture = root / "src/test/resources/python-0.3.36-llama-contracts.tsv"
fixture.parent.mkdir(parents=True, exist_ok=True)
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = {"pythonPackage": "0.3.36", "sourceCommit": audit["baseline"]["commit"],
            "fixtureRuntime": sys.version, "numpyVersion": np.__version__, "uniqueFunctions": 8,
            "functions": sorted("Llama." + name for name in names),
            "caseCount": len(rows), "casesByOperation": dict(counts),
            "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "numericalTolerance": {"absolute": 0.000004, "relative": 0.000004},
            "method": "Execute complete verified method bodies via AST. Native memory calls use explicit tracing doubles; no model inference. Compare view mutation, snapshots, cursor/cache/seed, reset ordering/errors and single-precision log-softmax. Runtime warnings suppressed in numerical fixtures; Java numeric comparison uses the recorded tolerance."}
(root / "docs/python-llama-contracts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
