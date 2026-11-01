"""Execute pinned llama_grammar.py bodies; outputs are pure strings -> result-only contract."""

import argparse
import ast
import base64
import contextlib
import gc
import hashlib
import io
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

src = (args.source_root / "llama_cpp/llama_grammar.py").read_text(encoding="utf-8")
tree = ast.parse(src)


class OpenStub:
    def __init__(self, case):
        self.case = case

    def __call__(self, file):
        if self.case.get("open_fail"):
            raise OSError("denied")
        return io.StringIO(self.case.get("file_content", ""))


def drive(case):
    ns = {"__name__": "pinned-grammar", "open": OpenStub(case),
          "requests": types.SimpleNamespace(get=lambda u: types.SimpleNamespace(json=lambda: case.get("fetched")))}
    exec(compile(tree, "pinned-grammar", "exec"), ns)
    LG = ns["LlamaGrammar"]
    SC = ns["SchemaConverter"]

    def capture(action):
        try:
            return action()
        except Exception as e:
            return dict(error=type(e).__name__, message=str(e))

    results = []
    for step in case["ops"]:
        name = step[0]
        if name == "ginit":
            results.append(capture(lambda: LG(_grammar=step[1])._grammar))
        elif name == "gstr":
            results.append(capture(lambda: LG.from_string(step[1], verbose=step[2])._grammar))
        elif name == "gfile":
            results.append(capture(lambda: LG.from_file(step[1], verbose=step[2])._grammar))
        elif name == "gschema":
            results.append(capture(lambda: LG.from_json_schema(step[1])._grammar))
        elif name == "rep":
            results.append(capture(lambda: ns["_build_repetition"](*step[1:])))
        elif name == "optrep":
            results.append(capture(lambda s=step: optrep(ns, *s[1:])))
        elif name == "notlit":
            conv = SC(prop_order={}, allow_fetch=False, dotall=False, raw_pattern=False)
            results.append(capture(lambda c=conv, s=step: c.not_literal(*s[1:])))
        elif name == "scinit":
            box["conv"] = SC(prop_order=step[1], allow_fetch=step[2], dotall=step[3], raw_pattern=step[4])
            box["conv"]._rules
            results.append("ok")
        elif name == "visit":
            results.append(capture(lambda: box["conv"].visit(step[1], step[2])))
        elif name == "rrefs":
            results.append(capture(lambda: show_val(box["conv"].resolve_refs(step[1], step[2]))))
        elif name == "gbnf":
            results.append(capture(lambda: ns["json_schema_to_gbnf"](step[1], *step[2:])))
        elif name == "fmt":
            results.append(capture(lambda: box["conv"].format_grammar()))
        elif name == "pat":
            results.append(capture(lambda: box["conv"]._visit_pattern(step[1], step[2])))
        elif name == "addrule":
            results.append(capture(lambda: box["conv"]._add_rule(step[1], step[2])))
        elif name == "builtin":
            br = ns["BuiltinRule"](*step[1:])
            results.append(dict(content=br.content, deps=br.deps))
        else:
            raise AssertionError("unknown op " + name)
    return dict(results=results, trace=[])


def optrep(ns, item, min_i, max_i, sep):
    return ns["_build_repetition"](item, min_i, max_i, sep)


def show_val(v):
    if isinstance(v, dict):
        return {k: show_val(x) for k, x in v.items()}
    if isinstance(v, list):
        return [show_val(x) for x in v]
    return v


box = {}
cases = []


def add(**case):
    cases.append(case)


completion_schema = json.dumps({"type": "object",
    "properties": {"name": {"type": "string"}, "age": {"type": "integer"}},
    "required": ["name"]})

add(ops=[["ginit", "root ::= x", ], ["gstr", "root ::= x", True], ["gstr", "g2", False]])
add(ops=[["gfile", "/g.gbnf", True]], file_content="root ::= x")
add(ops=[["gfile", "/no.gbnf", False]], open_fail=True)
add(ops=[["gfile", "/empty.gbnf", True]], file_content="")
add(ops=[["rep", '"a"', 0, 1, None], ["rep", '"a"', 1, None, None],
         ["rep", '"a"', 2, 4, None, True], ["rep", "x", 0, 2, None],
         ["rep", "x", 1, 3, None], ["rep", "x", 0, None, None],
         ["rep", "x", 0, 3, '"," space'], ["rep", "x", 2, 5, '"," space'],
         ["rep", "x", 2, None, '"," space']])
add(ops=[["notlit", "a", False], ["notlit", "abc", False], ["notlit", "a_b", True],
         ["notlit", "", False]])
add(ops=[["scinit", {}, False, False, False], ["builtin", "c1", ["d1", "d2"]],
         ["addrule", "name", "rule1"], ["addrule", "name", "rule1"],
         ["addrule", "name", "rule2"], ["addrule", "a b", "r"],
         ["fmt"]])
