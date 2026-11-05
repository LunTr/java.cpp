"""Differential fixtures for the dev.javacpp.python CPython-semantics layer.

Executes the real CPython 3.13 builtin protocol (truth/len/iter/repr/str,
get/set/del item, membership, rich comparisons, arithmetic, int/float/index
conversions, slicing), all 38 ported str methods, strict UTF-8 decoding,
bytes/str repr, isprintable ranges and json.dumps/loads, and records inputs
with exact results or exception (type, str(exc)) for Java replay.

Usage: conda run -n base python tools/python_compat/generate_semantics_contracts.py \
    --source-root .tools/python-api-audit
No llama_cpp code is exercised; --source-root is still verified against the
pinned audit manifest like every other generator.
"""

from contract_wire import wire

import argparse
import base64
import collections
import hashlib
import json
import operator
import pathlib
import random
import re
import struct
import sys
import unicodedata

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


class Idx:
    def __init__(self, value):
        self.value = value

    def __index__(self):
        return self.value


def tag(value):
    if value is None:
        return ["none"]
    if isinstance(value, bool):
        return ["bool", value]
    if isinstance(value, int):
        return ["int", str(value)]
    if isinstance(value, float):
        if value != value:
            return ["float", "nan"]
        if value == float("inf"):
            return ["float", "inf"]
        if value == float("-inf"):
            return ["float", "-inf"]
        return ["float", struct.pack(">d", value).hex()]
    if isinstance(value, str):
        return ["str", re.sub(r"at 0x[0-9a-fA-F]+", "at 0x0", value)]
    if isinstance(value, (bytes, bytearray)):
        return ["bytes", bytes(value).hex()]
    if isinstance(value, tuple):
        return ["tuple", [tag(v) for v in value]]
    if isinstance(value, list):
        return ["list", [tag(v) for v in value]]
    if isinstance(value, dict):
        return ["dict", [[tag(k), tag(v)] for k, v in value.items()]]
    if isinstance(value, frozenset):
        return ["frozenset", sorted((tag(v) for v in value), key=repr)]
    if isinstance(value, set):
        return ["set", sorted((tag(v) for v in value), key=repr)]
    if isinstance(value, slice):
        return ["slice", tag(value.start), tag(value.stop), tag(value.step)]
    if isinstance(value, Idx):
        return ["indexobj", str(value.value)]
    if type(value) is object:
        return ["object"]
    raise TypeError("untagged " + type(value).__name__)


def tagged_case(op, arguments):
    return {"op": op, "args": [tag(v) for v in arguments]}


rows = []
counts = collections.Counter()


def normalize(message):
    # CPython object reprs embed a heap address; pin it so the Java stub matches.
    return re.sub(r"at 0x[0-9a-fA-F]+", "at 0x0", message)


def record(op, arguments, thunk):
    try:
        expected = tag(thunk())
    except Exception as error:
        expected = ["err", type(error).__name__, normalize(str(error))]
    rows.append("\t".join([op, base64.b64encode(wire(tagged_case(op, arguments))).decode(),
                           base64.b64encode(wire(expected)).decode()]))
    counts[op] += 1


# ---- Value universe ---------------------------------------------------------------------------

INTS = [0, 1, -1, 2, 7, 10, 97, 255, 256, 2**31 - 1, -2**31, 2**31, 2**63 - 1,
        -(2**63), 2**63, 2**64, -(2**63) - 1, 10**30, -(10**30), 10**100]
FLOATS = [0.0, -0.0, 1.5, -2.75, 0.1, 1 / 3, 1e-4, 1e-5, 1e15, 1e16, 1e17, 1e23,
          5e-324, 2.2250738585072014e-308, 1.7976931348623157e308, float("inf"),
          float("-inf"), float("nan"), 9007199254740993.0]
STRS = ["", "a", "abc", "hello world", " Hello\tWorld \n", "aaa", "abab", "a,b,,c,",
        "中文测试", "🙂🙃x", "ßẞİıſﬀ", "ǅǆ", "A1 ²½四", "á", "x\ny\r\nz\rv",
        "\x00\x1f\x7f", "-123", "+4", "aXbXc", "line1 line2", "'\"\\",
        "१२३", "𝟙𝟚", "👨‍👩‍👧", "\ud800x\udfff", "  12  ", "1_000", ".5", "1e3",
        "nan", "-inf", "infinity", "0x10", "None", "True"]
