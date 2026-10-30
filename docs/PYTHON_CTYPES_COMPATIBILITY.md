# _ctypes_extensions.py 迁移记录

日期：2026-10-30。固定基线为 llama-cpp-python 0.3.36 / `1652066e0af45f2313b339670ef9555e8a54e545`，生成环境为 Anaconda CPython 3.13.9。

本批完成该文件全部 5 个定义项到 `compat.python.PythonCtypesExtensions` 的逐句移植，Java 侧不包含任何 Python 运行时：

| 原版定义 | Java 入口 |
|---|---|
| `load_shared_library` | `loadSharedLibrary(String, Path)` 及 `loadSharedLibrary(List, Map)` 绑定形式 |
| `ctypes_function_for_shared_library` | `ctypesFunctionForSharedLibrary` |
| `… .ctypes_function`（嵌套） | `ctypesFunctionForSharedLibrary` 返回的 `PyCallable` |
| `… .decorator`（嵌套） | 上一步返回的 `PyCallable` |
| `_byref` | `byrefStub`（TYPE_CHECKING 桩，恒返回 None）；模块级 `byref = ctypes.byref` 绑定由 `byref` 方法承载 |

## 端口设计

原版函数触达的副作用全部由显式端口承担，Java 实现只做控制流：

- `Host`：`sys.platform`、`sys.version_info`（按元组字典序比较 `>= (3,8)`）、`os.add_dll_directory`、`os.environ`（缺键 `[]` 读由端口按原样抛 `KeyError`）、`os.pathsep`、`os.path.join`、`ctypes.CDLL`、`ctypes.RTLD_GLOBAL`、`ctypes.byref`。
- `Path`：`pathlib.Path` 的 `/` 拼接、`exists()`、`str()`。
- `MutableAttributed` / `PythonFunction`：字典支持的属性对象；`__dict__` 返回底层映射，描述符属性（`__name__`、`__module__` 等）经 `withDescriptor` 层叠，复刻 CPython 中“函数描述符属性不进 `__dict__`”的区别。

## 已核对行为

66 组固定原版对照覆盖：

- 全部平台分支：emscripten / linux* / freebsd* / darwin（.so 与 .dylib 顺序）/ win32（两个候选名）/ 未知平台 `RuntimeError`；候选不存在时跳过、全部缺失时 `FileNotFoundError` 原文。
- win32：`add_dll_directory` 两次调用、`PATH` 前置（缺键 `KeyError`）、`CUDA_PATH`/`HIP_PATH` 的 bin/lib 逐项 join、version_info < 3.8 跳过第二段、`winmode`/`mode` kwargs 内容。
- emscripten：`LD_LIBRARY_PATH` 的 `get`/`split`/`contains`/`set` 顺序、llama 与 mtmd 依赖表、依赖缺失跳过、依赖加载失败 `RuntimeError` 原文。
- CDLL 失败按 `f"...: {e}"` 原样拼接端口异常消息。
- 装饰链：`enabled` 真值判断、`getattr(lib, name)` 失败传播、`argtypes`/`restype` 赋值、`functools.wraps` 的 WRAPPER_ASSIGNMENTS 顺序（`__doc__` 先于 `__annotations__`/`__type_params__`）、`__dict__` 批量复制与 `__wrapped__`；function/callable 实例/dict/object()/str 五类 wrapped 对象。
- 参数绑定报错：缺参（含三参牛津逗号）、超位、未知与重复 kwargs 的原版完整 qualname 消息；`byref` 的 C 函数元数报错（`expected at least/at most`、`takes no keyword arguments`）与实例/整数类型错误。
- `byref` 成功路径返回 cparam，记录 `指针 - 对象地址` 的实际偏移（含负数、bool、2**40）；3.13.9 允许负 offset。

端口替身记录完整调用轨迹（add-dll-dir / env-get / env-set / env-has / env-getdef / join / exists / cdll），测试逐条比对顺序。

## 边界

- 真实操作系统的 DLL 加载、目录搜索和环境变量写入由 `Host` 端口决定；本层只保证控制流与报错原文一致。
- `byref` 端口负责 ctypes 实例类型与引用对象表示；Java 侧默认 offset 为 0、保留任意精度长整型偏移。
- `load_shared_library` 的旧映射 `internal.NativeLoader.load` 保留为 Java 原生加载实现；`PythonCtypesExtensions` 负责原版逐句行为。

数据位于被忽略的 `src/test/resources/python-0.3.36-ctypes-contracts.tsv`，元数据见 [python-ctypes-contracts.json](python-ctypes-contracts.json)，生成脚本 `tools/python_compat/generate_ctypes_contracts.py` 已纳入 `generate_all_contracts.py`。

```powershell
& E:/soft/Anaconda/python.exe tools/python_compat/generate_ctypes_contracts.py --source-root .tools/python-api-audit
& E:/soft/Anaconda/python.exe tools/python_compat/update_inventory.py
.\mvnw.cmd "-Dtest=PythonCtypesContractTest" test
```
