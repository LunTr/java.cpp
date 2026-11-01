"""Execute pinned mtmd_cpp.py real-bodied functions; result+warning+trace contract."""

import argparse
import ast
import base64
import hashlib
import json
import pathlib
import sys
import types
import warnings

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

src = (args.source_root / "llama_cpp/mtmd_cpp.py").read_text(encoding="utf-8")
tree = ast.parse(src)
funcs = {n.name: n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)}


def drive(case):
    trace = []
    class _Sub:
        def __class_getitem__(cls, k):
            return k

    ns = {"warnings": warnings, "__name__": "pinned-mtmd", "mtmd_context_p": int,
          "mtmd_bitmap_p": int, "mtmd_helper_init_opt": dict, "Union": _Sub,
          "Optional": _Sub, "c_bool": bool, "CtypesArray": list,
          "c_uint8": int, "c_size_t": int}

    def mtmd_get_audio_sample_rate(ctx):
        trace.append(["mtmd_get_audio_sample_rate", ctx])
        return case.get("sample_rate", 24000)

    def mtmd_helper_init_opt_default():
        trace.append(["mtmd_helper_init_opt_default"])
        return {"opt": "default"}

    def _file_wrapper(ctx, fname, placeholder, opt):
        trace.append(["mtmd_helper_bitmap_init_from_file_wrapper", ctx, fname, bool(placeholder), opt])
        return types.SimpleNamespace(bitmap=case.get("bitmap", "bmp0"), video_ctx=None)

    def _buf_wrapper(ctx, buf, length, placeholder, opt):
        trace.append(["mtmd_helper_bitmap_init_from_buf_wrapper", ctx, buf, length, bool(placeholder), opt])
        return types.SimpleNamespace(bitmap=case.get("bitmap", "bmp0"), video_ctx=None)

    ns["mtmd_get_audio_sample_rate"] = mtmd_get_audio_sample_rate
    ns["mtmd_helper_init_opt_default"] = mtmd_helper_init_opt_default
    ns["mtmd_helper_bitmap_init_from_file_wrapper"] = _file_wrapper
    ns["mtmd_helper_bitmap_init_from_buf_wrapper"] = _buf_wrapper
    for name in case["funcs"]:
        exec(compile(ast.Module(body=[funcs[name]], type_ignores=[]),
                     "pinned-mtmd", "exec"), ns)

    results = []
    for step in case["ops"]:
        if step[0] == "bitrate":
            with warnings.catch_warnings(record=True) as caught:
                warnings.simplefilter("always")
                try:
                    out = ns["mtmd_get_audio_bitrate"](*step[1:])
                    results.append(out)
                except Exception as e:
                    results.append(dict(error=type(e).__name__, message=str(e)))
            for w in caught:
                trace.append(["warn", w.category.__name__, str(w.message)])
        elif step[0] == "fromfile":
            try:
                results.append(ns["mtmd_helper_bitmap_init_from_file"](*step[1:]))
            except Exception as e:
                results.append(dict(error=type(e).__name__, message=str(e)))
        elif step[0] == "frombuf":
            try:
                results.append(ns["mtmd_helper_bitmap_init_from_buf"](*step[1:]))
            except Exception as e:
                results.append(dict(error=type(e).__name__, message=str(e)))
        else:
            raise AssertionError("unknown op " + step[0])
    return dict(results=results, trace=trace)


cases = [
    dict(funcs=["mtmd_get_audio_bitrate"], ops=[["bitrate", "ctx0"]], sample_rate=24000),
    dict(funcs=["mtmd_get_audio_bitrate"], ops=[["bitrate", None]], sample_rate=-1),
    dict(funcs=["mtmd_helper_bitmap_init_from_file"],
         ops=[["fromfile", "ctx0", "img.png", True, None],
              ["fromfile", "ctx0", "img.png", False, {"opt": "custom"}]],
         bitmap="bmp-file"),
    dict(funcs=["mtmd_helper_bitmap_init_from_buf"],
         ops=[["frombuf", "ctx0", "hexbuf", 4, True, None]], bitmap="bmp-buf"),
]

rows = []
for case in cases:
    rows.append("\t".join(["mtmd", base64.b64encode(wire(case)).decode(),
                           base64.b64encode(wire(drive(case))).decode()]))
fixture = root / "src/test/resources/python-0.3.36-mtmd-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")

meta = {
    "source": "llama_cpp/mtmd_cpp.py",
    "cases": len(cases),
    "functions": ["mtmd_get_audio_bitrate", "mtmd_helper_bitmap_init_from_file",
                  "mtmd_helper_bitmap_init_from_buf"],
    "note": "ctypes binding declarations are bound via internal.MtmdLibrary JNA; "
            "this contract covers the real-bodied deprecation wrapper.",
}
(root / "docs/python-mtmd-contracts.json").write_text(
    json.dumps(meta, indent=2) + "\n", encoding="utf-8")
print("wrote", fixture, len(cases), "cases")
