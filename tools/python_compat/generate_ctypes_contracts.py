"""Execute the pinned _ctypes_extensions.py bodies over traced os/sys/ctypes/pathlib ports."""

import argparse
import ast
import base64
import functools
import hashlib
import json
import pathlib
import sys
import types
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
tree = ast.parse((args.source_root / "llama_cpp/_ctypes_extensions.py").read_text(encoding="utf-8"))
defs = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
byref_assign = next(node for node in tree.body
                    if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", "") == "byref")
assert set(defs) == {"load_shared_library", "ctypes_function_for_shared_library", "_byref"}
assert [node.name for node in defs["ctypes_function_for_shared_library"].body
        if isinstance(node, ast.FunctionDef)] == ["ctypes_function"]
outer = next(node for node in defs["ctypes_function_for_shared_library"].body
             if isinstance(node, ast.FunctionDef))
assert [node.name for node in outer.body
        if isinstance(node, ast.FunctionDef)] == ["decorator"]

ERRORS = {"OSError": OSError, "RuntimeError": RuntimeError, "ValueError": ValueError}
namespace = dict(functools=functools, TYPE_CHECKING=False,
                 _EMSCRIPTEN_SIDE_MODULE_SUFFIX=".cpython-00-wasm32-emscripten.so")


def capture(action):
    try:
        return dict(result=action())
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))


class FakePath:
    """pathlib.Path double: joins with /, looks up the case-provided file set."""

    def __init__(self, trace, existing, text):
        self._trace, self._existing, self._text = trace, existing, text

    def __truediv__(self, name):
        return FakePath(self._trace, self._existing, self._text + "/" + name)

    def exists(self):
        self._trace.append(["exists", self._text])
        return self._text in self._existing

    def __str__(self):
        return self._text


class Environ(dict):
    """os.environ double that records every mapping protocol touch in order."""

    def __init__(self, trace, pairs):
        super().__init__(pairs)
        self._trace = trace

    def __getitem__(self, key):
        self._trace.append(["env-get", key])
        return super().__getitem__(key)

    def __setitem__(self, key, value):
        self._trace.append(["env-set", key, value])
        super().__setitem__(key, value)

    def __contains__(self, key):
        self._trace.append(["env-has", key])
        return super().__contains__(key)

    def get(self, key, default=None):
        self._trace.append(["env-getdef", key, default])
        return super().get(key, default)


class CdllResult:
    """ctypes.CDLL instance double; only its origin path is compared."""

    def __init__(self, path):
        self.path = path


def run_load(case):
    trace = []
    existing = set(case.get("existing", []))
    environ = Environ(trace, case.get("env", []))
    failures = {entry[0]: entry[1:] for entry in case.get("fail", [])}
    base = FakePath(trace, existing, case["base"])

    class OsPath:
        @staticmethod
        def join(directory, name):
            trace.append(["join", directory, name])
            return directory + "/" + name

    class Os:
        def __init__(self):
            self.environ = environ
            self.pathsep = case.get("pathsep", ";")
            self.path = OsPath()

        @staticmethod
        def add_dll_directory(directory):
            trace.append(["add-dll-dir", directory])

    class Ctypes:
        RTLD_GLOBAL = case.get("rtld", 2)

        @staticmethod
        def CDLL(path, **kwargs):
            trace.append(["cdll", path, [[key, kwargs[key]] for key in kwargs]])
            failure = failures.get(path)
            if failure:
                raise ERRORS[failure[0]](failure[1])
            return CdllResult(path)

    ns = dict(namespace)
    ns["sys"] = types.SimpleNamespace(
        platform=case["platform"], version_info=tuple(case.get("version", [3, 13, 9])))
    ns["os"] = Os()
    ns["ctypes"] = Ctypes
    exec(compile(ast.fix_missing_locations(ast.Module(
        body=[future, defs["load_shared_library"]], type_ignores=[])),
        "pinned-ctypes", "exec"), ns)

    def call():
        result = ns["load_shared_library"](case["name"], base)
        return ["lib", result.path] if isinstance(result, CdllResult) else result

    outcome = capture(call)
    outcome["trace"] = trace
    outcome["env"] = [[key, environ[key]] for key in environ]
    return outcome


class FakeFunc:
    """ctypes function pointer double: arbitrary attributes land in __dict__."""


def run_decorator(case):
    trace = []
    ns = dict(namespace)
    exec(compile(ast.fix_missing_locations(ast.Module(
        body=[future, defs["ctypes_function_for_shared_library"]], type_ignores=[])),
        "pinned-ctypes", "exec"), ns)

    class Lib:
        def __getattr__(self, name):
            trace.append(["getattr", name])
            if case.get("missing"):
                raise AttributeError(f"function '{name}' not found")
            func = FakeFunc()
            self.__dict__[name] = func
            return func

    kind = case.get("f", "function")
    if kind == "function":
        def f():
            """doc"""
        f.__module__ = "mod"
        f.extra = 1
    elif kind == "callable":
        class Callable:
            def __init__(self):
                self.extra = 1
            def __call__(self):
                return None
        f = Callable()
    elif kind == "mapping":
        f = {"extra": 1}
    else:
        f = object()

    closure = ns["ctypes_function_for_shared_library"](Lib())
    outcome = capture(lambda: closure(*case["args"], **case.get("kwargs", {})))
    if "error" in outcome:
        outcome["trace"] = trace
        return outcome
    decorator = outcome.pop("result")
    outcome = capture(lambda: decorator(*case.get("f_args", [f]), **case.get("f_kwargs", {})))
    result = outcome.get("result")
    if result is not None and result is not f:
        outcome["result"] = "func"
        outcome["func"] = [[key, "<wrapped>" if key == "__wrapped__" else value]
                           for key, value in result.__dict__.items()]
    elif result is f:
        outcome["result"] = "f"
    outcome["trace"] = trace
    return outcome


