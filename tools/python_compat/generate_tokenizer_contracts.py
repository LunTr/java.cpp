"""Execute eight pinned tokenizer/facade method bodies using tracing model codecs."""
import argparse
import ast
import base64
import hashlib
import json
import pathlib
import random
import sys
import types
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

selection = {"llama.py": ("Llama", {"tokenize", "detokenize", "tokenizer"}),
             "llama_tokenizer.py": ("LlamaTokenizer", {"__init__", "tokenize", "detokenize", "encode", "decode"})}
namespace = {}
functions = []
for file, (className, names) in selection.items():
    tree = ast.parse((args.source_root / "llama_cpp" / file).read_text(encoding="utf-8"))
    node = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == className)
    node.bases = []
    node.body = [n for n in node.body if isinstance(n, ast.FunctionDef) and n.name in names]
    assert len(node.body) == len(names)
    functions += [className + "." + n.name for n in node.body]
    future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future, node], type_ignores=[])),
                 "pinned-python-0.3.36-tokenizer", "exec"), namespace)


def run(arg):
    trace = []
    tokenResult = arg["tokens"]
    byteResult = bytes.fromhex(arg["output"])

    def tokenize(text, add_bos, special):
        trace.append(["modelTokens", text.hex(), add_bos, special])
        return tokenResult

    def detokenize(tokens, special):
        trace.append(["modelBytes", tokens is tokenResult, special])
        return byteResult

    obj = namespace["Llama"].__new__(namespace["Llama"])
    obj._model = types.SimpleNamespace(tokenize=tokenize, detokenize=detokenize)
    wrapper = obj.tokenizer()
    obj.tokenizer_ = wrapper
    text, tokens, previous = bytes.fromhex(arg["bytes"]), tokenResult, arg["previous"]
    flags = arg["flags"]
    results = [obj.tokenize(text) is tokenResult, wrapper.tokenize(text) is tokenResult,
               obj.tokenize(text, *flags) is tokenResult, wrapper.encode(arg["text"], *flags) is tokenResult,
               wrapper.encode(arg["text"]) is tokenResult,
               obj.detokenize(tokens).hex(), obj.detokenize(tokens, previous, flags[1]).hex(),
               wrapper.detokenize(tokens, previous, flags[1]).hex(), wrapper.decode(tokens)]
    # tokenizer() constructs a fresh model wrapper even while tokenizer_ is customized.
    def customTokens(text, add_bos, special):
        trace.append(["customTokens", text.hex(), add_bos, special])
        return tokenResult
    def customBytes(tokens, prev_tokens=None, special=False):
        trace.append(["customBytes", tokens is tokenResult, prev_tokens is previous, special])
        return byteResult
    obj.tokenizer_ = types.SimpleNamespace(tokenize=customTokens, detokenize=customBytes)
    results += [obj.tokenize(text) is tokenResult, obj.detokenize(tokens, previous, flags[1]).hex(),
                obj.tokenizer() is not obj.tokenizer(), obj.tokenizer().tokenize(text) is tokenResult]
    # The earlier wrapper keeps its captured model after the facade's model reference changes.
    obj._model = None
    results.append(wrapper.tokenize(text) is tokenResult)
    return {"results": results, "trace": trace}


rng = random.Random(33629)
texts = ["", "hello\0中文🙂", "\ud800x", "a\udfff", "\ud800\ud800", "é\r\n", "\uffff"]
outputs = [b"", b"hello\0", "中文🙂".encode(), b"\xffA\xc0\xafB", b"\xed\xa0\x80Z",
           b"\xf0\x9f", b"\xe2(\xa1", b"\xf4\x90\x80\x80"]
arguments = []
for text in texts:
    for output in outputs:
        for flags in [(True, True), (False, False), (True, False), (False, True)]:
            arguments.append({"text": text, "output": output.hex(), "bytes": rng.randbytes(9).hex(),
                              "tokens": [0, 42, -1], "previous": [9, 8], "flags": list(flags)})
for _ in range(100):
    arguments.append({"text": "".join(chr(rng.randrange(0x110000)) for _ in range(15)),
                      "output": rng.randbytes(rng.randrange(25)).hex(), "bytes": rng.randbytes(12).hex(),
                      "tokens": [rng.randrange(-100, 100) for _ in range(rng.randrange(6))],
                      "previous": rng.choice([None, [], [4]]), "flags": [bool(rng.randrange(2)), bool(rng.randrange(2))]})
rows = ["\t".join(base64.b64encode(wire(value)).decode() for value in [arg, run(arg)]) for arg in arguments]
fixture = root / "src/test/resources/python-0.3.36-tokenizer-contracts.tsv"
fixture.parent.mkdir(parents=True, exist_ok=True)
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = {"pythonPackage": "0.3.36", "sourceCommit": audit["baseline"]["commit"],
            "fixtureRuntime": sys.version, "uniqueFunctions": 8, "functions": functions,
            "caseCount": len(rows), "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "method": "Execute complete verified bodies with model/custom-tokenizer tracing doubles; compare UTF-8 ignore, flags, previous-token forwarding, references, fresh wrappers and captured model. Native segmentation remains version-specific."}
(root / "docs/python-tokenizer-contracts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