BYTES = [b"", b"a", b"abc", b"\x00\xff", b"'", b'"', b"'\"", b"\\", b"\t\n\r",
         bytes(range(256)), b"\xc3\xa9", b"\xe4\xb8\xad", b"\xf0\x9f\x99\x82"]
LISTS = [[], [1], [1, "a", None], [True, 1, 1.0], [[1, [2]], 3], list(range(10)),
         [0.0], [None]]
TUPLES = [(), (1,), (1, "a"), ((1, 2), (3,)), tuple(range(8))]
DICTS = [{}, {"a": 1}, {1: "x", "1": "y", 1.0: "z"}, {True: "b"}, {None: 0},
         {"k": {"n": [1, 2]}}, {(1, 2): "t"}, {float("nan"): "nan"}, {1: "a", True: "b"}]
SETS = [set(), {1, 2, 3}, {"a", "b"}, {1, True}, frozenset({1, 2})]
OTHERS = [None, True, False, Idx(3), Idx(-2), Idx(2**70)]
VALUES = OTHERS[:3] + INTS + FLOATS + STRS + BYTES + LISTS + TUPLES + DICTS + SETS + OTHERS[3:]

rng = random.Random(20261105)
for _ in range(24):
    FLOATS.append(struct.unpack(">d", rng.getrandbits(64).to_bytes(8, "big"))[0])
for _ in range(30):
    pool = INTS + FLOATS + STRS + LISTS + TUPLES
    choice = rng.choice(pool)
    VALUES.append(choice)

# ---- Scalar operations ------------------------------------------------------------------------

def cInt(v):
    n = operator.index(v)
    if not (-(2**31) <= n <= 2**31 - 1):
        raise OverflowError("Python int too large to convert to C long")
    return n


for v in VALUES:
    record("truth", [v], lambda v=v: bool(v))
    record("len", [v], lambda v=v: len(v))
    record("str", [v], lambda v=v: str(v))
    record("repr", [v], lambda v=v: repr(v))
    record("int", [v], lambda v=v: int(v))
    record("float", [v], lambda v=v: float(v))
    record("index", [v], lambda v=v: operator.index(v))
    record("iter", [v],
           lambda v=v: sorted(iter(v), key=repr) if isinstance(v, (set, frozenset))
           else list(iter(v)))
    record("neg", [v], lambda v=v: -v)
    record("cint", [v], lambda v=v: cInt(v))


# int()/float() parsing on extra string shapes
for s in ["", " ", "  ", "\t12\n", "1 2", "++1", "--1", "+-1", "1_", "_1", "1__2",
          "０１２", "١٢٣", "४५६", "𝟙𝟚𝟛", "²", "½", "四", "0b11", "0o17", "1e", "1e+",
          "1e-", ".e3", "1.e3", ".5e-2", "1_0.5_0e1_0", "inf", "-infinity", "+nan",
          "-nan", "NaN", "INFINITY", "1,000", "\x0012", "12\x00", "１２.５", "9" * 400]:
    record("int", [s], lambda s=s: int(s))
    record("float", [s], lambda s=s: float(s))
for b in [b"12", b" 12 ", b"1_0", b"", b"1.5", b"\xff", b"1\x00", b"abc"]:
    record("int", [b], lambda b=b: int(b))
    record("float", [b], lambda b=b: float(b))

# ---- Binary operations ------------------------------------------------------------------------

PAIR_POOL = ([None, True, False] + INTS[:13] + FLOATS[:13] +
             ["", "a", "b", "abc", "abd", "中文", "🙂", "á", "Z"] +
             [b"", b"a", b"b", b"\x00", b"ab"] +
             [[], [0], [1], [1, 2], [2], ["a"], [1, "a"], [True]] +
             [(), (0,), (1,), (1, 2), (2,), ("a",)] +
             [{}, {"a": 1}, {"a": 2}, {1: "x"}, set(), {1}, {1, 2}, {2}])
SEQUENCE_KINDS = (str, bytes, list, tuple)


