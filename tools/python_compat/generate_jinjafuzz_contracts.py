"""Differential fuzzing over the PythonJinjaTemplate-supported Jinja subset.

Generates seeded-random templates inside the grammar the Java adapter ports.
Expressions are type-aware: operators, indexes, str methods and filters are
only applied where CPython would succeed, so most cases compare rendered
output exactly. A small fraction deliberately injects error constructs the
adapter emulates (undefined names/attributes, raise_exception, str-method
arity/type errors, invalid slice bounds).

The environment matches the pinned Jinja2ChatFormatter: ImmutableSandboxed
with trim/lstrip blocks, loopcontrols extension, tojson filter and the
raise_exception/namespace/strftime_now globals. Successful renders compare
exact text; failures only require the Java side to fail as well (jinjava
does not report CPython exception type names).

Usage: conda run -n base python tools/python_compat/generate_jinjafuzz_contracts.py \
    --source-root .tools/python-api-audit
"""

from contract_wire import wire

import argparse
import base64
import collections
import datetime
import hashlib
import json
import pathlib
import random
import re
import sys

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
assert sys.version_info[:2] == (3, 13), "Use the pinned CPython 3.13 contract runtime"
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for item in audit["baseline"]["files"]:
    data = (args.source_root / item["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"], item["path"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == item["sha"]

import jinja2
import jinja2.ext
from jinja2.sandbox import ImmutableSandboxedEnvironment

rng = random.Random(20261124)

# Expression types: int, float, str, bool, list, tuple, dict, none, any
STRINGS = ["", "a", "Hello World", "  pad  ", "a,b,c", "x\ny", "中🙂", "A-b_C"]
ASCII_STRINGS = ["", "ab", "Hello World", "  pad  ", "a,b,c", "x\ny", "A-b_C"]
INTS = [0, 1, 2, -3, 7, 42]
NZINTS = [1, 2, -3, 7, 42]
FLOATS = [0.0, 1.5, -2.25, 1e3]
NZFLOATS = [1.5, -2.25, 1e3]

# names the render binds; entries tagged by type so expressions stay well-typed
INT_NAMES = ["n", "i"]
FLOAT_NAMES = ["f"]
STR_NAMES = ["s"]
BOOL_NAMES = ["b"]
ANY_NAMES = ["v", "w", "tools"]
LIST_NAMES = ["seq", "slist", "ilist", "messages"]
ILIST_NAMES = ["ilist"]
SLIST_NAMES = ["slist"]
DICT_NAMES = ["data", "d"]


def atom(t=None):
    """(text, type) leaf expression, optionally of a requested type."""
    pool = {
        "int": lambda: rng.choice([repr(rng.choice(INTS))] + INT_NAMES),
        "float": lambda: rng.choice([repr(rng.choice(FLOATS))] + FLOAT_NAMES),
        "str": lambda: rng.choice([repr(rng.choice(STRINGS))] + STR_NAMES),
        "bool": lambda: rng.choice(["true", "false"] + BOOL_NAMES),
        "list": lambda: rng.choice(
            ["[1, 'a', none]", "[]", "[1, 2]", "['x', 'y']"] + LIST_NAMES),
        "ilist": lambda: rng.choice(["[1, 2]", "[]"] + ILIST_NAMES),
        "slist": lambda: rng.choice(["['x', 'y']", "[]"] + SLIST_NAMES),
        "tuple": lambda: "(1, 2)",
        "dict": lambda: rng.choice(["{'k': 1, 'z': 'q'}", "{}"] + DICT_NAMES),
        "none": lambda: "none",
        "any": lambda: rng.choice(ANY_NAMES),
    }
    if t is not None and t in pool:
        return pool[t](), t
    key = rng.choice(list(pool))
    return pool[key](), key


def intexpr(depth):
    if depth <= 0:
        return atom("int")[0]
    pick = rng.random()
    if pick < 0.45:
        return "(" + intexpr(0) + rng.choice(["+", "-", "*"]) + intexpr(0) + ")"
    if pick < 0.6:
        return "(" + intexpr(0) + rng.choice(["//", "%"]) + str(rng.choice(NZINTS)) + ")"
    if pick < 0.75:
        return "(" + rng.choice(INT_NAMES) + rng.choice(["+", "-"]) + intexpr(0) + ")"
    return intexpr(0)


def strexpr(depth):
    if depth <= 0:
        return atom("str")[0]
    pick = rng.random()
    if pick < 0.3:
        return "(" + strexpr(0) + "+" + strexpr(0) + ")"
    if pick < 0.45:
        return strexpr(0) + "[" + rng.choice(["", "1", "-2", "none"]) + ":" \
            + rng.choice(["", "2", "-1", "none"]) + "]"
    if pick < 0.7:
        return strexpr(0) + rng.choice(
            [".strip()", ".upper()", ".lower()", ".title()", ".capitalize()",
             ".replace('a','b')", ".zfill(6)", ".center(8,'*')", ".ljust(5)",
             ".rjust(5,'-')", ".swapcase()", ".expandtabs(3)",
             ".removeprefix('a')", ".removesuffix('c')"])
    if pick < 0.8:
        return "(" + strexpr(0) + "|string)"
    return strexpr(0)


def listexpr(depth):
    if depth <= 0:
        return atom("list")[0]
    pick = rng.random()
    if pick < 0.3:
        return "(" + listexpr(0) + "+" + listexpr(0) + ")"
    if pick < 0.55:
        return listexpr(0) + "[" + rng.choice(["", "1", "-2", "none"]) + ":" \
            + rng.choice(["", "2", "-1", "none"]) + "]"
    if pick < 0.7:
        return "(" + listexpr(0) + "|list)"
    if pick < 0.85:
        return "([" + atom()[0] + "] + " + listexpr(0) + ")"
    return listexpr(0)


def dictexpr():
    return rng.choice(["{'k': 1, 'z': 'q'}", "{}"] + DICT_NAMES)


def expr(depth):
    """(text, type) — only constructs valid under CPython semantics."""
    if depth <= 0:
        return atom()
    kind = rng.randrange(16)
    e = lambda: expr(depth - 1)
    if kind == 0:
        op = rng.choice(["==", "!="])
        return "(" + e()[0] + op + e()[0] + ")", "bool"
    if kind == 1:
        left, lt = e()
        right, rt = e()
        if lt in ("int", "float") and rt in ("int", "float") or lt == rt == "str":
            return "(" + left + rng.choice(["<", "<=", ">", ">="]) + right + ")", "bool"
        return "(" + left + rng.choice(["==", "!="]) + right + ")", "bool"
    if kind == 2:
        left, lt = e()
        right, rt = e()
        return "(" + left + rng.choice([" and ", " or "]) + right + ")", \
            lt if lt == rt else "any"
    if kind == 3:
        inner, _ = e()
        return "(not " + inner + ")", "bool"
    if kind == 4:
        a, at = e()
        b, bt = e()
        return "(" + a + " if " + e()[0] + " else " + b + ")", \
            at if at == bt else "any"
    if kind == 5:
        # index only literals that are always non-empty
        seq = rng.choice(["'ab'", "'xyz'", "[1, 'a', none]", "[7, 8]", "(1, 2)"])
        return seq + "[" + str(rng.randrange(-2, 2)) + "]", "any"
    if kind == 6:
        base, bt = e()
        if bt not in ("str", "list", "tuple", "slist", "ilist"):
            base, bt = strexpr(0), "str"
        return base + "[" + rng.choice(["", "1", "-2", "none", "n"]) + ":" \
            + rng.choice(["", "2", "-1", "none", "n"]) + "]", bt
    if kind == 7:
        base, bt = e()
        if bt not in ("int", "float"):
            base, bt = "n", "int"
        return "(" + base + rng.choice(["+", "-", "*"]) + intexpr(1) + ")", bt
    if kind == 8:
        return "(" + rng.choice([intexpr(0), atom("float")[0], atom("int")[0]]) \
            + rng.choice(["/", "//", "%"]) + str(rng.choice(NZINTS + NZFLOATS)) \
            + ")", "float"
    if kind == 9:
        needle, nt = e()
        if nt == "str":
            return "(" + needle + " in " + strexpr(0) + ")", "bool"
        if rng.random() < 0.5:
            return "(" + repr(rng.choice(STRINGS)) + " in " + dictexpr() + ")", "bool"
        return "(" + needle + " in " + listexpr(0) + ")", "bool"
    if kind == 10:
        inner, it = e()
        if it == "str":
            return "(" + inner + rng.choice(["|upper", "|lower", "|trim", "|title",
                                             "|capitalize", "|string"]) + ")", "str"
        if it in ("slist", "ilist"):
            f, rt = rng.choice([
                ("|join(',')", "str"), ("|length", "int"), ("|first", "any"),
                ("|last", "any"), ("|list", "list"), ("|sort", "list"),
                ("|unique", "any"), ("|batch(2)", "any"), ("|sum", "int"),
            ])
            return "(" + inner + f + ")", rt
        if it in ("list", "tuple"):
            f, rt = rng.choice([
                ("|length", "int"), ("|first", "any"), ("|last", "any"),
                ("|list", "list"), ("|unique", "any"), ("|batch(2)", "any"),
                ("|select", "any"), ("|reject('none')", "any"),
            ])
            return "(" + inner + f + ")", rt
        if it == "dict":
            f, rt = rng.choice([("|length", "int"), ("|first", "any"),
                                ("|list", "list"), ("|join(',')", "str")])
            return "(" + inner + f + ")", rt
        return "(" + inner + "|" + rng.choice(
            ["default('d')", "tojson"]) + ")", "any"
    if kind == 11:
        return strexpr(0) + rng.choice(
            [".startswith('a')", ".endswith('z')", ".find('l')", ".count('a')",
             ".isdigit()", ".isalpha()", ".isspace()", ".isnumeric()",
             ".split(',')", ".rsplit(',')", ".splitlines()", ".join(['x','y'])",
             ".partition(',')", ".rfind('x')"]), \
            "str" if rng.random() < 0.35 else "any"
    if kind == 12:
        attr = rng.choice(["content", "role", "x", "k", "y"])
        return "(" + dictexpr() + "." + attr + ")", "any"
    if kind == 13:
        return rng.choice([
            "range(" + str(rng.randrange(-2, 5)) + ")",
            "range(" + str(rng.randrange(4)) + ", " + str(rng.randrange(-2, 6)) + ")",
            "range(n)",
            "range(" + str(rng.randrange(5)) + ")[" + rng.choice(["1", "-2", "none"])
                + ":" + rng.choice(["-1", "2", "none"]) + "]",
            "range(" + str(rng.randrange(5)) + ")[" + rng.choice(["", "1", "-1"])
                + ":" + rng.choice(["", "-1", "2"]) + ":" + rng.choice(["-1", "2"]) + "]",
            "strftime_now('%Y')",
            "strftime_now('%m-%d')",
            "namespace(c=" + str(rng.randrange(3)) + ")",
            "len(" + strexpr(0) + ")",
        ]), "any"
    if kind == 14:
        target = rng.choice([atom("any")[0], "(" + expr(0)[0] + ")"])
        return target + rng.choice(
            [" is none", " is not none", " is true", " is not true",
             " is false", " is not false", " is defined", " is not defined"]), "bool"
    return "(" + strexpr(0) + " ~ " + strexpr(0) + ")", "str"


def statement(depth, names):
    kind = rng.random()
    if kind < 0.4 or depth <= 0:
        return "{{ " + expr(2)[0] + " }}"
    if kind < 0.55:
        var = rng.choice(["x", "y", "item", "m"])
        iterable = rng.choice([
            "seq", "slist", "ilist", "messages", "range(" + str(rng.randrange(5)) + ")",
            "[1, 'a', none]", listexpr(0), dictexpr(),
        ])
        body = block(depth - 1, names + [var])
        tail = "{% else %}" + block(0, names) if rng.random() < 0.15 else ""
        return ("{% for " + var + " in " + iterable + " %}" + body + tail
                + "{% endfor %}")
    if kind < 0.7:
        body = block(depth - 1, names)
        out = "{% if " + expr(1)[0] + " %}" + body
        if rng.random() < 0.5:
            out += "{% elif " + expr(1)[0] + " %}" + block(0, names)
        if rng.random() < 0.5:
            out += "{% else %}" + block(0, names)
        return out + "{% endif %}"
    if kind < 0.8:
        var = rng.choice(["t", "acc", "tmp"])
        names.append(var)
        return "{% set " + var + " = " + expr(1)[0] + " %}"
    if kind < 0.85:
        return ("{% set ns = namespace(count=0) %}{% set ns.count = ns.count + "
                + intexpr(1) + " %}{{ ns.count }}")
    if kind < 0.9:
        args = expr(1)[0] + (", " + expr(0)[0] if rng.random() < 0.5 else "")
        return ("{% macro m(a, b='d') %}<{{ a }}|{{ b }}>{% endmacro %}{{ m("
                + args + ") }}")
    if kind < 0.95:
        return rng.choice([
            "{{- " + expr(1)[0] + " -}}",
            "{%- if " + expr(1)[0] + " %}x{%- endif %}",
            "{%+ if " + expr(1)[0] + " %}x{%+ endif %}",
            "{%- for q in range(3) %}{{q}}{%- endfor %}",
            "t{{- " + expr(1)[0] + " }}s",
            "{% for z in seq %}{{ z }}{% break %}{% endfor %}",
            "{% for z in seq %}{% continue %}{{ z }}{% endfor %}",
        ])
    return rng.choice([
        "{# comment " + str(rng.randrange(9)) + " #}",
        "{% raw %}{{x}} {% endraw %}",
        "{% raw %}a {{ b }}{% c %}{% endraw %}",
        "{% if missing is not defined %}u{% endif %}",
        "{% if missing is defined %}u{% else %}v{% endif %}",
        "loop:" + "{{ loop.index }}" if rng.random() < 0.05 else "{{ 1 }}",
    ])


ERROR_FRAGMENTS = [
    "{{ raise_exception('bad') }}",
    "{{ missing.attr }}",
    "{{ missing.k }}",
    "{{ missing[0] }}",
    "{{ missing|attr('x') }}",
    "{{ missing() }}",
    "{{ s.startswith(1) }}",
    "{{ s.count() }}",
    "{{ s.index('zzz') }}",
    "{{ s.find(1) }}",
    "{{ s.upper(1) }}",
    "{{ 'ab'[1:'x'] }}",
    "{{ 'ab'[1:2:'x'] }}",
    "{{ 'ab'[1:2:0] }}",
    "{{ seq[0]['k']['x'] }}",
]


def block(depth, names):
    return "".join(statement(depth, names) for _ in range(rng.randrange(1, 3)))


def bindings():
    msgs = [{"role": rng.choice(["system", "user", "assistant", "tool"]),
             "content": rng.choice(STRINGS)} for _ in range(rng.randrange(4))]
    return {
        "v": rng.choice(INTS + FLOATS + STRINGS + [[1, 2], None, True]),
        "w": rng.choice(INTS + FLOATS + STRINGS + [{"k": 2}]),
        "s": rng.choice(STRINGS),
        "n": rng.randrange(-2, 6),
        "i": rng.randrange(-3, 9),
        "f": rng.choice(FLOATS),
        "b": rng.random() < 0.5,
        "seq": rng.choice([list(range(rng.randrange(6))), STRINGS[:3], ["a", "b"], []]),
        "slist": [rng.choice(["a", "b", "c"]) for _ in range(rng.randrange(4))],
        "ilist": [rng.randrange(-5, 10) for _ in range(rng.randrange(4))],
        "data": rng.choice([{"x": 1, "y": [1, "z"]}, {"content": "v", "role": "r"}, {}]),
        "d": rng.choice([{"k": 1}, {"a": 2, "b": 3}, {}]),
        "messages": msgs,
        "tools": [{"name": "f", "parameters": {"p": 1}}] if rng.random() < 0.3 else None,
    }


env = ImmutableSandboxedEnvironment(
    trim_blocks=True, lstrip_blocks=True, extensions=[jinja2.ext.loopcontrols])
env.filters["tojson"] = lambda x, ensure_ascii=False, indent=None, separators=None, sort_keys=False: \
    json.dumps(x, ensure_ascii=ensure_ascii, indent=indent, separators=separators, sort_keys=sort_keys)

rows = []
counts = collections.Counter()
CASES = 3000
for i in range(CASES):
    names = list(NAMES := ["v", "w", "s", "n", "i", "f", "b", "seq", "slist",
                            "ilist", "data", "d", "messages", "tools"])
    template = block(3, names)
    if rng.random() < 0.12:
        template += rng.choice(ERROR_FRAGMENTS)
    bound = bindings()
    argument = {"template": template, "bindings": bound}
    try:
        render = env.from_string(template)
        output = render.render(
            raise_exception=lambda m: (_ for _ in ()).throw(ValueError(m)),
            namespace=lambda **kw: jinja2.utils.Namespace(**kw),
            strftime_now=lambda f: datetime.datetime.now().strftime(f),
            **bound)
        expected = {"output": re.sub(r"at 0x[0-9a-fA-F]+", "at 0x0", output)}
    except Exception as error:
        expected = {"error": type(error).__name__}
    rows.append("\t".join([base64.b64encode(wire(argument)).decode(),
                           base64.b64encode(wire(expected)).decode()]))
    counts["expected_error" if "error" in expected else "expected_output"] += 1

fixture = root / "src/test/resources/python-0.3.36-jinjafuzz-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = {
    "sourceCommit": audit["baseline"]["commit"],
    "fixtureRuntime": sys.version,
    "jinjaVersion": jinja2.__version__,
    "caseCount": len(rows),
    "outcomes": dict(counts),
    "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
    "grammar": "type-aware seeded random templates over literals/ops/index/slice/"
               "str-methods/filters/for/if/set/namespace/macro/loop-controls/"
               "whitespace/comments/raw + ~12%% supported error constructs; "
               "error cases only require a Java failure, not the exception type.",
    "regenerate": "python tools/python_compat/generate_jinjafuzz_contracts.py --source-root .tools/python-api-audit",
}
(root / "docs/python-jinjafuzz-contracts.json").write_text(
    json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
