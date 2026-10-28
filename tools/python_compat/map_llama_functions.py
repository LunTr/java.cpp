"""Generate the complete pinned llama.py function/error/dependency mapping, preserving gaps."""
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
source = args.source_root / "llama_cpp/llama.py"
data = source.read_bytes()
entry = next(row for row in audit["baseline"]["files"] if row["path"] == "llama_cpp/llama.py")
assert hashlib.sha256(data).hexdigest() == entry["sha256"]
assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == entry["sha"]
tree = ast.parse(data)

targets = {
    "Llama.__init__": "PythonErrorBridge.loadModel/createContext + ModelParams/ContextParams（参数全集待连接）",
    "Llama.__init__.free_lora_adapter": "计划：LoRA AutoCloseable 资源回调",
    "Llama.ctx": "PythonLlama.ctx（Java 上下文对象）",
    "Llama.model": "PythonLlama.model（Java 模型对象）",
    "Llama.eval": "PythonLlama.eval / PythonLlamaExecution",
    "Llama._init_sampler": "计划：完整 Python 采样链构造策略",
    "Llama._init_sampler.apply_func": "计划：采样候选数组与 logits processor 的共享内存适配",
    "Llama.sample": "PythonLlama.sample（采样器工厂注入，完整参数链待连接）",
    "Llama.generate": "PythonLlama.generate / TokenGenerator.send（状态机已接入，采样策略参数待连接）",
    "Llama.create_embedding": "计划：embedding 结果对象与 usage 组装",
    "Llama.embed": "计划：多序列 embedding 批处理与归一化",
    "Llama.embed.decode_batch": "计划：embedding 解码、池化与输出拷贝",
    "Llama._create_completion": "计划：completion 生成、stop/echo/logprobs、缓存和流式 UTF-8",
    "Llama._create_completion.logit_bias_processor": "计划：logit bias 拷贝、索引与列表扩展语义",
    "Llama.create_completion": "现有 LlamaContext.generate；计划：完整 completion 参数转发和响应",
    "Llama.__call__": "计划：createCompletion 参数别名",
    "Llama.create_chat_completion": "现有 ChatSession / PythonChatFormats；计划：chat handler 优先级和参数转发",
    "Llama.create_chat_completion_openai_v1": "计划：Java OpenAI 类型 provider，依赖与转换异常策略",
    "Llama.__getstate__": "计划：Python 构造配置序列化（区别于推理快照）",
    "Llama.__setstate__": "计划：通过完整构造器重建配置",
    "Llama.save_state": "PythonLlama.saveState / PythonLlamaExecution.copyState",
    "Llama.load_state": "PythonLlama.loadState / PythonLlamaExecution.restoreState",
    "Llama.n_ctx": "PythonLlama.nCtx",
    "Llama.n_embd": "PythonLlama.nEmbd",
    "Llama.n_vocab": "PythonLlama.nVocab",
    "Llama.token_eos": "PythonLlama.tokenEos",
    "Llama.token_bos": "PythonLlama.tokenBos",
    "Llama.token_nl": "PythonLlama.tokenNl",
    "Llama.pooling_type": "PythonLlama.poolingType（按实际返回 int）",
    "Llama.close": "PythonLlama.close / setCloseAction",
    "Llama.__del__": "AutoCloseable.close；Java 自动清理策略待实现",
    "Llama.from_pretrained": "现有 ModelDownloader；计划：repo 校验、文件匹配与 Hugging Face provider",
}
notes = {
    "Llama.__init__": "按源码顺序处理设备数、kv_overrides、路径、原生资源和 LoRA；参数默认值与 Java 默认值逐项跟踪。",
    "Llama.eval": "先移除后缀，再逐批 decode、写 input_ids、写扁平 scores，最后推进 n_tokens；空输入仍调用后缀移除。",
    "Llama.sample": "先检查 n_tokens；临时采样器构造先于 ctx 断言；idx 转相对位置；成功后清除临时引用，异常保留。",
    "Llama.generate": "惰性启动；缓存前缀/恢复后重放；yield 前停止检查；send 后追加 token；draft 验证及异常导致生成器结束。",
    "Llama._init_sampler": "custom、penalties、grammar、温度和 mirostat 分支按顺序；tfs_z/penalize_nl 在该函数体中未参与原生构造。",
    "Llama._create_completion": "校验 suffix、准备 prompt、重复 BOS 警告、bias、上下文上限、stop 编码、logprobs 限制、缓存/生成；异常发生在首次迭代时。",
    "Llama._create_completion.logit_bias_processor": "复制 scores 再逐键赋值；索引错误和 extend 返回 None 的源码行为均需保留证据。",
    "Llama.embed": "先查询维度/池化，再检查 embeddings；按容量和序列数批处理；仅在成功路径清空 KV 并 reset。",
    "Llama.save_state": "按原版顺序输出诊断；实际拷贝数可小于分配量；大于分配量抛错；仅复制有效 scores，复制全部 input_ids。",
    "Llama.load_state": "先广播 scores 并清除尾部正数，替换 input_ids/游标/种子，再检查 byte buffer 并恢复原生状态；失败保留已完成修改。",
    "Llama.__getstate__": "返回构造配置，包含当前 seed；保留可调用对象引用；不含推理 KV 字节。",
    "Llama.__setstate__": "直接调用 __init__(**state)，重跑校验和资源初始化。",
    "Llama.create_chat_completion": "优先显式 chat_handler，再查模型内 handler，最后全局 registry；handler 错误原样传播。",
    "Llama.create_chat_completion_openai_v1": "import 在 stream bool 检查之前；流式对象转换惰性执行；捕获范围包含同步 create_chat_completion 的 ImportError。",
    "Llama.from_pretrained": "先导入依赖并校验 repo，再匹配唯一文件；主文件先下载，随后校验/下载附加文件；无 local_dir 时再次取得缓存路径。",
    "Llama.close": "ExitStack 消耗清理回调；重复 close 有效，回调失败后的重试也保留已消耗状态。",
    "Llama.__del__": "调用 close；Python 析构期间异常报告由 Python 运行时承担。",
    "Llama.pooling_type": "源码注解是 str，但函数直接返回 _ctx.pooling_type() 的整数；Java 保留整数值。",
}

