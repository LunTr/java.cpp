# _logger / _warn_deprecated / 小成员迁移记录

日期：2026-10-30。固定 Python 基线：llama-cpp-python 0.3.36，commit `1652066e0af45f2313b339670ef9555e8a54e545`。

## 覆盖范围

本轮把 20 个部分覆盖项迁入已覆盖/绑定：

| Python 项 | Java 对应 | 说明 |
|---|---|---|
| _logger.llama_log_callback | PythonLlamaLogger.logCallback | GGML→logging 级别映射、CONT 沿用上级、logger.level 门控、stderr 分两次写（文本 + end=""）、_last_log_level 更新 |
| _logger.set_verbose | PythonLlamaLogger.setVerbose | logger.setLevel(DEBUG/ERROR) |
| llama_cpp._warn_deprecated | PythonWarnings.deprecated | 消息模板；过滤器动作 always/ignore/error/once/module/default 与调用方 __name__ 规则 |
| llama_cpp.llama_context_default_params | internal.LlamaLibrary | 结构已按 b11669 对齐 168 字节（含 moe_cache_size），列为绑定完成 |
| llama_cpp.llama_init_from_model | internal.LlamaLibrary | 同上，按值传参 |
| llama_tokenizer.LlamaTokenizer.from_ggml_file | PythonLlamaTokenizer.fromGgmlFile | 工厂注入 `Llama(model_path=path, vocab_only=True)`，取 `_model` 为 codec |
| llama.Llama.ctx | PythonLlama.ctx | 属性直读句柄，关闭后仍返回原值（原版语义） |
| llama.Llama.model | PythonLlama.model | 同上 |
| llama.Llama.__del__ | PythonLlama.close / Cleaner | 函数体即 self.close()；Java 由幂等 close + Cleaner 承担 |
| llama.Llama.__init__.free_lora_adapter | PythonLlamaResource.free | 嵌套闭包：None 早退、成功后清空、失败保留引用 |
| llama_cache BaseLlamaCache 4 项 + LlamaDiskCache 6 项 | PythonLlamaCache / PythonLlamaDiskCache | 见 docs/PYTHON_CACHE_COMPATIBILITY.md |

## 行为细节

- `llama_log_callback`：`log_level` 取映射表（level=5 沿用 `_last_log_level`）；打印门控用 `logger.level <= 映射[level]`（注意不是 logLevel），Python 初始 logger.level=0 表示全打印。未知 level 走 dict 索引抛 KeyError；`text.decode("utf-8")` 严格模式，错误消息按 CPython 形状（`invalid start byte`/`invalid continuation byte`/`unexpected end of data`，截断序列消费过续字节时用复数区间 `bytes in position X-Y`）。
- `_warn_deprecated`：`warnings.warn(msg, DeprecationWarning, stacklevel=2)`；对照执行真实 warnings 模块，caller 帧的 `__name__` 决定 default 过滤是否放行，`__warningregistry__` 去重键保留在调用方模块。
- `ctx`/`model`：原版直接返回 `self._ctx.ctx` / `self._model.model`，close 后属性仍存在（句柄已释放但引用照常返回）。Java 之前关闭后返回 null 有偏差，已修正并同步集成测试断言。
- `free_lora_adapter`：`if adapter is None: return; free(adapter); adapter=None`——释放抛错时引用保留，挂入 ExitStack 的顺序由调用方注册承担。

## 验证

`generate_lowlevel_contracts.py` 以 AST 执行固定原版函数体（25 个基线文件先核 SHA），生成 25 组对照：logger 级别/门控/KeyError/UnicodeDecodeError 5 组场景、warnings 6 动作 × 2 调用方模块、LoRA 3 态、dunder 3 项、tokenizer 工厂 kwargs 2 组。`PythonLowLevelContractTest` 回放逐字节比对；缓存 ABC 场景 8 组随 `generate_cache_contracts.py` 一并再生（cache TSV 现 122 行）。本轮 compat.python 包内 JUnit：13,085 项通过、0 失败。

数据：`src/test/resources/python-0.3.36-lowlevel-contracts.tsv`（被 .gitignore 忽略），元数据 `docs/python-lowlevel-contracts.json`（含函数清单、用例数、fixture SHA-256）。

全输入一致性认证保持 0 项；原生 logger 回调到 stderr 的真实效果、diskcache 物理存储、以及 Cleaner 触发时序继续验收。
