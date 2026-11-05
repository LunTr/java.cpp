# Jinja 差分 Fuzz 进展记录

> 状态快照：本文档记录 `dev.javacpp.python` / `compat.python` 兼容层对 CPython 3.13 行为对齐的进展，
> 重点是 `PythonJinjaTemplate`/`PythonJinjaDialect`（jinjava 适配层）对真实 `jinja2` 的差分收敛。

## 总目标

修正一切 Python 版本和 Java 版本行为不一致的地方，用自动生成的 CPython 3.13 对照数据做差分契约验证。
不要求逐例修，而是消灭系统性类别。

## 已完成的里程碑

| 阶段 | 结果 |
|---|---|
| 语义层差分（Py/PythonStrings/PythonSlices/bytes/JSON/比较协议） | 59,892 用例全部通过 |
| 全量回归（61 个 surefire 报告） | 0 失败 0 错误 |
| Jinja 模板差分 fuzz（3000 用例，真实 jinja2 权威输出） | 失败数 927 → 656 → 435 → 329 → 191 → **1**（进行中） |

失败收敛曲线：

- 927：初跑，多为 jinjava 不支持的语法与宽松语义。
- 656：生成器改为类型良构文法后，剩余真实语义差。
- 435：`==/!=/%/////~` 函数化、`for...else`、`and/or` 操作数语义、`is` 测试、元组字面量。
- 329：括号链式改写、operand 边界、`{` 支持、`not` 调用括号化修复后。
- **191（当前）**：`not` 函数化、`is`/`dict` 属性访问、`tojson`/`title`/`slices`/标签关键字拆分修复后。
- **1（当前）**：批量修复后剩 case=612（`ns.count + (n-n)` 里 `+`/`-` 或 `|sum` 产 Double → `'0.0'` vs `'0'`）。
  - 非法切片：`literal[a:b]` 走 `_cpp_python_slice_soft`（const-fold → TypeError/IndexError→Undefined），非字面量基座 `_cpp_python_slice` 透出 TypeError。
  - `PyRange`/`PyGenerator`/`PyJinjaTuple` 改 Iterable/PySized/PyComparable，绕过 jinjava resolver 的 List→PyList 包装（宏参数/set 保 repr）。
  - `len()` 返回 `PyDeferredError`（死三元臂惰性），观测点 `strict()`：unnone/getAsString/pyEq/pyNe/pyIn/iterate/sliceIndex/`{% set %}` RHS。
  - `x is T ARG`：可解析 ARG→`raise_exception`（运行期）；不可解析→`\u0002` 哨兵→`close + {% __cpp_isarg__ %}`（编译期致命）。
  - `x is true/false` 恒等测试 `_cpp_py_istrue/isfalse`（`0.0 is false`→False）；`is none/defined` 保持 eq/ndef。
  - `{% if/elif/while %}` 条件包 `_cpp_py_truthy`；`forElse` 单遍 out 坐标重写 + 按 for 帧 ifDepth 配对嵌套 else。
  - `wordAt` 深度改绝对（括号从 0 计）——修嵌套三元/else 配对失败。
  - `.attr` 标识符基座对 `{content,role,x,y,k}` 走 `_cpp_dictget`（dictGet null/Undefined 基座抛 UndefinedError）。
  - `select|reject` 无参按真值过滤（两个重载都要处理）；`default`/`attr`/`sum`/`upper|lower|capitalize|trim` 自定义语义。
  - `tojson` 对 Undefined/Namespace/PyRange/PyGenerator 抛 TypeError；`isTests` 挪到 ternary 前。

## 当前架构

### `PythonJinjaDialect.normalize()` 归一化管线

在 jinjava 解析前把 Jinja/Python 语义改写成 jinjava 可执行的辅助函数调用：

- `forElse`：`{% for x in y %}..{% else %}..{% endfor %}` → `{% if (y) %}{% for %}..{% endfor %}{% else %}..{% endif %}`，
  支持 `for x in y if c`（包 `{% if c %}` 循环体）与三元迭代器 `for x in (a if c else b)`。