class DirectVisitor(ast.NodeVisitor):
    def __init__(self): self.events, self.calls, self.guards = [], set(), []
    def visit_FunctionDef(self, node): pass
    def visit_ClassDef(self, node): pass
    def visit_Raise(self, node):
        self.events.append({"kind": "raise", "line": node.lineno, "expression": ast.unparse(node.exc) if node.exc else "reraise", "guards": list(self.guards)})
        self.generic_visit(node)
    def visit_Assert(self, node):
        self.events.append({"kind": "assert", "line": node.lineno, "condition": ast.unparse(node.test), "message": ast.unparse(node.msg) if node.msg else "", "guards": list(self.guards)})
        self.generic_visit(node)
    def visit_Call(self, node):
        self.calls.add(ast.unparse(node.func))
        if ast.unparse(node.func) == "warnings.warn":
            self.events.append({"kind": "warning", "line": node.lineno, "expression": ast.unparse(node), "guards": list(self.guards)})
        self.generic_visit(node)
    def visit_If(self, node):
        self.visit(node.test)
        self.guards.append(ast.unparse(node.test))
        for child in node.body: self.visit(child)
        self.guards.pop()
        self.guards.append("not (" + ast.unparse(node.test) + ")")
        for child in node.orelse: self.visit(child)
        self.guards.pop()
    def visit_ExceptHandler(self, node):
        self.guards.append("except " + (ast.unparse(node.type) if node.type else "BaseException"))
        for child in node.body: self.visit(child)
        self.guards.pop()

definitions = {}
def walk(nodes, prefix=""):
    for node in nodes:
        if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            name = prefix + node.name
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)): definitions[name] = node
            walk(node.body, name + ".")
        else:
            walk(ast.iter_child_nodes(node), prefix)
