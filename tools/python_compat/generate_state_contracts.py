"""Run pinned Python cache/state/processor functions and write ignored golden data."""

from contract_wire import wire

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
import typing

import numpy as np

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
assert sys.version_info[:2] == (3, 13)
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for item in audit["baseline"]["files"]:
    data = (args.source_root / item["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"], item["path"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == item["sha"]

namespace = {"np": np, "List": typing.List, "LogitsProcessor": typing.Callable,
             "StoppingCriteria": typing.Callable, "ABC": __import__("abc").ABC,
             "abstractmethod": __import__("abc").abstractmethod,
             "OrderedDict": collections.OrderedDict}


def execute(nodes):
    future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
                 "pinned-python-0.3.36-state", "exec"), namespace)


tree = ast.parse((args.source_root / "llama_cpp/llama.py").read_text(encoding="utf-8"))
llamaClass = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Llama")
prefix = next(node for node in llamaClass.body if isinstance(node, ast.FunctionDef) and node.name == "longest_token_prefix")
prefix.decorator_list = []
execute([prefix])
namespace["llama_cpp"] = types.SimpleNamespace(llama=types.SimpleNamespace(
    Llama=types.SimpleNamespace(longest_token_prefix=namespace["longest_token_prefix"])))
execute([node for node in tree.body if isinstance(node, ast.ClassDef) and node.name in [
    "LlamaState", "LogitsProcessorList", "StoppingCriteriaList", "MinTokensLogitsProcessor"]])
cacheTree = ast.parse((args.source_root / "llama_cpp/llama_cache.py").read_text(encoding="utf-8"))
execute([node for node in cacheTree.body if isinstance(node, ast.ClassDef) and node.name in ["BaseLlamaCache", "LlamaRAMCache"]])

rows = []
counts = collections.Counter()


def failure(error):
    return {"error": type(error).__name__, "message": str(error)}


def capture(operation):
    try:
        return operation()
    except Exception as error:
        return failure(error)


def record(kind, argument, operation):
    value = capture(operation)
    rows.append("\t".join([kind, base64.b64encode(wire(argument)).decode(), base64.b64encode(wire(value)).decode()]))
    counts[kind] += 1


def key(value):
    return tuple(None if token is None else int(token) for token in value)


def keyStrings(value):
    return None if value is None else [None if token is None else str(token) for token in value]


def cacheCase(argument):
    cache = namespace["LlamaRAMCache"](int(argument["capacity"]))
    states = [None if size == "nullState" else namespace["LlamaState"](
        np.array([], dtype=np.intc), np.array([], dtype=np.single), 0, b"", None if size is None else int(size), 0)
        for size in argument["sizes"]]
    events = []
    for step in argument["steps"]:
        def operation():
            kind = step[0]
            if kind == "put":
                cache[key(step[1])] = states[step[2]]
            elif kind == "get":
                result = cache[key(step[1])]
                return None if result is None else next(index for index, state in enumerate(states) if result is state)
            elif kind == "contains":
                return key(step[1]) in cache
            elif kind == "find":
                return keyStrings(cache._find_longest_prefix_key(key(step[1])))
            elif kind == "capacity":
                cache.capacity_bytes = int(step[1])
            elif kind == "size":
                states[step[1]].llama_state_size = None if step[2] is None else int(step[2])
            return None
        result = capture(operation)
        size = capture(lambda: str(cache.cache_size))
        events.append({"result": result, "order": [keyStrings(k) for k in cache.cache_state], "size": size})
    return events


rng = random.Random(33612)
for _ in range(240):
    first = [str(rng.randrange(-3, 5)) for _ in range(rng.randrange(12))]
    second = first[:rng.randrange(len(first) + 1)] + [str(rng.randrange(-3, 5)) for _ in range(rng.randrange(8))]
    record("prefix", [first, second], lambda: namespace["longest_token_prefix"](key(first), key(second)))
for first, second in [([], []), (["1"], ["1", "2"]), ([str(2**100)], [str(2**100)]), ([None], [None])]:
    record("prefix", [first, second], lambda: namespace["longest_token_prefix"](key(first), key(second)))

