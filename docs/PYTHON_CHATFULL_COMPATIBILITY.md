# Python  重项兼容性（第二轮 · 完结）

固定基线：（commit ），对照体由
 对**原版函数体**在
确定性桩（jinja/mtmd/llama/huggingface_hub 全部注入端口）下执行生成。

## 本轮覆盖（29 项 → ）

| 组 | 函数 | Java 对应 |
|---|---|---|
| Jinja2ChatFormatter |  /  /  /  /  /  |  + （env_init 记录扩展名、 形状、stop-on-last-token 判据标记） |
| functionary v1 |  | （tools→functions 归一、auto 探测 completion、schema grammar 回落、chat 响应组装） |
| functionary v1v2 | 外层 +  +  | （v1/v2 token 表、/具名函数循环流式帧、tool_id 工厂注入、finish_reason 键序） |
| Llava15 |  /  +  /  /  /  /  /  | ///// |
| MTMD | 同名 8 项（含 ） | 同上 + （metadata 校验原文） |
| chatml |  | （1300 字节模板常量内嵌、三分支、自动工具循环、stream 拒绝原文） |

## 端口

-  /  / ：parser.skip + parse_statements；env_init/from_string/render 序列
- ：params_default/init_from_file/support_vision/free/bitmap/chunks 生命周期/eval_chunk_single
- ：detokenize/ctx/kv/n_ctx/batch/eval/input_ids/stack.callback
- ：validate_repo_id/ls/hf_hub_download + fnmatch 等价
- //：复用第一批

## fixture 要点

-  经端口返回 0； 在 Python 桩下天然抛 ——Java 端口同路径抛同款错误对齐
-  模板常量为源码内嵌 1300 字节字面量（AST 提取嵌入 ）；llava  805 字节同理
- 显式 tool_choice 分支 trace 含 ；auto 分支含 ——桩仅在显式传 kwargs 时记录

## 验证

- 24 组契约回放  24/24 全过；第一批  21/21 回归全过
- ：covered 353 / checked 468 / missing 277 / partial 6
-  **全部 60+ 项已覆盖，文件清零**