add(ops=[["gbnf", completion_schema]])
add(ops=[["gbnf", '{"type":"string"}'], ["gbnf", '{"type":"integer"}'],
         ["gbnf", '{"type":"number"}'], ["gbnf", '{"type":"boolean"}'],
         ["gbnf", '{"type":"null"}'], ["gbnf", '{"type":"object"}'],
         ["gbnf", '{}']])
add(ops=[["gbnf", '{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":3}'],
         ["gbnf", '{"type":"array","prefixItems":[{"type":"string"},{"type":"integer"}]}'],
         ["gbnf", '{"enum":["a","b",1,null]}'], ["gbnf", '{"const":"x"}'],
         ["gbnf", '{"type":["string","integer"]}'],
         ["gbnf", '{"oneOf":[{"type":"string"},{"type":"integer"}]}']])
add(ops=[["gbnf", '{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}},"required":["a","b"]}'],
         ["gbnf", '{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}}}'],
         ["gbnf", '{"type":"object","properties":{"a":{"type":"string"}},"additionalProperties":{"type":"integer"}}'],
         ["gbnf", '{"type":"object","properties":{"a":{"type":"string"}},"additionalProperties":true}']])
add(ops=[["gbnf", '{"type":"string","minLength":2,"maxLength":5}'],
         ["gbnf", '{"type":"string","format":"date"}'],
         ["gbnf", '{"type":"string","format":"date-time"}'],
         ["gbnf", '{"type":"string","format":"uuid4"}'],
         ["gbnf", '{"type":"string","pattern":"^[a-z]+$"}'],
         ["gbnf", '{"type":"string","pattern":"^a{2,4}b*$"}'],
         ["gbnf", '{"type":"string","pattern":"^(ab|cd)[0-9]{2}$"}']])
add(ops=[["pat", "^x{abc}$", "nm"], ["pat", "abc", "nm"], ["pat", "^a($", "nm"],
         ["pat", "^[a-z$", "nm"], ["pat", "^a{x$", "nm"], ["pat", "^{2}$", "nm"]])
add(ops=[["gbnf", '{"":"#/definitions/x","definitions":{"x":{"type":"string"}}}'],
         ["gbnf", '{"":"#/definitions/x","definitions":{"x":{"type":"string"},"y":{"type":"integer"}}}'],
         ["gbnf", '{"":"bad"}'], ["gbnf", '{"":"https://x/y"}']])
add(ops=[["gbnf", '{"type":"wibble"}'], ["gbnf", '{"type":"string","pattern":"abc"}'],
         ["gbnf", '{"type":"array"}'], ["gbnf", '{"allOf":[{"properties":{"a":{"type":"string"}}}]}']])
add(ops=[["scinit", {}, False, False, False],
         ["rrefs", {"a": {"": "#/b"}, "b": {"type": "integer"}}, "stdin"],
         ["rrefs", {"x": [{"": "#/y"}], "y": 1}, "stdin"]])

gc.disable()
rows = []
for index, case in enumerate(cases):
    try:
        gc.collect()
        rows.append("\t".join(["gram", base64.b64encode(wire(case)).decode(),
                                base64.b64encode(wire(drive(case))).decode()]))
    except Exception:
        print("case", index, file=sys.stderr)
        raise
fixture = root / "src/test/resources/python-0.3.36-grammar-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
functions = [
    "LlamaGrammar.__init__", "LlamaGrammar.from_string", "LlamaGrammar.from_file",
    "LlamaGrammar.from_json_schema", "_build_repetition", "_build_repetition.opt_repetitions",
    "BuiltinRule.__init__", "SchemaConverter.__init__", "SchemaConverter._format_literal",
    "SchemaConverter.not_literal", "SchemaConverter.not_literal.recurse",
    "SchemaConverter._add_rule", "SchemaConverter.resolve_refs",
    "SchemaConverter.resolve_refs.visit", "SchemaConverter._generate_union_rule",
    "SchemaConverter._visit_pattern", "SchemaConverter._visit_pattern.to_rule",
    "SchemaConverter._visit_pattern.transform", "SchemaConverter._visit_pattern.transform.get_dot",
    "SchemaConverter._visit_pattern.transform.join_seq", "SchemaConverter._resolve_ref",
    "SchemaConverter._generate_constant_rule", "SchemaConverter.visit",
    "SchemaConverter.visit.add_component", "SchemaConverter._add_primitive",
    "SchemaConverter._build_object_rule", "SchemaConverter._build_object_rule.get_recursive_refs",
    "SchemaConverter.format_grammar", "json_schema_to_gbnf",
]
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=len(functions),
                functions=functions,
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Pure input/output equivalence: pinned llama_grammar.py bodies executed verbatim; generated GBNF strings and exception types/messages compared byte for byte.")
(root / "docs/python-grammar-contracts.json").write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(dict(caseCount=len(rows), uniqueFunctions=len(functions)), indent=2))
