# llama_chat_format.py 处理器/辅助函数移植对照记录

生成命令：python3 tools/python_compat/generate_chathandlers_contracts.py --source-root .tools/python-api-audit。
固定原版 llama-cpp-python 0.3.36（commit 1652066e），源文件 SHA-256/SHA-1 均按 docs/python-api-audit.json 校验后执行函数体。

## 覆盖范围（31 项）

| 组 | 函数 | Java |
|---|---|---|
| grammar | _grammar_for_json/_schema/_response_format | PythonChatFormatHandlers.grammarFor* |
| HF tokenizer | hf_autotokenizer_to_chat_formatter + format_autotokenizer + handler | hfAutotokenizerToChat* |
| tokenizer_config | hf_tokenizer_config_to_chat_formatter + format_tokenizer_config + handler | hfTokenizerConfigToChat* |
| 转换 | _convert_completion_to_chat_function + _stream_response_to_function_stream | convertCompletionToChatFunction / streamResponseToFunctionStream |
| functionary v1 | generate_type_definition / shared_definitions / schema_from_functions、prepare_messages_for_inference + message_to_str | generateTypeDefinition / generateSharedDefinitions / generateSchemaFromFunctionsV1 / prepareMessagesForInferenceV1 |
| functionary v1v2 | 同名四个 + get_grammar | generateSchemaFromFunctionsV2 / prepareMessagesForInferenceV2 / functionaryGetGrammar |
| 媒体 | Llava15/MTMD get_image_urls、split_text_on_image_urls + find_first、_get_template_messages、_convert_message/_content_part_for_template、_decode_token_piece、_postprocess_template_text | 同名静态方法 |
| Qwen | Qwen25VLChatHandler.__call__ 前置段 | qwen25vlCall |

## 端口

- GrammarPort：from_string（无 kwargs / 带 verbose 两种形态）、from_json_schema、json_schema_to_gbnf、JSON_GBNF 常量、print、suppress_stdout_stderr。
- TokenizerPort：from_pretrained、use_default_system_prompt、apply_chat_template(tokenize=False,**kwargs)、eos_token。
- JinjaPort：envFromString（构造期记 env_init + from_string）、render（调用期）。
- QwenLlama：reset / kvCacheClear / setNTokens / hasInputIds / inputIdsFill。

## 忠实要点

- split_text_on_image_urls 的 find_first 按 substrs 顺序而非最左位置命中（fixture 已验证 "x u2 y u1" 命中 u1）。
- v1 schema 两空格缩进加 } // namespace 尾换行；v1/v2 无缩进且无尾换行。Python format 串里双花括号转义为单括号，Java 侧曾写出双括号，已修正。
- get_grammar：json_schema_to_gbnf 调两次（grammar_text 与 from_string 实参），print(grammar_text) 进 stdout 记录；from_string(x) 不带 kwargs 与 from_string(JSON_GBNF, verbose=v) 两种 trace 形态分别回放。
- transformers import 在生成器里注入 sys.modules；本机无 transformers，靠桩执行原版函数体。

## 未覆盖 / 后续

- Jinja2ChatFormatter、两个 handler 的完整 __call__ 流（mtmd/真实 tokenizer）、chatml_function_calling、functionary 外层 handler、from_pretrained、load_image 等仍为 missing。
- HF/jinja 真实实现经端口注入，本契约只认证控制流与数据形态。
