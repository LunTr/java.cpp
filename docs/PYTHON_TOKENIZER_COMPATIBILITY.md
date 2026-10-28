# Llama 分词链路迁移（第二轮）

日期：2026-10-28。固定来源为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。本轮选择 8 项原有部分覆盖函数，保留原版委托和文本编码规则。

| Python 定义 | Java 对应 |
|---|---|
| `Llama.tokenize` | `PythonLlama.tokenize` |
| `Llama.detokenize` | `PythonLlama.detokenize` |
| `Llama.tokenizer` | `PythonLlama.tokenizer` |
| `LlamaTokenizer.__init__` | `PythonLlamaTokenizer` 构造器 |
| `LlamaTokenizer.tokenize` | `PythonLlamaTokenizer.tokenize` |
| `LlamaTokenizer.detokenize` | `PythonLlamaTokenizer.detokenize` |
| `LlamaTokenizer.encode` | `PythonLlamaTokenizer.encode` |
| `LlamaTokenizer.decode` | `PythonLlamaTokenizer.decode` |

## 使用

```java
state.setTokenizerModel(PythonLlamaTokenizer.modelFromVocab(model.vocab()));
int[] ids = state.tokenize(promptBytes); // add_bos=true, special=false
byte[] text = state.detokenize(ids);
PythonLlamaTokenizer tokenizer = state.tokenizer();
int[] encoded = tokenizer.encode(prompt); // add_bos=true, special=true
String decoded = tokenizer.decode(encoded);
```

state 为上一轮的 PythonLlama 状态组件，其余求值状态由调用方管理。单独使用可通过 `PythonLlamaTokenizer.fromVocab(model.vocab())` 创建包装器。模型由调用方关闭；关闭后的分词调用沿用 Java 生命周期检查。

PythonTokenizerModel 提供模型级 bytes 编解码；PythonTokenizer 支持高层自定义 tokenizer 和 previousTokens。setTokenizer 可替换高层分词器。tokenizer() 每次创建新的模型包装器，使用当前模型 codec；已有包装器保留创建时的模型引用。原版 LlamaTokenizer 接收 prev_tokens 后只向模型传 tokens/special，高层 Llama 则完整转发给其当前分词器。

字符串 encode/decode 均采用 UTF-8 errors=ignore；覆盖 NUL、中文、补充平面字符、孤立代理字符及无效/截断 UTF-8。Java String 的有效代理对按补充平面字符处理；Python 显式相邻代理字符构成的特殊字符串表示仍按全输入标准继续核对。

## Vocab 字节适配

模型分词通过现有 PythonErrorBridge.tokenize 接入，支持原始字节及特殊 token 开关。新增 Vocab.pythonDetokenizeBytes 在模型生命周期锁内逐 piece 读取，复用 32 字节缓冲、保留原版负长度切片规则，并在首 token 为 BOS 且输出首字节为空格时移除该空格。现有 Java 动态扩容反分词 API 继续供原调用方使用。

适配器使用当前 b11669 原生库和有效词表 token。原版内部包装的缓冲分配和完整错误路径、无效 token 的行为及 Python vendor 原生 revision 的全模型分词结果继续逐项对照。本轮计数为上述 8 项高层定义，Vocab 辅助方法单独记为实现支撑。

## 对照数据与验证

Anaconda CPython 3.13.9 执行固定原版 8 项完整函数体；执行前校验全部 25 个源码文件的 SHA-256 和 Git blob SHA-1。模型 codec 与自定义 tokenizer 使用显式轨迹替身，324 组比较两层默认参数、显式开关、UTF-8 编解码、previousTokens 转发、返回引用、新包装器和旧模型引用。

生成脚本为 `tools/python_compat/generate_tokenizer_contracts.py`。数据位于被忽略的 `src/test/resources/python-0.3.36-tokenizer-contracts.tsv`；元数据见 [python-tokenizer-contracts.json](python-tokenizer-contracts.json)。真实模型集成测试读取 Python 原版产生的 UTF-8 字节，执行 b11669 模型分词/反分词往返，并核对关闭后的调用及 facade 接入。

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_tokenizer_contracts.py --source-root .tools/python-api-audit
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify -Pnative "-Djavacpp.natives=E:\JavaKotlin\java.cpp\natives\b11669-cpu" "-Djavacpp.model=E:\JavaKotlin\java.cpp\gguf\Qwen3.5-0.8B-Q8_0.gguf"
```

本轮后累计 134 / 751（17.84%）勾选，50 项部分覆盖，567 项待迁移。全输入一致性继续按逐项标准验收。

完整 `verify -Pnative`：**12,152 项通过，0 失败、0 错误、0 跳过**。包含本轮 324 项原版契约及 1 项新增真实模型集成测试，共 12 项真实模型测试。Google Java Format 和公开 API 边界检查通过，类库及 demo JAR 已重新打包。