def run_byref(case):
    import ctypes as real_ctypes
    ns = dict(namespace, ctypes=real_ctypes)
    exec(compile(ast.fix_missing_locations(ast.Module(
        body=[future, defs["_byref"], byref_assign], type_ignores=[])),
        "pinned-ctypes", "exec"), ns)
    objects = {"obj": real_ctypes.c_int(7)}
    arguments = [objects.get(value, value) for value in case.get("args", [])]
    outcome = capture(lambda: ns[case["call"]](*arguments, **case.get("kwargs", {})))
    result = outcome.get("result")
    if result is not None:
        # cparam 'P' stores the object address plus offset; record the applied byte delta.
        pointer = real_ctypes.cast(result, real_ctypes.c_void_p).value
        outcome["result"] = ["cparam", pointer - real_ctypes.addressof(arguments[0])]
    return outcome


load_cases = [
    dict(name="llama", platform="emscripten", base="em/base",
         existing=["em/base/libllama.cpython-00-wasm32-emscripten.so"]),
    dict(name="llama", platform="linux", base="lx/base", existing=["lx/base/libllama.so"]),
    dict(name="llama", platform="freebsd13", base="fb/base", existing=["fb/base/libllama.so"]),
    dict(name="llama", platform="darwin", base="dy/base", existing=["dy/base/libllama.dylib"]),
    dict(name="llama", platform="darwin", base="so/base", existing=["so/base/libllama.so"]),
    dict(name="llama", platform="darwin", base="dy/both",
         existing=["dy/both/libllama.so", "dy/both/libllama.dylib"]),
    dict(name="llama", platform="win32", base="w/base", existing=["w/base/llama.dll"],
         env=[["PATH", "p0"]]),
    dict(name="llama", platform="win32", base="w2/base", existing=["w2/base/libllama.dll"],
         env=[["PATH", "p0"]]),
    dict(name="llama", platform="win32", base="w3/base", existing=["w3/base/llama.dll"],
         version=[3, 7, 9], env=[["PATH", "p0"]]),
    dict(name="llama", platform="win32", base="w4/base", existing=["w4/base/llama.dll"],
         env=[["PATH", "p0"], ["CUDA_PATH", "C:/cuda"], ["HIP_PATH", "C:/hip"]]),
    dict(name="llama", platform="sunos5", base="s/base"),
    dict(name="llama", platform="win32", base="w5/base", existing=[], env=[]),
    dict(name="llama", platform="linux", base="lx/none", existing=[]),
    dict(name="llama", platform="darwin", base="dy/none", existing=[]),
    dict(name="llama", platform="emscripten", base="em/none", existing=[]),
    dict(name="llama", platform="win32", base="w7/base", existing=["w7/base/llama.dll"],
         env=[["PATH", "p0"]], fail=[["w7/base/llama.dll", "OSError", "dll broken"]]),
    dict(name="llama", platform="linux", base="lx/bad", existing=["lx/bad/libllama.so"],
         fail=[["lx/bad/libllama.so", "OSError", "wrong elf"]]),
    dict(name="llama", platform="darwin", base="dy/bad", existing=["dy/bad/libllama.so"],
         fail=[["dy/bad/libllama.so", "OSError", "bad so"]]),
    dict(name="llama", platform="emscripten", base="em/dep", existing=[
        "em/dep/libllama.cpython-00-wasm32-emscripten.so",
        "em/dep/libggml-base.cpython-00-wasm32-emscripten.so",
        "em/dep/libggml.cpython-00-wasm32-emscripten.so"]),
    dict(name="mtmd", platform="emscripten", base="em/mtmd", existing=[
        "em/mtmd/libmtmd.cpython-00-wasm32-emscripten.so",
        "em/mtmd/libggml-base.cpython-00-wasm32-emscripten.so",
        "em/mtmd/libllama.cpython-00-wasm32-emscripten.so"]),
    dict(name="other", platform="emscripten", base="em/other", existing=[
        "em/other/libother.cpython-00-wasm32-emscripten.so",
        "em/other/libggml.cpython-00-wasm32-emscripten.so"]),
    dict(name="llama", platform="emscripten", base="em/env",
         env=[["LD_LIBRARY_PATH", "em/env"]],
         existing=["em/env/libllama.cpython-00-wasm32-emscripten.so"]),
    dict(name="llama", platform="emscripten", base="em/env2",
         env=[["LD_LIBRARY_PATH", "x:y"]],
         existing=["em/env2/libllama.cpython-00-wasm32-emscripten.so"]),
    dict(name="llama", platform="emscripten", base="em/env3",
         env=[["LD_LIBRARY_PATH", ""]],
         existing=["em/env3/libllama.cpython-00-wasm32-emscripten.so"]),
    dict(name="llama", platform="emscripten", base="em/depbad", existing=[
        "em/depbad/libllama.cpython-00-wasm32-emscripten.so",
        "em/depbad/libggml-base.cpython-00-wasm32-emscripten.so"],
        fail=[["em/depbad/libggml-base.cpython-00-wasm32-emscripten.so", "OSError", "dep bad"]]),
    dict(name="llama", platform="emscripten", base="em/depbad2", existing=[
        "em/depbad2/libllama.cpython-00-wasm32-emscripten.so",
        "em/depbad2/libggml-base.cpython-00-wasm32-emscripten.so",
        "em/depbad2/libggml-cpu.cpython-00-wasm32-emscripten.so"],
        fail=[["em/depbad2/libggml-cpu.cpython-00-wasm32-emscripten.so", "ValueError", "cpu bad"]]),
    dict(name="llama", platform="win32", base="w8/base", existing=["w8/base/llama.dll"],
         version=[3, 7, 9], env=[["PATH", "p0"], ["CUDA_PATH", "C:/cuda"]]),
    dict(name="llama", platform="linux", base="lx/extra", existing=["lx/extra/libllama.so"],
         env=[["IGNORED", "1"]]),
]
rows = ["\t".join(["load", base64.b64encode(wire(case)).decode(),
                       base64.b64encode(wire(run_load(case))).decode()])
        for case in load_cases]

