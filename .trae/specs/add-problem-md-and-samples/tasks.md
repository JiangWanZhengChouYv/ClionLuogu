# Tasks

- [x] Task 1: 恢复 DTO 题面正文字段，保证现有解析不回归
  - [x] SubTask 1.1: `dto.kt` 的 `LuoguProblemDto` 新增 `content: JsonObject?`、`contenu: JsonObject?`（import `kotlinx.serialization.json.JsonObject`），保持 `ignoreUnknownKeys=true` 不变
  - [x] SubTask 1.2: 验证 `./gradlew compileKotlin` 通过
  - [x] SubTask 1.3: 用现有 api 在线拉一次 `P1001`，确认解析成功且可读到 content/contenu 题面对象（可临时在原型或测试打印）

- [x] Task 2: 实现 md 生成服务 ProblemMdGenService
  - [x] SubTask 2.1: 新增 `service/ProblemMdGenService.kt`，输入 `LuoguProblemDto`，输出 Markdown 字符串（题号/题名/难度/标签/背景/描述/输入输出格式/提示 + 全部样例代码块）
  - [x] SubTask 2.2: 正文取 `content` 优先、`contenu` 兜底；缺失/空时输出「暂无题目描述」
  - [x] SubTask 2.3: 轻量清洗：保留 Markdown 换行/`**`/列表，含代码块时原样；不做复杂 HTML→MD 转换
  - [x] SubTask 2.4: 提供将 md 文本写入 `Pxxxx.md` 的写文件封装（用与样例一致的 VFS 写操作 + 刷新）

- [x] Task 3: 改造 ProblemFileGenService 支持全部样例进文件夹
  - [x] SubTask 3.1: 新增按 pid 构造 `Pxxxx_samples` 目录路径的逻辑（`Pxxxx_samples/Pxxxx_{n}.in/.out`，`_1` 起）
  - [x] SubTask 3.2: 遍历 `samples` 全部，逐字节写 in/out，UTF-8 无 BOM，`_1`..`_N`；仅剩第一个样例时也进文件夹（不再写根目录单文件）
  - [x] SubTask 3.3: 保留 `.cpp` 生成与防覆盖原逻辑；样例文件夹整体可重写
  - [x] SubTask 3.4: 样例为空时不建样例文件夹（或建空），返回「该题无样例」提示；结果结构含 md 路径、样例文件数、文件名列表
  - [x] SubTask 3.5: 验证 `./gradlew compileKotlin` 通过

- [x] Task 4: 串联 md + 全部样例到拉题动作
  - [x] SubTask 4.1: `FetchProblemAction` 拉取成功后，调用 `ProblemMdGenService` 生成 `Pxxxx.md`，调用扩展后的 `ProblemFileGenService` 生成全部样例
  - [x] SubTask 4.2: 完成通知聚合：md 路径 + 样例组数 + 样例文件夹路径；仍自动打开 `.cpp`
  - [x] SubTask 4.3: 缺失项（无正文/无样例）不中断，仅通知提示，不抛未捕获异常
  - [x] SubTask 4.4: 验证 `./gradlew buildPlugin -x buildSearchableOptions` 通过

- [x] Task 5: 端到端验证（GUI 需本机 runIde）
  - [x] SubTask 5.1: 样例实现复核：P1001 应生成 `P1001.md` + `P1001_samples/P1001_1.in/.out`（及更多组样例按实际数量），`.cpp` 仍在根目录
  - [x] SubTask 5.2: 构造多组样例题在线核对文件命名与内容一致；无样例/无正文分支核对提示
  - [ ] SubTask 5.3: `runIde` 本机验证（用户操作）：输入 P1001 后确认 md + 样例文件夹生成、cpp 自动打开、通知正确

- [x] Task 6: 收尾与交付
  - [x] SubTask 6.1: 如本 spec 期间修改样例输出位置，检查 `.gitignore` 无需变更；确认无测试残留
  - [x] SubTask 6.2: 本地 commit（先加暂存相关源码文件），不 push（除非用户要求）——沿用现有 origin/main（commit 77b46f0）

# Task Dependencies

- Task 1 无依赖（独立于既有良好 DTO 修改）
- Task 2 依赖 Task 1（需 content/contenu）
- Task 3 无依赖于 Task 2，但与 Task 1 独立可并行；其依赖现状 `ProblemFileGenService`
- Task 4 依赖 Task 2、Task 3
- Task 5 依赖 Task 4
- Task 6 依赖 Task 5
- 可并行：Task 1、Task 3；Task 2 紧随 Task 1