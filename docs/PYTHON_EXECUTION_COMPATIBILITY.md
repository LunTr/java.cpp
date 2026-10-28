# llama.py 执行链路与全文件映射

日期：2026-10-28。固定基线为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`。

## 全文件映射

[函数与异常映射](PYTHON_LLAMA_MAPPING.md) 覆盖 llama.py 全部 49 个定义项，包括 4 个嵌套函数。生成器要求 AST 函数集合与审计清单完全相等，同时检查显式 raise/assert/warnings.warn 共 23 处，保存源表达式、触发分支、Python 类别、Java 目标和当前实现状态。完整签名、装饰器和调用依赖保存在 [机器映射](python-llama-mapping.json)。

本轮新增能力：eval、save_state、load_state、token_nl、pooling_type、close；原有五个查询入口统一增加 PythonLlama 门面。sample 和 generate 的状态机已有执行对照，完整采样参数策略继续由独立映射跟踪。完整构造、LoRA 清理、embedding、completion 的 stop/echo/logprobs/流式处理、chat handler、OpenAI 类型转换、配置重建和通用仓库下载均列出具体目标与原版错误要求。

## 执行与资源适配

```java
state.attachNative(model, context);
state.setSamplerFactory(() -> relativeIndex -> context.sample(sampler));
state.eval(promptTokens);
PythonLlamaState saved = state.saveState();
state.loadState(saved);
try (PythonLlama.TokenGenerator generator = state.generate(promptTokens)) {
  Integer first = generator.next();
  Integer next = generator.send(extraTokens);
}
```

state 为调用方提供 intc/single 缓冲的 PythonLlama。attachNative 连接模型、上下文、默认分词器和执行端口，资源仍由调用方释放。上例采样器支持最后输出索引 -1；草稿验证等路径会传其他相对输出索引，调用方需提供支持相应索引的采样实现。完整 _init_sampler 的策略顺序已列入映射，保留部分实现状态。

可使用 setCloseAction 安装调用方选择的资源清理动作。close 在执行动作前消耗关闭状态；动作抛错后再次关闭保持已消费状态，与已测 ExitStack 行为对应。Java AutoCloseable 生命周期和原版 __del__ 自动析构分开记录。

## 保留的顺序与错误

- eval 先调用后缀移除，再逐批 decode、写 input_ids、写 scores，全部完成后推进游标。logits_all 关闭时保留 scores。空输入仍移除后缀；零 batch 对齐 range 的 ValueError。单元素向空切片广播和 scores 的扁平广播采用原版规则及消息。
- sample 先检查 n_tokens，再按需创建临时采样器，然后检查上下文。idx 减去 n_tokens 形成相对索引。成功后移除临时引用；失败保留引用，后续调用可观察该状态。
- save_state 按原版顺序输出诊断，只保存有效 scores、全部 input_ids 和实际原生字节数。拷贝数超过分配量时提供 RuntimeError 和原文。快照深拷贝数组及字节；BigInteger 种子通过 seedValue 保存，原 long getter 在范围内保持兼容。
- load_state 先赋 scores，尾部仅将正数归零，保留负数及 NaN；再替换 input_ids、游标、待求值标记和种子；随后检查字节缓冲、恢复原生状态。广播、长度和恢复错误均保留之前完成的修改。
- generate 惰性启动，保留精确前缀命中、恢复后重放、局部移除失败回退、send、停止条件和草稿 token 验证的顺序。停止条件使用有效 scores 前缀内的索引。正常结束以 null 表示，异常后后续 next 返回 null；首次 send 非空数组对应 TypeError，并保留生成器可启动状态。

执行端口为 PythonLlamaExecution。原生 decode 错误通过 PythonErrorBridge 按返回码转换；未知 Java/用户回调异常保留原对象，继续使用既有异常策略。数组接口为矩形 single scores/intc IDs；Python 任意 dtype、对象协议、超过 Java 数组上限的状态以及完整 traceback 继续逐项验证。停止条件和 draft 的 int[] 输入采用快照；NumPy 视图写回协议列入 generate 的部分实现范围。

## 原版对照与原生验证

生成器执行经全部 25 文件摘要校验的原版完整 eval/sample/generate/save/load/close 函数体，原生执行和采样器用显式状态替身代替。Anaconda CPython 3.13.9 / NumPy 2.4.3 产生 206 组数据：eval 90、sample 24、save 12、load 19、generate 59、close 2。每组比较返回、错误类别与消息、调用轨迹、诊断、数组、游标、种子及采样器保留状态。

新增原生集成测试使用既有忽略数据，验证实际 b11669 模型求值、有效 logits、状态保存、继续求值、恢复、reset 后继续生成和原生查询。Python vendor revision 与 Java b11669 的全推理结果一致性仍由独立进度跟踪。

新增 C 绑定为 llama_memory_seq_rm、llama_get_logits_ith、llama_state_get_size/get_data/set_data、llama_pooling_type、llama_vocab_nl。签名按固定 commit `620d2225cc436ab0b001e3200e590e3b286ef9ab` 的 include/llama.h 核对，头文件 SHA-256 为 `961430f89e2553dbcd99e3e6c59826af9ec143f05911520c74f804d986ff3cd2`。Windows x64 的 size_t 使用 long，原始指针保留在 internal/资源封装内。

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_execution_contracts.py --source-root .tools/python-api-audit
& E:/soft/Anaconda/python.exe tools/python_compat/update_inventory.py
& E:/soft/Anaconda/python.exe tools/python_compat/map_llama_functions.py --source-root .tools/python-api-audit
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify -Pnative "-Djavacpp.natives=E:\JavaKotlin\java.cpp\natives\b11669-cpu" "-Djavacpp.model=E:\JavaKotlin\java.cpp\gguf\Qwen3.5-0.8B-Q8_0.gguf"
```

数据在被忽略的 src/test/resources/python-0.3.36-execution-contracts.tsv。来源、环境、数量与摘要见 [执行契约元数据](python-execution-contracts.json)。全部六组数据恢复步骤见 [生成说明](../tools/python_compat/README.md)。

最终完整 `verify -Pnative`：**12,359 项通过，0 失败、0 错误、0 跳过**，包含 13 项真实模型测试。Google Java Format、公开 API 边界检查通过，类库与 demo JAR 已重新打包。全文件映射中 28 项已有窄范围实现、9 项部分实现、12 项待实现；全项目累计 147 / 751（19.57%）勾选。