decorator_cases = [
    dict(args=["sym", ["int"], "float"], f="function"),
    dict(args=["sym", None, None, True], f="callable"),
    dict(args=["sym", [], "void*", False], f="function"),
    dict(args=["sym", [], "void*", 0], f="callable"),
    dict(args=["sym", [], "void*", ""], f="mapping"),
    dict(args=["sym", [], "void*", [1]], f="plain"),
    dict(args=["missing", [], None], f="function", missing=True),
    dict(args=[], f="function"),
    dict(args=["a", "b"], f="function"),
    dict(args=["a", "b", "c", "d", "e"], f="function"),
    dict(args=["a", "b", "c"], kwargs={"enabled": False}, f="function"),
    dict(args=["a", "b", "c"], kwargs={"bad": 1}, f="function"),
    dict(args=["a", "b", "c"], kwargs={"name": "x"}, f="function"),
    dict(args=["a", "b", "c", "d"], kwargs={"enabled": True}, f="function"),
    dict(args=["sym", [], None], f="function", f_args=[]),
    dict(args=["sym", [], None], f="function", f_args=["a", "b"]),
    dict(args=["sym", [], None], f="function", f_kwargs={"f": "x"}),
    dict(args=["sym", [], None], f="function", f_args=["a"], f_kwargs={"f": "x"}),
    dict(args=["sym", [], None], f="function", f_args=[], f_kwargs={"f": "x"}),
    dict(args=["sym", [], None], f="function", f_args=[], f_kwargs={"bad": 1}),
]
rows += ["\t".join(["decorator", base64.b64encode(wire(case)).decode(),
                        base64.b64encode(wire(run_decorator(case))).decode()])
         for case in decorator_cases]

byref_cases = [
    dict(call="byref", args=["obj"]),
    dict(call="byref", args=["obj", 5]),
    dict(call="byref", args=["obj", -1]),
    dict(call="byref", args=["obj", True]),
    dict(call="byref", args=["obj", 1099511627776]),
    dict(call="byref", args=["obj", "a"]),
    dict(call="byref", args=["obj", 1.5]),
    dict(call="byref", args=["obj", None]),
    dict(call="byref", args=["str"]),
    dict(call="byref", args=["obj", 3, 7]),
    dict(call="byref", args=[]),
    dict(call="byref", args=["obj"], kwargs={"offset": 1}),
    dict(call="_byref", args=[1, 2]),
    dict(call="_byref", args=[1]),
    dict(call="_byref", args=[]),
    dict(call="_byref", args=[1, 2, 3]),
    dict(call="_byref", args=[1], kwargs={"offset": 4}),
    dict(call="_byref", args=[1], kwargs={"obj": 2}),
]
rows += ["\t".join(["byref", base64.b64encode(wire(case)).decode(),
                        base64.b64encode(wire(run_byref(case))).decode()])
         for case in byref_cases]

fixture = root / "src/test/resources/python-0.3.36-ctypes-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=5,
                functions=["load_shared_library", "ctypes_function_for_shared_library",
                           "ctypes_function_for_shared_library.ctypes_function",
                           "ctypes_function_for_shared_library.ctypes_function.decorator",
                           "_byref"],
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Complete hash-verified _ctypes_extensions.py bodies over traced os/sys/ctypes/pathlib ports. CDLL loads, DLL-directory and environ effects are port-owned; no real shared library is loaded.")
(root / "docs/python-ctypes-contracts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
