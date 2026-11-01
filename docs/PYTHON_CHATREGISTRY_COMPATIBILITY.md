# chat_format 注册表/转换器、app 端点、ext 装饰器迁移记录

日期：2026-11-01。固定 Python 基线：llama-cpp-python 0.3.36，commit `1652066e0af45f2313b339670ef9555e8a54e545`。

## 覆盖范围（21 项）

| Python 项 | Java 对应 | 说明 |
|---|---|---|
| ext._ctypes_function_from_names ×2 | PythonCtypesExt | 逐名 getattr 缺失收集、命中赋值 argtypes/restype、functools.wraps（复用 CtypesExtensions）、全缺 AttributeError 原文 |
| LlamaChatCompletionHandler/ChatFormatter.__call__ | PythonChatFormat | Protocol 省略号方法体返回 None |
| Registry ×3 + 模块级 4 项 | PythonChatHandlerRegistry | 类属性共享 handler 表（挂 PythonSingleton）、overwrite/未注册 ValueError 原文、NotFound 自定义异常与合法名单 repr、两个装饰器工厂 |
| Jinja2ChatFormatter.strftime_now/tojson | PythonChatFormat + PythonJson | 时钟端口；新增 PythonJson 复刻 dumps 参数组、\uXXXX 转义、sort/indent/separators、float repr |
| chat_formatter_to_chat_completion_handler ×2 | PythonChatFormat | 嵌套 handler 完整移植：stop 合并、response_format grammar、functions→tools/function_call→tool_choice、tool 查找 ValueError、grammar 失败回退 JSON_GBNF、create_completion 23 个 kwargs、两个 convert 出口 |
| app.authenticate/tokenize/detokenize/count/get_models ×5 | PythonServerApp | api_key 放行/401、utf-8 编解码、tokens/count/text/固定字段模型列表 |

## 实现说明

- `PythonErrorType` 新增 `LLAMA_CHAT_HANDLER_NOT_FOUND`、`HTTP_EXCEPTION`、`JSON_DECODE_ERROR`（前轮）以保异常名一致。
- `Union[None]` 坍缩、Java 数值三元装箱提升陷阱等问题在本轮 fixture 对齐中发现并已修正。
- `function_call="auto"` 会覆盖传入的 tool_choice，嵌套 handler 严格按原版分支顺序。

## 验证

`generate_chatregistry_contracts.py` 执行固定原版函数体生成 **14 组**对照；`PythonChatContractTest` 回放逐字节比对全部通过。数据：`src/test/resources/python-0.3.36-chatregistry-contracts.tsv`（gitignore），元数据 `docs/python-chatregistry-contracts.json`（21 函数、14 例、SHA-256）。

全输入一致性认证保持 0 项。