def safe_mul(a, b):
    # Skip pairs whose successful repetition would allocate hundreds of MB;
    # overflow/error paths are covered by dedicated cases below.
    for seq, n in [(a, b), (b, a)]:
        if isinstance(seq, SEQUENCE_KINDS) and isinstance(n, int) and not isinstance(n, bool):
            if abs(n) > 1_000_000 and len(seq) > 0:
                return True
    return False


for a in PAIR_POOL:
    for b in PAIR_POOL:
        for name, op in [("eq", operator.eq), ("ne", operator.ne), ("lt", operator.lt),
                         ("le", operator.le), ("gt", operator.gt), ("ge", operator.ge)]:
            record(name, [a, b], lambda a=a, b=b, op=op: op(a, b))
        for name, op in [("add", operator.add), ("sub", operator.sub)]:
            record(name, [a, b], lambda a=a, b=b, op=op: op(a, b))
        if not safe_mul(a, b):
            record("mul", [a, b], lambda a=a, b=b: a * b)

for seq, n in [("a", 2**63), ("a", -(2**63)), ("ab", 2**62), ("ab", -(2**62)),
               ("x" * 100, 2**60), ("", 2**63), ([], 2**63), ([0], 2**63), ([0, 1], 2**62),
               (b"a", 2**63), (b"ab", 2**62), ((), 2**63), ((1, 2), 2**62),
               ("a", 10**30), ("ab", 10**30), ("a", 2**63 + 8), ([0, 1], 10**30)]:
    record("mul", [seq, n], lambda seq=seq, n=n: seq * n)
    record("mul", [n, seq], lambda seq=seq, n=n: n * seq)

# ---- Membership, indexing, deletion ------------------------------------------------------------

CONTAINERS = ([[1, "a", None], list(range(6)), [], (1, 2, 3), tuple(range(6)), (),
               "abcdef", "a中🙂", "", b"abc\x00\xff", b"",
               {"a": 1, 1: "x", True: "y"}, {}, {1, 2, "a"}, set(),
               [1, True, 1.0], {(1, 2): "t"}, {float("nan"): "n"}, {"k": [1]}] +
              [None, 0, 1.5, "x"])
ITEMS = [0, 1, 2, -1, 5, True, False, None, "a", "", "abc", 1.0, 0.5, float("nan"),
         (1, 2), [1], [], {}, set(), Idx(2), b"a", b"\x00", 97, 255, 256, -5, 2**70]
for c in CONTAINERS:
    for i in ITEMS:
        record("in", [c, i], lambda c=c, i=i: i in c)

KEYS = [0, 1, 2, -1, -5, 5, 6, True, False, None, 1.0, 0.5, float("nan"), "a", "",
        "key", (1, 2), (), [1], [], {}, set(), Idx(1), Idx(-2), 2**70, -(2**70),
        slice(None, None, None), slice(1, None, None), slice(None, 3, None),
        slice(None, None, 2), slice(None, None, -1), slice(-3, -1, None),
        slice(2**70, None, None), slice(None, None, 0), slice(1.5, 2, None)]
SUBSCRIPTABLES = ([list(range(6)), [], [1, "a"], tuple(range(6)), (), (1, "a"),
                   "abcdef", "a中🙂x", "", b"abc\x00\xff", b"",
                   {"a": 1, 1: "x", True: "y", (1, 2): "t"}, {}, {float("nan"): "n"}] +
                  [None, 0, 1.5, {1, 2}])
for c in SUBSCRIPTABLES:
    for k in KEYS:
        record("get", [c, k], lambda c=c, k=k: c[k])

MUTABLES = [[1, 2, 3], list(range(8)), [], [1, "a"], {"a": 1, 1: "x", True: "y"}, {},
            {(1, 2): "t"}, [0] * 6]
ASSIGN_VALUES = [0, "x", None, [9], (9,), {}, 1.5, 5, "ab"]
for c in MUTABLES + [(1, 2), "abc", b"ab", None, 0, 1.5, {1, 2}]:
    keys = KEYS if isinstance(c, (list, dict)) else KEYS[:24]
    values = ASSIGN_VALUES if isinstance(c, list) else ASSIGN_VALUES[:3] if isinstance(c, dict) else ASSIGN_VALUES[:1]
    for k in keys:
        for v in values:
            def assign(c=c, k=k, v=v):
                target = list(c) if isinstance(c, list) else dict(c) if isinstance(c, dict) else c
                target[k] = v
                return target
            record("set", [c, k, v], assign)
    for k in keys:
        def delete(c=c, k=k):
            target = list(c) if isinstance(c, list) else dict(c) if isinstance(c, dict) else c
            del target[k]
            return target
        record("del", [c, k], delete)

