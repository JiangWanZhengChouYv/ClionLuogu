# Tasks

- [x] Task 1: 实现 Cookie 安全存储与注入
  - [x] SubTask 1.1: 新增 `storage/SecureCookieStore.kt`：用 `PasswordSafe`（CREDENTIALS_STORE）持久化 `Map<String,String>` cookie（键：__client_id/_uid/C3VK/__suid），提供 `save(cookies)`/`load()`/`clear()`/`hasLogin()`（判断 __client_id 与 _uid 是否齐全）
  - [x] SubTask 1.2: 改造 `api/LuoguHttpClient.kt`：新增将持久化 cookie 注入内存 CookieJar 的方法（`injectCookies(Map)`，按 host=www.luogu.com.cn）与清空方法（`clearCookies()`）
  - [x] SubTask 1.3: 验证 `./gradlew compileKotlin` 通过

- [x] Task 2: 实现手动粘贴 Cookie 登录动作
  - [x] SubTask 2.1: `api/LuoguApiService.kt` 新增 CSRF 提取（复用 meta `csrf-token` 正则）与账号校验方法（如 `getCurrentUser()`，返回用户名或 null）
  - [x] SubTask 2.2: 新增 `action/LoginAction.kt`：弹窗（多行文本框）让用户粘贴 `__client_id=...; _uid=...` 格式，解析 → `SecureCookieStore.save` → `LuoguHttpClient` 注入 → 后台校验 → 通知「已登录/失败原因」
  - [x] SubTask 2.3: cookie 不完整（缺 __client_id/_uid）时即时提示不发请求；支持清除登录态
  - [x] SubTask 2.4: `plugin.xml` 注册 `LoginAction`（Tools 菜单 + 右键组，中文「登录洛谷 / 粘贴 Cookie」）
  - [x] SubTask 2.5: 验证 `./gradlew compileKotlin` 通过

- [x] Task 3: 实现代码提交动作
  - [x] SubTask 3.1: `LuoguApiService.kt` 新增 `submitCode(pid, langId, code): rid`，POST 提交，携带 `X-CSRF-Token`/`Referer`/`Origin`/`X-Requested-With`，返回记录 id
  - [x] SubTask 3.2: 定义 C++ 语言 id 表（98/11/14/17/20/23 each with O2），并提供默认值可后续接入设置
  - [x] SubTask 3.3: 新增 `action/SubmitCodeAction.kt`：读取当前激活编辑器 `.cpp` 内容 → 弹窗确认 pid（默认从文件名猜测，可改）+ 选语言版本 → 后台提交 → 通知 rid
  - [x] SubTask 3.4: 未登录/无激活文件/提交失败各走可读路径，不抛未捕获异常
  - [x] SubTask 3.5: `plugin.xml` 注册 `SubmitCodeAction`（中文「提交代码到洛谷」）
  - [x] SubTask 3.6: 验证 `./gradlew buildPlugin -x buildSearchableOptions` 通过

- [x] Task 4: 实现评测轮询
  - [x] SubTask 4.1: `LuoguApiService.kt` 新增 `getSubmissionStatus(rid)` 返回状态（含状态码、总用时/内存、子任务得分、编译错误）
  - [x] SubTask 4.2: 新增 `service/JudgePollingService.kt`：可取消的后台轮询（≥1s 间隔、有限重试），终态停止，回调通知 / 工具窗口
  - [x] SubTask 4.3: 接入提交动作：提交拿到 rid 后启动轮询，完成时更新通知与工具窗口
  - [x] SubTask 4.4: 验证 `./gradlew compileKotlin` 通过

- [x] Task 5: 实现完整工具窗口
  - [x] SubTask 5.1: 新增 `ui/LuoguToolWindowFactory.kt` + `ui/LuoguToolWindow.kt`：侧边面板（题目区 + 提交历史区 + 详情区）
  - [x] SubTask 5.2: 提交历史列表（题目/状态徽标/时间/用时内存），点击展示详情（状态、子任务得分、编译错误提示）
  - [x] SubTask 5.3: 更新逻辑运行于 EDT；构造轻量（不引入重 UI 依赖，用 Swing/JB 组件即可）
  - [x] SubTask 5.4: `plugin.xml` 注册 tool-window 扩展点（id、factoryClass、icon 可省略）
  - [x] SubTask 5.5: 验证 `./gradlew buildPlugin -x buildSearchableOptions` 通过
  - [x] SubTask 5.6: 补提交联动：SubmitCodeAction 提交成功后调 `LuoguToolWindow.INSTANCE.trackSubmission(pid, rid)` 启动轮询并实时更新窗口

- [x] Task 6: 端到端验证
  - [x] SubTask 6.1: 复核 code 安全：无明文 cookie 写入仓库/磁盘（grep 检查 `PasswordSafe` 用法与无 `cookie.*=.*写文件` 路径）
  - [x] SubTask 6.2: 编译与打包全通过；确认 login/submit/judge/tool-window 类都在产物 jar
  - [ ] SubTask 6.3: runIde 本机验证（用户操作，评估：粘贴 cookie → 登录 → 提交 → 工具窗口看结果），不在无头自动跑
  - [x] SubTask 6.4: 完成后本地 commit（不 push，除非用户要求）（commit bedfbe8）

# Task Dependencies

- Task 1 无依赖（新组件 + 改造 HttpClient）
- Task 2 依赖 Task 1（登录需注入 cookie）
- Task 3 依赖 Task 1（提交需 cookie），CSRF/提交方法独立于 Task 2 可在 Task 1 后并行
- Task 4 依赖 Task 3（需 rid 入口）与 Task 5（结果展示，可异步）
- Task 5 依赖 Task 4（展示评测）也可先搭壳并行，但与 Task 4 联合完成验收
- Task 6 依赖其余全部
- 可并行：Task 1 先行；Task 2/3 在 Task 1 后并行；Task 4/5 在 Task 3 后并行