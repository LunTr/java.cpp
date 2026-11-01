# Python llama_grammar.py 兼容性（第十轮 · 29 项）

固定基线：llama-cpp-python 0.3.36（commit 1652066e）。对照由
tools/python_compat/generate_grammar_contracts.py 原样执行固定原版模块级与函数体生成，
纯输入/输出等价（GBNF 文本、异常类型与消息逐字节比对），无原生依赖。

## 覆盖

- LlamaGrammar：__init__ / from_string / from_file（open 端口 + 三类错误原文）/ from_json_schema
- _build_repetition + 嵌套 opt_repetitions：全部 min/max/sep/literal 组合
- BuiltinRule.__init__ 与 PRIMITIVE/STRING_FORMAT/RESERVED 规则表
- SchemaConverter 全量：__init__、_format_literal、not_literal+recurse、_add_rule 去重编号、
  resolve_refs+visit（#/、https://+Fetcher、错误原文）、_generate_union_rule、
  _visit_pattern+to_rule/transform/get_dot/join_seq（GBNF 正则转换全分支：括号、字符集、
  |、量词 {x}/{x,}/{x,y} 子规则提取、literal 合并）、_resolve_ref、_generate_constant_rule、
  visit 全派发（/oneOf/anyOf/type list/const/enum/object/allOf/array/pattern/uuid/format/
  minLength-maxLength/object/基元）、_add_primitive、_build_object_rule+get_recursive_refs、
  format_grammar（名称排序输出）、json_schema_to_gbnf

## 细节偏差修正

- dict 断言消息用 Python repr（单引号键）而非 Java Map.toString
- str(err) 用 getMessage 而非 Java toString
- seq[-1] 越界报 IndexError "list index out of range"
- STRING_FORMAT_RULES 三字符串规则首字符为引号转义对

## 验证

PythonGrammarContractTest 16/16 全过。清单：covered 382 / missing 248 / checked 497；
llama_grammar.py 未迁移清零。