# ---- Slicing -----------------------------------------------------------------------------------

SLICES = [slice(a, b, c) for a in [None, 0, 1, -1, 3, -3, 100, -100, 2**70, -(2**70)]
          for b in [None, 0, 2, -1, 4, -4, 100, -100]
          for c in [None, 1, 2, -1, -2, 5, -5, 0]]
SEQ = [list(range(8)), [], tuple(range(8)), "abcdefgh", "a中🙂x́", b"abc\x00\xff"]
for v in SEQ:
    for s in SLICES:
        record("slice", [v, s], lambda v=v, s=s: v[s])

# ---- str methods --------------------------------------------------------------------------------

RECEIVERS = ["", "a", "abc", "AbC dEf", "aaa", "abab", "a,b,,c,", " Hello\tWorld \n",
             "中文测试", "🙂🙃x", "ßẞİıſﬀ", "ǅǆ", "A1 ²½四", "á", "x\ny\r\nz\rv\x0b\f\x1c\x1d\x85  ",
             "\x00\x1f\x7f\xa0", "-123", "+4", "aXbXc", "0123", "áb", "𝟏２۳",
             "i", "I", "İ", "ﬀ", "ΣΣσς", "ﬁle", "  12  "]
METHOD_ARGS = {
    "startswith": [("a",), ("",), ("ab",), (("a", "z"),), (("a", 1),), (5,), ([],), (None,),
                   ("a", 1), ("a", None, 2), ("a", -2), ("a", 1, -1), ("a", 5, 2), ("a", 1, 2, 3)],
    "endswith": [("a",), ("",), ("bc",), (("a", "z"),), (("a", 1),), (5,), (None,), ("a", 1),
                 ("a", None, 2), ("a", -2), ("a", 1, -1), ("a", 5, 2), ("a", 1, 2, 3)],
    "strip": [(), (None,), ("a",), ("ab",), (" \t",), (5,), ("a", "b")],
    "lstrip": [(), (None,), ("a",), (" \t",), (5,), ("a", "b")],
    "rstrip": [(), (None,), ("a",), (" \t",), (5,), ("a", "b")],
    "split": [(), (None,), (" ",), ("",), (",",), ("a",), ("a", 0), ("a", 1), ("a", -1),
              ("a", 2), ("a", 99), (5,), ("a", "b"), ("a", 1.5), ("a", True), ("a", 1, 2)],
    "rsplit": [(), (None,), (" ",), ("",), (",",), ("a",), ("a", 0), ("a", 1), ("a", -1),
               ("a", 2), (5,), ("a", "b"), ("a", 1.5), ("a", 1, 2)],
    "splitlines": [(), (True,), (False,), (1,), (0,), ("x",), (None,), (1, 2)],
    "join": [([],), (["a", "b"],), (["a"],), (["a", ""],), (("a", "b"),), (["a", 1],), ([1],),
             ("abc",), ({"a": 1, "b": 2},), ({1, 2},), (5,), (None,), ([b"a"],), ([["x"]],), ([], [])],
    "replace": [("a", "b"), ("a", "b", 1), ("a", "b", 0), ("a", "b", -1), ("a", "b", 99),
                ("", "x"), ("", "x", 2), ("", "x", 0), ("a", ""), ("ab", "cd"),
                (5, "b"), ("a", 5), ("a", "b", "c"), ("a", "b", 1.5), ("a", "b", True), ("a",)],
    "find": [("a",), ("",), ("ab",), ("a", 1), ("a", None, None), ("a", -1), ("a", 0, 2),
             ("a", 5, 2), ("a", 1, -1), (5,), (None,), ("a", 1.5), ("a", True), (), ("a", 1, 2, 3)],
    "rfind": [("a",), ("",), ("ab",), ("a", 1), ("a", -1), ("a", 0, 2), ("a", 5, 2), (5,),
              (None,), ("a", 1.5), (), ("a", 1, 2, 3)],
    "index": [("a",), ("",), ("ab",), ("a", 1), ("a", -1), ("a", 0, 2), ("a", 5, 2), (5,),
              (None,), ("a", 1.5), (), ("a", 1, 2, 3)],
    "rindex": [("a",), ("",), ("ab",), ("a", 1), ("a", 0, 2), ("a", 5, 2), (5,), (), ("a", 1, 2, 3)],
    "count": [("a",), ("",), ("aa",), ("a", 1), ("a", -1), ("a", 0, 2), ("a", 5, 2), (5,),
              (None,), ("a", 1.5), (), ("a", 1, 2, 3)],
    "removeprefix": [("a",), ("",), ("ab",), (5,), (None,), (["a"],), (("a",),), ()],
    "removesuffix": [("a",), ("",), ("bc",), (5,), (None,), (["a"],), ()],
    "zfill": [(5,), (0,), (-3,), (3,), (True,), ("5",), (None,), (1.5,), (2**70,), ()],
    "center": [(10,), (10, "*"), (10, ""), (10, "ab"), (10, "🙂"), (0,), (-1,), ("x",), (10, 5),
               (True,), (1,), ()],
    "ljust": [(10,), (10, "*"), (10, ""), (10, "ab"), (0,), (-1,), ("x",), (10, 5), ()],
    "rjust": [(10,), (10, "*"), (10, ""), (10, "ab"), (0,), (-1,), ("x",), (10, 5), ()],
    "partition": [("a",), ("",), ("b",), (5,), (None,), (["a"],), ()],
    "rpartition": [("a",), ("",), ("b",), (5,), (None,), ()],
    "expandtabs": [(), (4,), (0,), (-1,), (1,), (16,), ("x",), (None,), (1.5,), (True,), (1, 2)],
}
for name in ["lower", "upper", "title", "capitalize", "swapcase", "isdigit", "isdecimal",
             "isnumeric", "isalpha", "isalnum", "isspace", "islower", "isupper", "istitle"]:
    METHOD_ARGS[name] = [(), ("x",), (1,)]

