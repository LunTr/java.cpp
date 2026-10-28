"""Generate Java keyword/default binding tables from the verified original signatures."""
import argparse
import ast
import hashlib
import json
import pathlib

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
data = (args.source_root / "llama_cpp/llama.py").read_bytes()
entry = next(x for x in audit["baseline"]["files"] if x["path"] == "llama_cpp/llama.py")
assert hashlib.sha256(data).hexdigest() == entry["sha256"]
tree = ast.parse(data)
llama = next(x for x in tree.body if isinstance(x, ast.ClassDef) and x.name == "Llama")
constants = {"llama_cpp.LLAMA_DEFAULT_SEED": 4294967295, "llama_cpp.LLAMA_SPLIT_MODE_LAYER": 1,
             "llama_cpp.LLAMA_ROPE_SCALING_TYPE_UNSPECIFIED": -1, "llama_cpp.LLAMA_POOLING_TYPE_UNSPECIFIED": -1,
             "llama_cpp.LLAMA_ATTENTION_TYPE_UNSPECIFIED": -1}

def java(value):
    if value is None: return "null"
    if value is True: return "true"
    if value is False: return "false"
    if isinstance(value, str): return json.dumps(value, ensure_ascii=True)
    if isinstance(value, list): return "new java.util.ArrayList<>()" if not value else "new java.util.ArrayList<>(java.util.List.of(" + ",".join(java(x) for x in value) + "))"
    if isinstance(value, int): return str(value) + ("L" if value > 2147483647 else "")
    return repr(value)

parts = ["package io.github.yaulun.javacpp.compat.python;\n\n",
         "/** Generated from hash-verified Python 0.3.36 signatures. */\nfinal class PythonLlamaDefaults {\n",
         "private PythonLlamaDefaults() {}\nprivate static final java.util.Map<String, java.util.Map<String,Object>> defaults = new java.util.LinkedHashMap<>();\n",
         "private static final java.util.Map<String,java.util.List<String>> required = new java.util.LinkedHashMap<>();\nprivate static final java.util.Map<String,java.util.List<String>> forwarding = new java.util.LinkedHashMap<>();\nstatic {\n"]
for method in llama.body:
    if not isinstance(method, ast.FunctionDef): continue
    if method.name not in {"__init__", "_init_sampler", "sample", "generate", "embed", "create_embedding", "_create_completion", "create_completion", "__call__", "create_chat_completion", "from_pretrained"}: continue
    parameters = method.args.posonlyargs + method.args.args
    values = [None] * (len(parameters) - len(method.args.defaults)) + method.args.defaults
    pairs, required = [], []
    for arg, value in zip(parameters + method.args.kwonlyargs, values + method.args.kw_defaults):
        if arg.arg in {"self", "cls"}: continue
        if value is None: required.append(arg.arg); continue
        expression = ast.unparse(value)
        literal = constants[expression] if expression in constants else ast.literal_eval(value)
        pairs += [java(arg.arg), java(literal)]
    parts.append("defaults.put(" + java(method.name) + ", PythonValues.dict(" + ",".join(pairs) + "));\n")
    parts.append("required.put(" + java(method.name) + ", java.util.List.of(" + ",".join(java(x) for x in required) + "));\n")
    targets = {"create_completion": "self._create_completion", "__call__": "self.create_completion", "create_chat_completion": "handler"}
    if method.name in targets:
        call = next(n for n in ast.walk(method) if isinstance(n, ast.Call) and ast.unparse(n.func) == targets[method.name])
        parts.append("forwarding.put(" + java(method.name) + ", java.util.List.of(" + ",".join(java(k.arg) for k in call.keywords) + "));\n")
parts.append("""}
static java.util.Map<String,Object> bind(String method, java.util.Map<String,Object> supplied) {
  java.util.Map<String,Object> result = new java.util.LinkedHashMap<>(defaults.get(method));
  java.util.List<String> needs = required.get(method);
  for (String key : supplied.keySet()) {
    if (!method.equals("__init__") && !method.equals("from_pretrained") && !result.containsKey(key) && !needs.contains(key))
      throw PythonValues.error(PythonErrorType.TYPE_ERROR, "Llama." + method + "() got an unexpected keyword argument '" + key + "'");
  }
  for (String key : needs) if (!supplied.containsKey(key))
    throw PythonValues.error(PythonErrorType.TYPE_ERROR, "Llama." + method + "() missing 1 required positional argument: '" + key + "'");
  result.putAll(supplied);
  return result;
}
static java.util.Map<String,Object> forward(String method, java.util.Map<String,Object> options) {
  java.util.Map<String,Object> result = new java.util.LinkedHashMap<>();
  for (String key : forwarding.get(method)) result.put(key, options.get(key));
  return result;
}
""")
stateMethod = next(n for n in llama.body if isinstance(n, ast.FunctionDef) and n.name == "__getstate__")
stateCall = next(n for n in ast.walk(stateMethod) if isinstance(n, ast.Call) and ast.unparse(n.func) == "dict")
def stateExpression(node):
    if isinstance(node, ast.Attribute):
        name = ast.unparse(node)
        if name.startswith("self.model_params."): return 'PythonValues.get(model,' + java(node.attr) + ')'
        if name.startswith("self.context_params."): return 'PythonValues.get(context,' + java(node.attr) + ')'
        return 'PythonValues.get(values,' + java(node.attr) + ')'
    if isinstance(node, ast.Compare):
        left = stateExpression(node.left)
        if isinstance(node.ops[0], ast.In):
            numbers = [1,3] if ast.unparse(node).endswith('LLAMA_LOAD_MODE_MMAP_MLOCK)') and 'LLAMA_LOAD_MODE_MMAP,' in ast.unparse(node) else [2,3]
            return 'java.util.List.of(' + ','.join(map(str,numbers)) + ').contains(((Number)' + left + ').intValue())'
        return '(((Number)' + left + ').intValue() == 1)'
    raise ValueError(ast.dump(node))
parts.append('static java.util.Map<String,Object> snapshot(java.util.Map<String,Object> values, java.util.Map<String,Object> model, java.util.Map<String,Object> context) { return PythonValues.dict(')
parts.append(','.join(java(k.arg)+','+stateExpression(k.value) for k in stateCall.keywords))
parts.append('); }\n}\n')
(root / "src/Java_Cpp/io/github/yaulun/javacpp/compat/python/PythonLlamaDefaults.java").write_text("".join(parts), encoding="utf-8")
print("Generated verified Llama keyword/default tables")
