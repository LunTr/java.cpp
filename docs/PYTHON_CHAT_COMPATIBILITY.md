# 静态聊天函数逐项移植

日期：2026-10-23。固定基线：llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。

后续进度：新增 12 项状态/缓存/处理器函数，总清单勾选 118 项，最新完整测试 11,452 项通过。见 [状态/缓存记录](PYTHON_STATE_COMPATIBILITY.md)。本页保留聊天批次的数量快照；全部测试数据统一采用 [本地生成规则](../tools/python_compat/README.md)。

## 数量复核

重新读取本地缓存的 25 个原版 Python 文件，逐个验证审计清单中的 Git blob SHA-1 和 SHA-256，再使用 AST 枚举定义。结果仍为 786 个 def/async def、751 个去重函数项。机器清单和 README 逐项清单同步更新。

| 状态 | 修改前 | 本批完成后 |
|---|---:|---:|
| 同名 JNA 绑定 | 55 | 55 |
| 独立能力实现 | 14 | 51 |
| 清单打勾合计 | 69 | 106 |
| 部分覆盖 | 63 | 60 |
| 尚无对应实现 | 619 | 585 |
| 尚未打勾（部分 + 尚无实现） | 682 | 645 |
| 全输入一致性认证 | 0 | 0 |

本批包含 34 项原先尚无实现的函数，以及 3 项原先部分覆盖的函数。所有 751 项继续保留 `pythonExactParity=not-certified`。打勾记录实现与对照证据。

## Java 入口

`compat.python.PythonChatFormats` 提供原版 23 个静态格式化函数及按注册名称分发的 `format(name, messages)`：

llama-2、llama-3、alpaca、qwen、vicuna、oasst_llama、baichuan-2、baichuan、openbuddy、redpajama-incite、snoozy、phind、intel、open-orca、mistrallite、zephyr、pygmalion、chatml、mistral-instruct、chatglm3、openchat、saiga、gemma。

该类还提供 `_get_system_message`、`_map_roles`、7 个 `_format_*` 辅助函数以及 GGUF 模板识别函数的对应实现。每个对应入口均在 [完整审计清单](python-api-audit.json) 标出。

```java
var messages = List.of(
    Map.of("role", "system", "content", "请用中文回答"),
    Map.of("role", "user", "content", "你好🙂"));
PythonChatFormatterResponse formatted = PythonChatFormats.format("chatml", messages);
String prompt = formatted.prompt();
Object stop = formatted.stop();
```

输入用 List 和 Map 保留原版消息结构。需要显式空值时，可使用允许 null 的 ArrayList 和 LinkedHashMap。返回对象提供 prompt、stop、stoppingCriteria 和 addedSpecial；本批静态 formatter 的后两项分别为 null、false。

实现保留原版输出细节：空 system 模板、首个 system 优先级、空字符串与 null 的分支、非字符串内容的角色映射、Open Orca 的大写角色、Saiga 的 bot 角色、Intel 的双冒号及字面量 `{system_message}`、Qwen 的 `<|endoftext|>` stop。Gemma 的原版 debug 消息可通过 `formatGemma(messages, debugLog)` 接收。

`compat.python.PythonChatCompletions` 提供 4 个 completion 转换函数：

| Python 函数 | Java 方法 |
|---|---|
| `_convert_text_completion_logprobs_to_chat` | `convertTextCompletionLogprobsToChat` |
| `_convert_text_completion_to_chat` | `convertTextCompletionToChat` |
| `_convert_text_completion_chunks_to_chat` | `convertTextCompletionChunksToChat` |
| `_convert_completion_to_chat` | `convertCompletionToChat` |

转换保留字段插入顺序、null、zip 截断到最短输入、只读取首个 choice、`chat` ID 前缀、usage 引用和原版异常分支。字典及数组返回值可修改。流式入口返回惰性 Iterator：首个 role 块先于 choices 访问，随后产出正文块，结束块 delta 为空字典；发生 PythonException 后关闭转换迭代器。

错误桥接新增 KeyError、TypeError、IndexError、AttributeError 类别；原有错误桥接测试继续通过。

## 原版对照与复现

生成器逐句执行固定原版完整函数体，仅移除注册装饰器，使用原版 ChatFormatterResponse 定义，以回调记录原版 logger.debug。该批函数的执行无需原生模型，也未用 Java 实现生成预期结果。

10,376 条 golden 样例比较返回值、字段顺序、错误类别及消息、debug 消息，以及发生错误前已经产出的流式块。包括空历史、多轮历史、多个 system、未知角色、空消息、中文和 emoji、NUL、控制字符、Unicode 空白、未配对代理字符、非字符串内容、缺少字段、空 choices、不同长度的 logprobs 和结束块。补充 CPython repr 对照覆盖 Unicode 可打印区间的全部边界、524 个浮点样例及其 system 内容格式化；Java 使用从 CPython 生成的 Unicode 15.1.0 区间和最短往返浮点格式规则。

测试资源采用三列 TSV：操作名、Base64 输入、Base64 原版结果。Base64 内为带类型标签的二进制值；字符串使用 UTF-16 code unit，能够逐字比较 Java 字符串中的未配对代理字符。整数、浮点、布尔、null、列表和字典分别编码，字典保持插入顺序。来源与样例摘要见 [契约生成记录](python-chat-contracts.json)。

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_chat_contracts.py --source-root .tools/python-api-audit
& E:/soft/Anaconda/python.exe tools/python_compat/update_inventory.py
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify -Pnative "-Djavacpp.natives=E:\JavaKotlin\java.cpp\natives\b11669-cpu" "-Djavacpp.model=E:\JavaKotlin\java.cpp\gguf\Qwen3.5-0.8B-Q8_0.gguf"
```

生成器的 `--source-root` 指向包含 `llama_cpp/` 原版源码的目录；各文件必须与审计清单摘要一致。生成脚本已纳入版本控制。大型聊天 TSV 按 `.gitignore` 规则作为本地生成文件保存；清洁检出后先按上述命令生成 TSV，再运行本批测试。

本次完整验证：**10,713 项通过，0 失败，0 错误，0 跳过**。包含新增 10,376 条原版/CPython 对照、2 项 Java 惰性读取/可变引用测试、原有 335 项测试；其中 11 项真实模型测试执行成功。Google Java Format 检查通过，类库和 demo JAR 已重新生成。

## 后续一致性验收

本批验证覆盖静态 formatter 和 completion 数据转换，尚需继续检查 Java/Python 输入类型对应的完整边界。Java 输入采用 List、Map 和 String，Python 可接收自定义对象及迭代协议；自定义对象行为、完整数值域及 Python traceback 均保留待验收。Python 中显式的高低代理字符对与单个补充平面字符可具有不同 repr，Java String 使用相同 UTF-16 表示，这项输入区分也需要专门适配。Gemma 的 debug 来源和输出设备由调用方接入。

完整 Python 原生推理对照仍待完成：Python vendor commit 为 `0c1e57098bba43ac29e6e3b677cdceebdd22334f`，Java 锁定 b11669 / `620d2225cc436ab0b001e3200e590e3b286ef9ab`。本批静态函数直接执行原版源码核验；真实模型回归检查现有 Java 功能。Jinja2、自定义 chat handler、tools/function calling、完整生成流程和多模态函数继续按清单推进。