- `PythonJinjaSlices.normalize`：`x[a:b:c]` → `_cpp_python_slice`，`x[i]` → `_cpp_python_index`。
- `methods`：`s.name(...)` → `s|py_name(...)`；`(...).attr`/`[...].attr`/`{...}.attr` → `_cpp_dictget(base,'attr')`；
  `(...)|filter` 前置括号化避免字面量 `|` 被当按位或。
- `argSafe`（函数参数位可用语法）：`unwrap`（保元组括号）→ `tupleLiterals`（`(1,2)` → `_cpp_tuple`）
  → `ternary`（`a if c else b` → `_cpp_py_tern`）→ `notX` → `isTests` → `orAnd` → `inKw` → `comparisons`。
- 逐操作符函数化（jinjava 原生运算符语义不符）：
  `==`→`_cpp_py_eq`，`!=`→`_cpp_py_ne`，`/`→`_cpp_py_tdiv`，`//`→`_cpp_py_floordiv`，
  `%`→`_cpp_py_mod`，`~`→`_cpp_py_cat`，`in`/`not in`→`_cpp_py_in`/`_cpp_py_notin`，
  `a and b`→`_cpp_py_tern(a,b,a)`，`a or b`→`_cpp_py_tern(a,a,b)`，
  `not X`→`_cpp_py_not(X)`（操作数延伸到 `and`/`or`；`is not`/`not in` 中的 `not` 跳过），
  `x is defined`→`_cpp_py_ndef/defined`，`x is y`/`x is not y`→`_cpp_py_eq/ne`。
- `{% if/elif/while/do/print %}`：关键字后接 `argSafe` 表达式；
  `{% set t = e %}`：`=` 右侧 `argSafe`；`{% for t in e %}`：` in ` 右侧 `argSafe`。

### `PythonJinjaTemplate` 注册内容

- 函数：`_cpp_python_slice`、`_cpp_python_index`、`_cpp_dictget`、`_cpp_py_eq/ne/tdiv/floordiv/mod/cat/tern/in/notin/not/defined/ndef`、
  `_cpp_tuple`、`_cpp_raw`、`range`、`len`、`namespace`、`raise_exception`、`strftime_now`。
- 过滤器：`py_*` 字符串方法（`PythonStrings` 语义 + `Undefined` 兜底）、
  `title`（jinja2 分词规则：`-`/空白断词，`_` 不断）、`upper`/`lower`/`capitalize`/`trim`、
  `tojson`（`PythonJson.dumps`）、generator 包装（`map/select/reject/batch/unique` → `PyGenerator`，
  迭代时物化、渲染时显示 `<generator object ... at 0x0>`）。
- `Undefined`：可链 `falsy` 哨兵集合，渲染为空串。
- `PythonInterpreter.getAsString`：`null`/`PyNone`→`None`，`Undefined`→空，`PyRange`/`PyGenerator`/namespace→各自 repr，其余走 `Py.str`（元组 `(1, 2)`、列表、`None`）。
- jinjava 配置：`usePyishObjectMapper`、`useNaturalOperatorPrecedence`、`iterateOverMapKeys`。

## 本轮已验证修复（探针全对）

- `({'k':1}.k)` → `1`、`({'k':1}.role)`/`(data.y)` → 空（原 `[1]`/`[None]`）。
- `(1, 2)` → `(1, 2)`；`(1,2)[1:]` → `(2,)`（`PythonSlices.slice` 保留 `PyTuple`）。
- `tools is not defined==(true) is not true` → `True`；`missing is not defined` → `True`。
- `not (false|tojson)` → `False`（jinjava 的 `not`+`(`+`|` 吞过滤器 bug，由 `_cpp_py_not` 绕过）。
- `{{ 'a-b_chello world'|title }}` → `A-B_chello World`（jinja2 语义）。
- `{% for x in seq %}a{% else %}e{% endfor %}` → `aaa`；`{% for x in seq if x>1 %}` 循环过滤 → `23`。
- `{% if (n-(n+2)) %}` / `{% set t = 'xyz'[1] %}` 等标签表达式归一化。

## 剩余 191 失败的归类（待修）

