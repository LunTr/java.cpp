# 依赖与上游记录

- llama.cpp：MIT，官方 [b11669 发布页](https://github.com/ggml-org/llama.cpp/releases/tag/b11669)。参考头文件 [llama.h](https://github.com/ggml-org/llama.cpp/blob/b11669/include/llama.h)。绑定版本为 0.7.0-dev，ggml 0.27.0，commit `620d2225cc436ab0b001e3200e590e3b286ef9ab`。
- JNA 5.19.1：LGPL-2.1-or-later 或 Apache-2.0，项目 [java-native-access/jna](https://github.com/java-native-access/jna)。demo JAR 保留依赖中的许可证文件。
- JUnit 6.1.3：EPL-2.0，仅用于测试。
- Google Java Format 1.28.0：Apache-2.0，仅用于构建时格式检查。
- Maven Wrapper：Apache-2.0，保留生成脚本中的许可证声明。
- Qwen3.5 模型及其 GGUF 分发遵循相应模型仓库的许可和版本信息；模型单独下载。当前本地文件摘要见验证记录。

- llama-cpp-python 0.3.36：MIT，固定参考 commit `1652066e0af45f2313b339670ef9555e8a54e545`；用于函数盘点、错误/警告文本和原版分支契约样例。来源及文件摘要见 `docs/python-api-audit.json`。运行 Java 成品无需安装 Python 包。

## 官方 Windows 发布包实算 SHA-256

| 包 | SHA-256 |
|---|---|
| CPU x64 | `37b3efcf962847ca869745fd4ccf7f5e7e8dacfeff9fa3523b4ca8cdc66d632b` |
| CUDA 12.4 x64 | `c2da1f73ed176ae093ac5aceb978a7b167a16113f6d74c6789eeaee2135cb8dc` |
| CUDA 12.4 runtime | `8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6` |

原生包中的 OpenMP、CUDA、VC++ 运行依赖保留各自许可。上游参考仓库、权重、DLL 和下载缓存单独存储。Java 类库与 demo JAR 包含 Java 实现及各自所需 Java 依赖。
