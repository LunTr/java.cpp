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