1. **expected-failure-but-rendered（占大头）**：oracle 期望 Python 报错，适配层宽松渲染。
   - `'ab'[1:'x']` / `'ab'[1:2:'x']` 非法切片索引应抛 `TypeError: slice indices must be integers or None...`，
     目前 `slice()` 捕获后返回 `Undefined`（渲染空串）——方向反了，**应让 PythonException 透出**。
   - 其他 Python 报错但 jinjava 接受的构造（跨类型运算、未定义算术等）需逐个确认是否属于适配层承诺面。
2. **宏参数中 generator/range 物化**：`m(range(-2))` 渲染 `<[]|d>` 应为 `<range(0, -2)|d>`；
   `m((1,2)|reject('none'))`/`|batch` 渲染 `[1, 2]`/`[[1,2]]` 应为 `<generator object ... at 0x0>`。
   jinjava 宏绑定把 `PyGenerator`/`PyRange` 当 `Collection` 物化，需要让包装类型在参数传递中不被展开
   （候选：非 Collection 包装 + `for` 迭代时惰性展开，或拦截宏参数绑定）。
3. **`is` + 过滤器优先级**：`(b) is false|tojson` 期望 `true`，jinjava/`isTests` 解析顺序与 jinja2 不同。
4. **`tojson` 对 Undefined/未绑定名的真假值**：`{%- if (b|tojson) %}` 期望渲染 `x`（jinja2 `undefined|tojson` → `"null"` 真值），
   目前不渲染——`Undefined` 经 `tojson` 后应为真值字符串。
5. **`_cpp_dictget` 缺失键抛 AttributeError**：嵌套 `{% for %}` 中 `(data.y)` 期望空输出，
   应捕获后返回 `Undefined`（case=729 期望 `aTRUE` 实为 `AttributeError`）。
6. **`(not ('x\ny' in d))` → TemplateSyntaxError**（case=665）：`not` 操作数内 `in` + 换行字符串字面量的组合，
   `notX` 与 `inKw` 交互有待查。
7. **for-else 嵌套配对**（case=760）：`{% else %}` 在内层 `for` 与外层 `for` 间的归属，`forElse` 配对有误。

## 生成器侧（`tools/python_compat/generate_jinjafuzz_contracts.py`）

- 3000 用例，类型良构有界文法：str/list/tuple/map 操作数类型感知，运算符按类型限定。
- 保留受支持的错误注入：`raise_exception`、未定义属性、str 方法非法参数、非法切片类型、
  `False in seq` 之外的越界/类型错误等 oracle 承诺路径。
- `tojson` 用 `json.dumps` 对齐签名；namespace 用真实 `jinja2.utils.Namespace`（`<Namespace {'c': 0}>`）。
- 输出地址归一化（`<generator object ... at 0x...>` → `0x0`）。

## 复现与验证

```bash
# 编译（必须 Java 21）
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.12
mvnw.cmd -q compile -DskipTests

# Jinja fuzz 契约（3000 用例）
mvnw.cmd -q test -Dtest=PythonJinjaFuzzContractTest -Dsurefire.failIfNoSpecifiedTests=false

# 语义层契约（59,892 用例）
mvnw.cmd -q test -Dtest=PythonSemanticsContractTest -Dsurefire.failIfNoSpecifiedTests=false

# 全量回归
mvnw.cmd -q test
```

固件再生成（需 Windows 侧 CPython 3.13.9）：

```bash
python tools\python_compat\generate_jinjafuzz_contracts.py
python tools\python_compat\generate_semantics_contracts.py
```

## 下一步

1. 非法切片改回抛 `TypeError`（对齐"expected failure"用例，预计消掉最大一块）。
2. `_cpp_dictget` 缺失键/`Undefined` 基座返回 `Undefined`。
3. 宏参数 generator/range 物化问题（jinjava `Collection` 展开拦截）。
4. `not` 操作数内 `in`+字符串转义的归一化边界（case=665）。
5. `is`+过滤器、`for-else` 嵌套配对。
6. 归零后跑全量回归 + `git ls-files src/test/resources` 检查 + 文档同步。
