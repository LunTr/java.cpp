# 实现与验证记录

> 后续更新（2026-10-23）：已加入 Python 0.3.36 错误桥接及 37 项静态聊天函数实现。最新 `verify -Pnative` 为 10,713 项通过，0 跳过（含 300 条桥接样例、10,376 条聊天函数及 CPython 样例）；来源、逐项覆盖及验证边界见 [Python 核对记录](PYTHON_COMPATIBILITY.md) 和 [聊天函数记录](PYTHON_CHAT_COMPATIBILITY.md)。下文保留初次封装阶段的测试与产物快照。

日期：2026-10-23，Asia/Shanghai。

## 成品位置

- 根目录为单个 Maven 工程，唯一 POM 为 `pom.xml`。
- 封装源码：`src/Java_Cpp/io/github/yaulun/javacpp`，包含 API、exception、internal。
- demo：`src/main/java/io/github/yaulun/demo`；原 `org.example.Main` 入口保留并连接到演示入口。
- 测试：`src/test/java`。
- `Java_Cpp` 内仅保留 `io` 源码树。包权限与目录边界由测试检查。
- `target/java-cpp-1.0.0-SNAPSHOT.jar`：96164 字节，仅包含封装类。
- `target/demo-all.jar`：2094750 字节，包含 demo、封装与 JNA。
- 已检查类库 JAR，包含的文件中没有 demo、org.example、DLL、GGUF、原 C++ 源码和测试类。demo JAR 保留 JNA 许可证。

## 环境与原生 ABI

Windows 11 x64，Oracle JDK 21.0.12，Maven 3.9.16，JNA 5.19.1，JUnit 6.1.3。

llama.cpp b11669，完整 commit 为 `620d2225cc436ab0b001e3200e590e3b286ef9ab`，运行库报告 llama 0.7.0-dev、ggml 0.27.0。CPU 包和 CUDA 12.4 两个包均完成 SHA-256 校验，摘要见 THIRD-PARTY.md。CPU 为 i5-12500H，GPU 为 RTX 2050 4 GiB，驱动 616.92。

结构体尺寸为 80 / 168 / 1 / 16 字节，关键偏移及默认参数通过自检。设备注册的 count/get 符号位于 ggml.dll，设备属性符号位于 ggml-base.dll。版本校验先于按值结构体调用。日志与进度回调保留强引用，回调异常被截获并记录，进入运行时锁之前检查回调重入。

## 本地权重

文件：`gguf/Qwen3.5-0.8B-Q8_0.gguf`。

- 大小：833592096 字节。
- SHA-256：`37ae482d336108d23516fa35e8e0c4126688d81018b87178a18d752a1357814f`。
- GGUF v3，335 个张量，41 个元数据键。
- 实际模型：qwen35，24 层，训练上下文 262144，词表 248320。
- 文件带有额外 MTP 张量，当前常规生成路径跳过这些张量。

该文件与任务文档固定下载 revision 的大小和摘要不同。C10 将它作为本地 revision 给出 WARN 并记录实算摘要；C13 和完整 demo 验证实际加载及推理。下载器仍对文档固定 revision 使用严格大小与 SHA-256 校验。

## 构建与测试

执行并通过：

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd verify -Pnative "-Djavacpp.natives=E:\JavaKotlin\java.cpp\natives\b11669-cpu" "-Djavacpp.model=E:\JavaKotlin\java.cpp\gguf\Qwen3.5-0.8B-Q8_0.gguf"
.\run-check.cmd
.\run-demo.cmd --greedy --ctx 1024 --max-tokens 256
```

共 30 个测试通过，包含 21 个普通测试与 9 个真实模型测试。普通测试和原生测试分别执行，最后的 verify 同时检查格式。原生测试覆盖：

- 版本、设备、元数据、中文与 emoji 分词往返、特殊 token、模板渲染。
- 四种停止原因、跨批次位置连续性、贪心与固定种子复现。
- 单轮和多轮生成、姓名记忆、完整轮次裁剪、监听器失败后的完整历史回滚、取消时不提交历史。
- 加载进度监听器失败的 E206 与原因保留、模型级联关闭、vocabOnly、关闭后使用、回调重入与失败后恢复。

普通测试包括本地 HTTP 服务模拟的 200、206、416、404、错误 Content-Range、摘要不符和传输中断后续传。源码经 Google Java Format 检查；公开 API 的字段权限、签名中 JNA 类型隔离和 demo 依赖边界有专门测试。

最终 C01–C14：PASS=13、WARN=1、FAIL=0、SKIP=0。C14 七类异常场景均符合预期，GPU 模型上的并发测试也成功完成。完整报告位于根目录 `check-report.txt`。

## 演示实测

`docs/demo-session.txt` 保存四轮脚本的原始模型输出。最终一轮输出“小明”，打印“记忆测试通过”。系统提示明确要求根据完整历史回答，末轮要求只回答名字。

`docs/interactive-session.txt` 保存交互命令验证：中文提问、`/reset`、`/think on`、思考流输出、`/think off`、再次 reset 和 `/exit`。思考演示限制 96 个 token，按 MAX_TOKENS 停止。

CPU 与 CUDA 均成功输出中文并按正常顺序释放模型、上下文和采样器。单轮 31-token 演示中，CPU 约 41.6 tok/s（ctx=1024），CUDA 约 78.7 tok/s（ctx=4096）；这些数值记录各次演示运行。最终检查程序在 CUDA 上生成 16 个 token，约 89.5 tok/s。结果仅记录对应运行参数和设备。

两个原生依赖准备分支均通过：`setup-natives.ps1 -Backend Cpu` 与 `-Backend Cuda`。已存在的正确文件会逐项核验后保留，避免重复覆盖正在被进程使用的 DLL。