GENERIC_ARGS = [(), (None,), (0,), (1,), (-1,), (True,), (False,), ("a",), ("",), ("x y",),
                (1.5,), ([],), ({},), (["a"],), (["a", "b"],), (("a", "b"),), (("a", 1),),
                ("a", 1), ("a", None, None), (0, "x"), (2, "*"), ("a", "b"), ("a", "b", 0),
                ("a", "b", -1), ("a", 0, 2), ("a", -3, 9), (b"a",), (float("nan"),), (2**70,),
                ("🙂",), (Idx(2),), (None, None), ("a", "b", "c", "d")]
for name, argsets in METHOD_ARGS.items():
    for text in RECEIVERS:
        for call_args in argsets:
            record("strm", [name, text, list(call_args)],
                   lambda name=name, text=text, call_args=call_args:
                   getattr(text, name)(*call_args))
for _ in range(1600):
    name = rng.choice(list(METHOD_ARGS))
    text = rng.choice(RECEIVERS)
    call_args = rng.choice(GENERIC_ARGS)
    record("strm", [name, text, list(call_args)],
           lambda name=name, text=text, call_args=call_args:
           getattr(text, name)(*call_args))

# ---- bytes decode / repr ------------------------------------------------------------------------

utf8cases = list(BYTES)
for first in range(256):
    for tail in [b"", b"\x80", b"\xbf", b"\xc0", b"a", b"\x80\x80", b"\xbf\xbf"]:
        utf8cases.append(bytes([first]) + tail)
utf8cases += [b"\xed\xa0\x80", b"\xed\xbf\xbf", b"\xf4\x90\x80\x80", b"\xc0\xaf",
              b"\xe0\x80\x80", b"\xe0\x9f\xbf", b"\xf0\x80\x80\x80", b"\xf0\x8f\xbf\xbf",
              b"a\xc3", b"a\xe4\xb8", b"a\xf0\x9f\x99", b"\x80", b"\xbf", b"\xfe", b"\xff",
              b"\xc2\x7f", b"\xdf\xc0", "中🙂a\x00".encode("utf-8")]
