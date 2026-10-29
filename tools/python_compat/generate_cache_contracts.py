"""Execute the pinned disk cache bodies over a traced deterministic storage port."""

import argparse
import ast
import base64
import collections
import contextlib
import hashlib
import json
import pathlib
import random
import sys
import types
from abc import ABC, abstractmethod
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

namespace = dict(ABC=ABC, abstractmethod=abstractmethod, sys=sys)
future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
def execute(nodes):
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
                 "pinned-cache", "exec"), namespace)

tree = ast.parse((args.source_root / "llama_cpp/llama.py").read_text(encoding="utf-8"))
llama = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Llama")
prefix = next(node for node in llama.body if isinstance(node, ast.FunctionDef) and node.name == "longest_token_prefix")
prefix.decorator_list = []
execute([prefix])
namespace["llama_cpp"] = types.SimpleNamespace(llama=types.SimpleNamespace(
    Llama=types.SimpleNamespace(longest_token_prefix=namespace["longest_token_prefix"])))
tree = ast.parse((args.source_root / "llama_cpp/llama_cache.py").read_text(encoding="utf-8"))
execute([node for node in tree.body if isinstance(node, ast.ClassDef) and node.name in ["BaseLlamaCache", "LlamaDiskCache"]])

def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))

def run(argument):
    trace, writes = [], []
    class Store:
        def __init__(self, directory):
            self.data = collections.OrderedDict()
            self.failure = None
            trace.append(["open", directory])
        def call(self, name, *values):
            trace.append([name, *values])
            if self.failure == name:
                raise ValueError("store:" + name)
        def volume(self):
            self.call("volume")
            return 10 + sum(self.data.values())
        def iterkeys(self):
            self.call("iterKeys")
            return iter(self.data)
        def pop(self, key):
            self.call("pop", list(key))
            return str(self.data.pop(key))
        def __contains__(self, key):
            self.call("contains", list(key))
            return key in self.data
        def __delitem__(self, key):
            self.call("delete", list(key))
            del self.data[key]
        def __setitem__(self, key, value):
            self.call("put", list(key), value)
            self.data[key] = int(value)
        def __len__(self):
            self.call("size")
            return len(self.data)
        def __iter__(self):
            self.call("firstKey")
            return iter(self.data)
    class Stderr:
        def write(self, value):
            writes.append(value)
        def flush(self):
            pass
    namespace["diskcache"] = types.SimpleNamespace(Cache=Store)
    cache = namespace["LlamaDiskCache"](argument["directory"], int(argument["capacity"]))
    events = []
    for step in argument["steps"]:
        def operation():
            kind = step[0]
            if kind == "put": cache[step[1]] = step[2]
            elif kind == "get": return cache[step[1]]
            elif kind == "contains": return step[1] in cache
            elif kind == "find":
                found = cache._find_longest_prefix_key(tuple(step[1]))
                return None if found is None else list(found)
            elif kind == "volume": return str(cache.cache_size)
            elif kind == "capacity": cache.capacity_bytes = int(step[1])
            elif kind == "failure": cache.cache.failure = step[1]
        with contextlib.redirect_stderr(Stderr()):
            result = capture(operation)
        events.append(dict(result=result, order=[list(key) for key in cache.cache.data],
                           trace=list(trace), stderr=list(writes)))
        trace.clear()
        writes.clear()
    return events

cases = []
def add(steps, capacity="100"):
    cases.append(dict(directory="cache/测试", capacity=capacity, steps=steps))

add([["put", ["1", "2"], "4"], ["put", ["1", "3"], "5"], ["find", ["1"]],
     ["contains", ["1"]], ["get", ["1"]], ["get", ["1"]], ["get", ["1"]],
     ["put", [], "1"], ["get", []], ["volume"]])
add([["put", ["1"], "4"], ["put", ["1"], "5"], ["capacity", "9"], ["put", ["2"], "0"], ["volume"]])
for failure in ["iterKeys", "pop", "contains", "delete", "put", "volume", "size", "firstKey"]:
    add([["put", ["1"], "3"], ["failure", failure], ["put", ["1"], "4"],
         ["put", ["2"], "5"], ["get", ["1"]], ["contains", ["1"]],
         ["volume"], ["failure", None], ["put", ["3"], "6"]], "13")
rng = random.Random(33618)
keys = [[], ["1"], ["1", "2"], ["1", "3"], ["2"], [None], [str(2**100)]]
for _ in range(100):
    steps = []
    for _ in range(40):
        kind = rng.choice(["put", "put", "get", "contains", "find", "volume", "capacity"])
        if kind == "put": steps.append([kind, rng.choice(keys), str(rng.randrange(8))])
        elif kind == "volume": steps.append([kind])
        elif kind == "capacity": steps.append([kind, str(rng.randrange(-2, 30))])
        else: steps.append([kind, rng.choice(keys)])
    add(steps, str(rng.randrange(-2, 30)))
rows = ["\t".join(["disk", base64.b64encode(wire(case)).decode(),
                       base64.b64encode(wire(run(case))).decode()]) for case in cases]
for capacity in [None, "0", "-1", str(2**100)]:
    probe = types.SimpleNamespace()
    base = namespace["BaseLlamaCache"]
    if capacity is None: base.__init__(probe)
    else: base.__init__(probe, int(capacity))
    result = dict(capacity=str(probe.capacity_bytes), prefix=base._find_longest_prefix_key(probe, None))
    rows.append("\t".join(["base", base64.b64encode(wire(capacity)).decode(),
                           base64.b64encode(wire(result)).decode()]))
fixture = root / "src/test/resources/python-0.3.36-cache-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows),
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Complete hash-verified LlamaDiskCache bodies with a traced deterministic storage port; volume=10+stored integer sizes. No SQLite/pickle parity certification.")
(root / "docs/python-cache-contracts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