cacheCases = [
    {"capacity": "20", "sizes": ["4", "4", "4"], "steps": [
        ["put", ["1", "2"], 0], ["put", ["1", "3"], 1], ["find", ["1", "9"]],
        ["contains", ["1", "2"]], ["get", ["1", "9"]], ["find", ["1", "9"]],
        ["capacity", "8"], ["put", ["8"], 2], ["get", []], ["get", ["99"]]]},
    {"capacity": "0", "sizes": ["0", "1"], "steps": [
        ["put", [], 0], ["contains", []], ["get", []], ["put", ["1"], 0], ["get", ["1"]], ["put", ["2"], 1]]},
    {"capacity": str(2**100), "sizes": [str(2**99), str(2**99)], "steps": [
        ["put", [str(2**100)], 0], ["put", ["2"], 1], ["size", 0, str(2**100)], ["put", ["2"], 1]]},
]
for size in [None, "nullState"]:
    cacheCases.append({"capacity": "5", "sizes": ["1", size], "steps": [
        ["put", ["1"], 0], ["put", ["2"], 1], ["find", ["2"]], ["get", ["2"]], ["put", ["2"], 0]]})
for _ in range(128):
    sizes = [str(rng.randrange(-3, 10)) for _ in range(5)]
    keys = [[], ["1"], ["1", "2"], ["1", "3"], ["2"], [None], [str(2**80)]]
    steps = []
    for _ in range(60):
        kind = rng.choice(["put", "put", "get", "contains", "find", "capacity", "size"])
        if kind == "put": steps.append([kind, rng.choice(keys), rng.randrange(5)])
        elif kind == "capacity": steps.append([kind, str(rng.randrange(-2, 20))])
        elif kind == "size": steps.append([kind, rng.randrange(5), str(rng.randrange(-3, 10))])
        else: steps.append([kind, rng.choice(keys)])
    cacheCases.append({"capacity": str(rng.randrange(-2, 20)), "sizes": sizes, "steps": steps})
for argument in cacheCases:
    record("cache", argument, lambda: cacheCase(argument))


def minTokensCase(argument):
    processor = namespace["MinTokensLogitsProcessor"](int(argument["minimum"]), argument["eos"])
    events = []
    for step in argument["steps"]:
        if "prompt" in step: processor.prompt_tokens = step["prompt"]
        ids = None if step["ids"] is None else np.array(step["ids"], dtype=np.intc)
        scores = None if step["scores"] is None else np.array(step["scores"], dtype=np.single)
        def apply():
            result = processor(ids, scores)
            return {"scores": None if result is None else result.tolist(), "sameScores": result is scores}
        events.append({"result": capture(apply), "promptTokens": processor.prompt_tokens})
    return events


for minimum in [-10, -1, 0, 1, 2, 7, 2**80]:
    for eos in [-2147483648, -7, -1, 0, 2, 5, 2147483647]:
        for scoreCount in [0, 1, 3, 7]:
            argument = {"minimum": str(minimum), "eos": eos, "steps": [
                {"ids": list(range(length)), "scores": [index / 2 for index in range(scoreCount)]}
                for length in [3, 3, 4, 2, 12]]}
            argument["steps"] += [{"ids": None, "scores": None}, {"ids": [1], "scores": None},
                                   {"ids": [], "scores": [0.0], "prompt": None}]
            record("minTokens", argument, lambda: minTokensCase(argument))


def callbacksCase(argument, stopping):
    processors = namespace["StoppingCriteriaList" if stopping else "LogitsProcessorList"]()
    ids = np.array([1, 2], dtype=np.intc)
    initial = np.array([0.5, -2.0, 1.25], dtype=np.single)
    trace = []
    specs = argument
    def build(spec, index):
        if spec["kind"] == "null": return None
        def callback(inputIds, scores):
            trace.append({"index": index, "idsSame": inputIds is ids, "scoresSame": scores is initial})
            kind = spec["kind"]
            if kind == "raise": raise ValueError("callback failure")
            if kind == "append": processors.append(build({"kind": "identity", "value": True}, 99))
            if kind == "remove": processors.remove(callback)
            if kind == "clearResult": specs[spec["target"]]["value"].clear()
            if stopping: return spec.get("value", False)
            if kind == "replace": return np.array([3.0, 4.0], dtype=np.single)
            if kind == "add": scores += np.single(0.5)
            if kind == "scale": scores *= np.single(2.0)
            if kind == "none": return None
            return scores
        return callback
    for index, spec in enumerate(specs): processors.append(build(spec, index))
    def apply():
        result = processors(ids, initial)
        return result if stopping else {"scores": None if result is None else result.tolist(), "sameScores": result is initial}
    return {"result": capture(apply), "trace": trace, "count": len(processors)}