walk(tree.body)
inventory = [row for row in audit["functions"] if row["file"] == "llama_cpp/llama.py"]
assert set(definitions) == {row["qualname"] for row in inventory}
mapping = []
for row in inventory:
    name = row["qualname"]
    node = definitions[name]
    visitor = DirectVisitor()
    for statement in node.body: visitor.visit(statement)
    mapping.append({"function": name, "line": node.lineno, "endLine": node.end_lineno,
                    "signature": ast.unparse(node.args), "decorators": [ast.unparse(d) for d in node.decorator_list],
                    "javaTarget": targets.get(name, row["java"]), "implementationStatus": row["status"],
                    "contractEvidence": row.get("contractEvidence"), "source": row["source"],
                    "directErrorSites": sorted(visitor.events, key=lambda event: event["line"]),
                    "dependencies": sorted(visitor.calls),
                    "orderNotes": notes.get(name, "保留参数、返回形态及委托调用；回调、数组/编码、原生操作的隐式错误另按依赖核对。"),
                    "errorScope": "显式 raise/assert/warnings.warn 来自 AST；隐式错误需由执行对照和依赖契约验证。"})
    assert mapping[-1]["javaTarget"], name
payload = {"sourceCommit": audit["baseline"]["commit"], "sourceSha256": entry["sha256"],
           "functionCount": len(mapping), "nestedCount": sum(row["nested"] for row in inventory),
           "directErrorSiteCount": sum(len(row["directErrorSites"]) for row in mapping),
           "mappingMeaning": "All source definitions have a concrete existing or planned Java destination. implementationStatus preserves implemented/partial/missing distinctions; mapping alone does not change completion counts.",
           "functions": mapping}
allErrorSites = sum(isinstance(n, (ast.Raise, ast.Assert)) or (isinstance(n, ast.Call) and ast.unparse(n.func) == "warnings.warn") for n in ast.walk(tree))
assert allErrorSites == payload["directErrorSiteCount"]
(root / "docs/python-llama-mapping.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

parts = ["# llama.py 全文件函数与异常映射\n\n",
         "固定来源：llama-cpp-python 0.3.36 / `" + audit["baseline"]["commit"] + "`。\n\n",
         f"完整映射 {len(mapping)} 个定义项，包含 {payload['nestedCount']} 个嵌套函数、{payload['directErrorSiteCount']} 处显式异常/断言/警告站点。映射生成器校验源码摘要，并要求定义集合与全量审计清单完全相等。\n\n",
         "每项同时记录 Java 去向和实际实现状态。计划中的目标继续保留待实现状态，回调及 NumPy/ctypes 的隐式错误由对应测试和后续对照补充。\n\n",
         "| Python 定义 | Java 去向 | 实现状态 |\n|---|---|---|\n"]
labels = {"covered": "已有窄范围实现", "partial": "部分实现", "missing": "待实现", "bound": "绑定"}
for row in mapping: parts.append(f"| `{row['function']}` | {row['javaTarget']} | {labels[row['implementationStatus']]} |\n")
parts.append("\n## 调用顺序与显式报错\n\n")
for row in mapping:
    parts.append(f"### {row['function']}\n\n[固定源码第 {row['line']} 行]({row['source']})。{row['orderNotes']}\n\n")
    for event in row["directErrorSites"]:
        expression = event.get("expression", event.get("condition", ""))
        condition = "；触发分支：" + " → ".join(event["guards"]) if event["guards"] else ""
        parts.append(f"- L{event['line']} / {event['kind']}：`{expression.replace('`', chr(39))}`{condition}\n")
    if not row["directErrorSites"]: parts.append("显式异常站点：0；依赖调用及运行时隐式错误继续按该项契约核对。\n")
    parts.append("\n")
parts.append("## 复现\n\n```powershell\n& E:/soft/Anaconda/python.exe tools/python_compat/map_llama_functions.py --source-root .tools/python-api-audit\n```\n\n机器清单保留完整签名、装饰器、直接调用依赖、错误站点和实现证据，见 [python-llama-mapping.json](python-llama-mapping.json)。求值与状态实现记录见 [执行链路记录](PYTHON_EXECUTION_COMPATIBILITY.md)。\n")
(root / "docs/PYTHON_LLAMA_MAPPING.md").write_text("".join(parts), encoding="utf-8")
print(json.dumps({key: value for key, value in payload.items() if key != "functions"}, ensure_ascii=False, indent=2))