for b in utf8cases:
    record("utf8", [b], lambda b=b: b.decode("utf-8"))
for b in BYTES + [b"\x01\x02", b" \x7e\x7f\x80", b"'\""]:
    record("brepr", [b], lambda b=b: repr(b))

# ---- repr/str extra coverage --------------------------------------------------------------------

for v in FLOATS:
    record("repr", [v], lambda v=v: repr(v))
for cp in list(range(0x00, 0x100)) + [0x2028, 0x2029, 0xfeff, 0xd800, 0xdfff, 0x10ffff]:
    record("repr", [chr(cp)], lambda cp=cp: repr(chr(cp)))
    record("str", [chr(cp)], lambda cp=cp: str(chr(cp)))

# Full isprintable range table
ranges = []
start = None
for cp in range(0x110000):
    p = chr(cp).isprintable()
    if p and start is None:
        start = cp
    elif not p and start is not None:
        ranges.extend([start, cp - 1])
        start = None
if start is not None:
    ranges.extend([start, 0x10FFFF])
record("printable", [], lambda: ranges)

# ---- json.dumps / loads -------------------------------------------------------------------------

JVALS = [None, True, False, 0, -1, 2**70, 1.5, -0.0, float("nan"), float("inf"),
         float("-inf"), "", "a\nb\t\"\\中🙂\x00\x1f\x7f ", [], [1, "a", None],
         {}, {"a": 1, "b": [1, 2]}, {"z": 1, "a": 2}, {1: "i", True: "b", 1.5: "f", None: "n"},
         {(1, 2): "t"}, {b"x": 1}, set(), {1, 2}, (1, 2), b"x", object()]
for v in JVALS:
    for opts in [(True, None, None, False), (False, None, None, False),
                 (True, 2, None, False), (True, 0, None, False), (False, 4, None, True),
                 (True, None, [",", ":"], False), (False, 1, ["|", "="], True)]:
        ascii_flag, indent, separators, sort = opts
        record("jdumps", [v, ascii_flag, indent, separators, sort],
               lambda v=v, a=ascii_flag, i=indent, s=separators, k=sort:
               json.dumps(v, ensure_ascii=a, indent=i,
                          separators=None if s is None else tuple(s), sort_keys=k))
JTEXTS = ["", " ", "1", "-1", "0", "01", "1.5", "1e3", "-0.0", "1e999", "1e-999",
          "123456789012345678901234567890", "true", "false", "null", "True", "nul",
          "NaN", "Infinity", "-Infinity", "infinity", '"a"', '"a', '"\\n"', '"\\x"',
          '"\\u0041"', '"\\u"', '"\\ud800"', '"a\nb"', "[]", "[1,2]", "[1,]", "[,1]",
          "{}", '{"a":1}', '{"a":1,}', '{a:1}', '{"a"}', '{"a":}', '{"a":1 "b":2}',
          '{"a":1,,"b":2}', "[1 2]", "  [ 1 , 2 ]  ", "x", "1 2", "[1]x", '"\\/"',
          '"\\ud800\\udc00"', '"𝄞"', "[[[1]]]", '{"k":{}}', "\t1\n", "nulll",
          "--1", "+1", ".5", "1.", "1.e3", "0x1", "[true,false,null]",
          '{"\\u0061": 1}', '"line\nbreak"']
for s in JTEXTS:
    record("jloads", [s], lambda s=s: json.loads(s))
record("jloads", [None], lambda: json.loads(None))

fixture = root / "src/test/resources/python-0.3.36-semantics-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
manifest = {"pythonPackage": audit["baseline"]["version"], "sourceCommit": audit["baseline"]["commit"],
            "fixtureRuntime": sys.version, "unicodeVersion": unicodedata.unidata_version,
            "caseCount": len(rows), "casesByOperation": dict(counts),
            "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "method": "Execute CPython 3.13 builtin protocol and str methods on a tagged value universe; record exact results or exception type plus str(exc). Tags: none/bool/int/float(bits|nan|inf)/str/bytes/list/tuple/dict/set/slice/indexobj/err."}
(root / "docs/python-semantics-contracts.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"caseCount": len(rows), "casesByOperation": dict(counts)}, indent=2))