for kinds in [[], ["add"], ["scale", "replace", "add"], ["replace", "scale"], ["append", "add"],
              ["remove", "replace", "add"], ["none", "identity"], ["add", "raise", "replace"], ["null"]]:
    argument = [{"kind": kind} for kind in kinds]
    record("processors", argument, lambda: callbacksCase(argument, False))
for _ in range(96):
    argument = [{"kind": rng.choice(["identity", "add", "scale", "replace", "append", "remove", "raise", "null"])}
                for _ in range(rng.randrange(10))]
    record("processors", argument, lambda: callbacksCase(argument, False))
for values in [[], [True, False], [False, True], [True, True], [None, "", [], {}, 0], [0, [1]], ["yes", None]]:
    argument = [{"kind": "identity", "value": value} for value in values]
    record("stopping", argument, lambda: callbacksCase(argument, True))
for argument in [[{"kind": "identity", "value": True}, {"kind": "raise"}],
                 [{"kind": "append", "value": True}], [{"kind": "remove", "value": False}, {"kind": "identity", "value": True}],
                 [{"kind": "identity", "value": [1]}, {"kind": "clearResult", "target": 0, "value": False}],
                 [{"kind": "null"}]]:
    # The upstream case mutates argument values; persist the pre-call input.
    value = json.loads(json.dumps(argument))
    record("stopping", value, lambda: callbacksCase(argument, True))


def stateCase(argument):
    ids = None if argument["ids"] is None else np.array(argument["ids"], dtype=np.intc)
    scores = None if argument["scores"] is None else np.array(argument["scores"], dtype=np.single)
    data = None if argument["data"] is None else bytes.fromhex(argument["data"])
    state = namespace["LlamaState"](ids, scores, argument["nTokens"], data, int(argument["size"]), argument["seed"])
    return {"ids": None if state.input_ids is None else state.input_ids.tolist(),
            "scores": None if state.scores is None else state.scores.tolist(), "nTokens": state.n_tokens,
            "data": None if state.llama_state is None else state.llama_state.hex(),
            "size": str(state.llama_state_size), "seed": state.seed,
            "idsSame": state.input_ids is ids, "scoresSame": state.scores is scores, "dataSame": state.llama_state is data}


for index in range(48):
    argument = {"ids": None if index % 7 == 0 else [rng.randrange(-100, 100) for _ in range(4)],
                "scores": None if index % 5 == 0 else [[rng.randrange(-20, 20) / 4 for _ in range(3)] for _ in range(2)],
                "nTokens": rng.randrange(-10, 10), "data": None if index % 3 == 0 else rng.randbytes(12).hex(),
                "size": str(rng.randrange(-2**80, 2**80)), "seed": rng.randrange(-2**63, 2**63)}
    record("state", argument, lambda: stateCase(argument))

fixture = root / "src/test/resources/python-0.3.36-state-contracts.tsv"
fixture.parent.mkdir(parents=True, exist_ok=True)
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = {"pythonPackage": "0.3.36", "sourceCommit": audit["baseline"]["commit"],
            "fixtureRuntime": sys.version, "numpyVersion": np.__version__, "uniqueFunctions": 12,
            "caseCount": len(rows), "casesByOperation": dict(counts),
            "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "method": "Execute complete hash-verified original state, RAM cache, prefix and processor bodies; NumPy uses intc/single arrays. Capture per-step errors, order, mutable state, references and callback traces."}
(root / "docs/python-state-contracts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
