# _utils / tokenizer 基类与 HF / speculative / server.errors 迁移记录

日期：2026-11-01。固定 Python 基线：llama-cpp-python 0.3.36，commit `1652066e0af45f2313b339670ef9555e8a54e545`。

## 覆盖范围（20 项）

| Python 项 | Java 对应 | 说明 |
|---|---|---|
| _utils.suppress_stdout_stderr ×3 | PythonSuppressStdoutStderr | disable 早退；dup/dup2/close 顺序、sys 流替换、ipykernel `_original_stdstream_copy` 暂存/改指/恢复 |
| _utils.MetaSingleton.__call__ | PythonSingleton.call | 按类注册的共享实例表；构造异常传播且不写入注册表 |
| _utils.Singleton.__init__ | PythonSingleton | 仅链 super() 的空构造 |
| BaseLlamaTokenizer ×2 | PythonBaseLlamaTokenizer | 基类方法体抛 NotImplementedError；ABC 实例化 TypeError 名单按原版排序（共享 `PythonAbstract`） |
| LlamaHFTokenizer ×4 | PythonHfTokenizer | errors=ignore 解码→encode(special)；detokenize 的 prev_tokens 前缀差分与 skip 取反；from_pretrained 工厂注入 transformers，失败原文保留 |
| LlamaDraftModel.__call__ | PythonDraftModel.call | ABC + NIE |
| LlamaPromptLookupDecoding ×3 | PythonPromptLookupDecoding | 降序 ngram 滑窗匹配、首个续写截取、空 intc 数组兜底 |
| server/errors 2 formatters | PythonServerErrors | chat/completion 消息模板、max_tokens None→0、`{}` 占位替换、None 渲染 |
| RouteErrorHandler ×3 | RouteErrorHandler | pattern dict 顺序、stderr 四段写入（print 两段 + traceback 两段）、HTTPException 穿透、json() 异常传播、body 重解析兜底、计时头 |

## 实现说明

- 新增 `PythonAbstract`：把上一轮 `PythonLlamaCache` 内嵌的 ABCMeta 检查抽成共享工具（按声明类反射判断未实现方法、Python 名排序、单复数形式），本轮三个抽象基类共用。
- `errorMessageWrapper` 的 `str(error)` 取异常消息（Python str(exc)），非 Java `toString`；stderr 按 print/traceback 真实写入分四段。
- `customRouteHandler`：`request.json()` 自身异常向外传播；仅“payload→Body 构造”包在 try 内失败置 None，与原版一致。

## 验证

`generate_helpers_contracts.py` 以 AST 执行固定原版函数体（生成环境无 numpy，speculative 用等价桩数组实现），生成 **31 组**对照；`PythonHelpersContractTest` 回放逐字节比对全部通过。数据：`src/test/resources/python-0.3.36-helpers-contracts.tsv`（gitignore），元数据 `docs/python-helpers-contracts.json`（20 个函数、31 例、SHA-256）。

全输入一致性认证保持 0 项；真实 fd 重定向、pydantic 请求模型、transformers 与 FastAPI 行为由端口承担，继续验收。
