# server/cli + settings + app + model 迁移记录

日期：2026-11-01。固定 Python 基线：llama-cpp-python 0.3.36，commit `1652066e0af45f2313b339670ef9555e8a54e545`。

## 覆盖范围（20 项）

| Python 项 | Java 对应 | 说明 |
|---|---|---|
| cli._get_base_type | PythonServerCli.getBaseType | Literal 首参类型、Union 滤 NoneType 首项递归、list 参数递归；注解经注入端口表达 |
| cli._contains_list/dict_type | containsListType / containsDictType | origin 命中 + Literal/Union 任一参数递归 |
| cli._parse_bool_arg | parseBoolArg | bytes→utf-8、lower().strip() 集合判定、ValueError 原文 |
| cli._parse_json_object_arg | parseJsonObjectArg | bytes→utf-8、内置最小 JSON 解析器、非 dict 的 ValueError 原文；解析失败为 JSONDecodeError（新枚举） |
| cli.add_args_from_model | addArgsFromModel | description 追加 `(default: x)` 仅在非必填且有默认值时；dict→json_arg、bool→bool_arg、list→nargs="*" |
| cli.parse_model_from_args | parseModelFromArgs | 仅非 None 且属于 model_fields 的项进 kwargs |
| settings.set_dynamic_defaults | PythonServerSettings.setDynamicDefaults | -1→cpu_count 替换并原地写回 |
| app.set/get_server_settings | ModuleState + set/getServerSettings | 全局赋值；get 为单次 yield 生成器 |
| app.set_llama_proxy | setLlamaProxy | LlamaProxy(models=...) 经工厂端口 |
| app.set_ping_message_factory | setPingMessageFactory | 全局赋值 |
| app.get_llama_proxy | getLlamaProxy | 双锁顺序：outer acq→inner acq→outer rel→inner rel，releaseOuterLock 兜底 |
| app._logit_bias_tokens_to_input_ids | logitBiasTokensToInputIds | utf-8 编码→tokenize(add_bos=False,special=True)→str(input_id):score |
| model.LlamaProxy ×6 | PythonLlamaProxy | 空列表 AssertionError 原文；alias 回填 model；未知模型回落默认；切换关闭旧模型；get 引号 KeyError；set 走 validate_json 端口；iter 按插入序；free 关闭并置空 |

## 实现说明

- `Union[None]` 等单参 Union 在 Python 中坍缩为 NoneType，注解端口按坍缩后形态回放。
- Java 数值三元的装箱提升陷阱（`Long:Double` → `double`）在 JSON 数字分支用 if/else 规避。
- `request.json()`/锁/FastAPI 响应层全部端口化；锁顺序与 `add_argument` 序列在 fixture 中逐条记录。

## 验证

`generate_server_contracts.py` 执行固定原版函数体生成 **47 组**对照；`PythonServerContractTest` 回放逐字节比对全部通过。数据：`src/test/resources/python-0.3.36-server-contracts.tsv`（gitignore），元数据 `docs/python-server-contracts.json`（20 函数、47 例、SHA-256）。

全输入一致性认证保持 0 项。

## app.py 端点批（再 6 项，2026-11-02）

| Python 项 | Java 对应 | 说明 |
|---|---|---|
| app.create_app | PythonServerApp.createApp | CONFIG_FILE 环境变量→存在性检查→JSON 直读 / yaml.safe_load+json.dumps 两分支、ConfigFileSettings/ServerSettings/ModelSettings.validate 序列、双断言原文（含 "must be provided together"）、FastAPI(middleware/title/version/root_path) 与 add_middleware(CORS)/include_router 调用顺序、disable_ping_events→空 bytes 工厂 |
| app.prepare_request_resources | PythonServerApp.prepareRequestResources | proxy None→HTTPException(503, "Service is not available")、logit_bias_type=="tokens" 走 _logit_bias_tokens_to_input_ids、grammar→LlamaGrammar.from_string 端口、min_tokens>0 构造 MinTokensLogitsProcessor(min_tokens, llama.token_eos()) 包 LogitsProcessorList 并写入/extend kwargs["logits_processor"] |
| app.get_event_publisher | PythonServerApp.getEventPublisher | next(get_server_settings())→interrupt_requests 真值判定、双锁 async with、inner_send_chan enter/send/close、逐块 dict(data=json.dumps(chunk))、is_disconnected→CancelledError、interrupt+outer.locked()→send [DONE]+CancelledError、except 分支双 print+move_on_after(1,shield)+raise e、正常末尾 send [DONE] |
| app.create_completion | PythonServerApp.createCompletion | prompt list 归一化（assert len≤1 空消息、空列表→""、单元素→元素）、copilot-codex 路径别名、exclude={n,best_of,logit_bias_type,user,min_tokens}、stream→create_memory_object_stream(10)+partial(get_event_publisher,六 kwargs)+EventSourceResponse(sep="\n",ping=_ping_message_factory)、非流式→双锁上下文+prepare+is_disconnected→print+HTTPException(400,"Client closed request")+run_in_threadpool(llama,**kwargs) |
| app.create_embedding | PythonServerApp.createEmbedding | run_in_threadpool(llama_proxy(request.model).create_embedding, **model_dump(exclude={"user"}))；proxy None→TypeError 'NoneType' object is not callable |
| app.create_chat_completion | PythonServerApp.createChatCompletion | exclude={n,logit_bias_type,user,min_tokens}（无 best_of）、stream 分支同上但 llama_call=Llama.create_chat_completion、非流式 run_in_threadpool(llama.create_chat_completion,**kwargs) |

## 实现说明（第二批）

- 装饰器（@router.post/@router.get 及 Body(openapi_examples=...)）按 FastAPI 注册语义剥离，逐句移植仅限函数体；Depends 默认值在桩侧记录不执行。
- `get_llama_proxy` 重构为 enter/close 两段：`enterLlamaProxy` 重放 outer acquire→inner acquire→outer release，调用方 finally `inner.release()`；独立生成器语义保持不变（既有 2 组锁序对照仍通过）。
- 新增端口：Request(url.path/is_disconnected/client)、SendChannel(enter/send/close)、ResourceBody/CompletionBody/ChatBody/EmbeddingBody(model_dump exclude 集合)、ResourceLlama(tokenize+token_eos)、EndpointDeps(threadpool/iterate/memory stream/EventSourceResponse/bound-method token/print/move_on_after/interrupt_requests)、CreateAppDeps(os.environ/path/open/yaml/三个 validate/Settings()/FastAPI/Middleware/CORS/router/root_path/disable_ping_events/LlamaProxy 工厂)。
- 新增异常：HttpException(status_code+detail)、CancelledError(anyio cancel 类标记，except 捕获后原样 re-raise)。
- json.dumps 走已验证的 PythonJson（ensure_ascii/分隔符/浮点 repr）；functools.partial 以 BoundCall(function+keywords) 表示，kwargs 按原版顺序记录。
- FastAPI 版本号取自 PythonErrorBridge.PYTHON_VERSION=0.3.36。

## 验证（累计）

`generate_server_contracts.py` 新增 34 组用例（create_app 8、prepare 7、publisher 6、completion 7、chat 4、embedding 2），总计 **81 组**；`PythonServerContractTest` 回放逐字节比对全部通过（JUnit 实测 81 successful / 0 failed）。fixture 仍为 `src/test/resources/python-0.3.36-server-contracts.tsv`（gitignore），元数据 `docs/python-server-contracts.json`（26 函数、81 例、SHA-256）。

全输入一致性认证保持 0 项。
