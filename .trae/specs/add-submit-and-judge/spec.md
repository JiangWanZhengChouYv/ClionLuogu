# 洛谷代码提交与评测（第二阶段 / MVP2）Spec

## Why

前两个阶段已能在 CLion 内拉取洛谷题目、生成题面 md 与全部样例进 `Pxxxx_samples/`。但写完代码后仍要切到浏览器、登录、粘贴代码、点提交，再去评测记录页看结果——体验断裂。

本 spec 交付第二阶段：在插件内**带上用户登录态**（手动粘贴 cookie）→ **把当前编辑器代码提交**到选定洛谷题目 → **后台轮询评测结果** → 在**完整工具窗口**里展示题目与评测（AC/WA/TLE/MLE + 子任务得分）。

**关键约束（用户强调）**：登录一律用「用户从浏览器复制的 cookie」，**不做密码自动登录**——自动登录有封号风险，也绕不开洛谷人机验证（418/403/Cloudflare 反爬）。本项目只接受「用户主动提供合法 cookie」这种方式，且不向任何第三方服务器转发。

## 背景与已核实事实

来源：社区逆向文章 + vscode-luogu wiki + 本项目 MVP1 已实测。以下为设计依据（非猜测）：

| 项 | 结论 |
| --- | --- |
| 登录态 cookie | 手动粘贴即可；社区/ vscode-luogu 最少要求 **`__client_id`** 与 **`_uid`** 两个身份 cookie；另有 `C3VK`（反爬短时效 ~300s）、`__suid`（游客浏览 id）等 |
| CSRF token | 从页面 HTML `<meta name="csrf-token" content="…">` 提取（正则匹配，与 MVP1 相同的 meta）；提交时放到请求头 **`X-CSRF-Token`** |
| 提交接口 | POST（洛谷 `submit` 相关端点），payload 关键字段：`problemId`(pid)、`code`(源码文本)、`language`(语言 id)；`Referer`/`Origin`/`X-Requested-With: XMLHttpRequest` 需模拟浏览器 |
| 语言 | C++ 族多版本：98/11/14/17/20/23，且各有 `with O2` 变体；提交需传对应语言 id |
| 评测查询 | 拿提交返回的纪录 id（rid）后，查询该记录状态码；状态含 待评测/进行中/AC/WA/TLE/MLE/**等，记录内有各测试点/子任务得分、总用时、总内存、编译错误信息 |
| 反爬红线 | 轮询加延时（建议 ≥1s/次，避免高频触发限流）；带真实浏览器头；不伪造 `cf_clearance`、不用破解库 |
| 现状 | MVP1 `LuoguHttpClient` 已有内存 CookieJar + 浏览器头 + 15s 超时；`LuoguSettings` 为应用级持久化组件 |

> 注：洛谷反爬/接口可能变化。以上是当前可用的合规路径；spec 验收以「能成功携带用户 cookie 拿到登录后数据/提交成功」为准，不追求绕过验证码。

## What Changes

- **新增** `storage/SecureCookieStore.kt`：用 IDE PasswordSafe（`PasswordSafe`）持久化用户 cookie（键如 `__client_id`、`_uid`、`C3VK`、`__suid`），支持保存/读取/清除；**绝不落明文文件**。
- **改造** `api/LuoguHttpClient.kt`：提供将持久化 cookie 注入内存 CookieJar 的能力（启动时从 SecureCookieStore 载入，请求时回传）。
- **改造** `api/LuoguApiService.kt`：新增 `loginWithCookie(cookies)`（组装/校验）、`submitCode(pid, lang, code)`、`getSubmissionStatus(rid)` 等方法；CSRF 从页面 meta 提取。
- **新增** `action/LoginAction.kt`：弹窗让用户粘贴 cookie（文本域 + 说明如何从浏览器获取），保存到 SecureCookieStore 并注入客户端，用 `/api/user/info`（或登录后页面）校验登录态并提示「XX 已登录」。
- **新增** `action/SubmitCodeAction.kt`：读取当前激活编辑器文件的 C++ 代码 → 让用户确认/输入题目 pid（默认从文件名 `Pxxxx.cpp` 猜测）→ 选语言版本 → 后台提交 → 拿 rid → 返回结果通知。
- **新增** `service/JudgePollingService.kt`：后台轮询评测结果（带延时、可取消），返回状态码与子任务。
- **新增** `ui/LuoguToolWindowFactory.kt` 与 `ui/LuoguToolWindow.kt`：**完整面板**——题目列表 + 提交历史 + 选中记录详情（AC/WA/TLE/MLE、各子任务得分、用时内存、编译信息）。`plugin.xml` 注册 tool-window。
- **更新** `plugin.xml`：注册 `LoginAction`、`SubmitCodeAction` 到菜单与右键、注册 tool-window 扩展点。
- **不改**：拉题/生成文件/ md 逻辑（前两阶段成果不动）。

### 本次非目标（明确不做）

- **不做密码自动登录**（封号风险，用户已定）。
- 不改拉题与样例/md 生成链路。
- 不做比赛模式提交、不做网页题面渲染（工具窗口展示文本/简洁渲染即可）。
- 不做多账号切换 UI（MVP2 只支持单一已存 cookie，登录动作即「覆盖保存」）。

## Impact

- Affected specs: 前两个 spec（`add-luogu-mvp1`、`add-problem-md-and-samples`）为前置，本 spec 在其上新增能力，不改其功能。
- Affected code:
  - 新增：`storage/SecureCookieStore.kt`、`action/LoginAction.kt`、`action/SubmitCodeAction.kt`、`service/JudgePollingService.kt`、`ui/LuoguToolWindowFactory.kt`、`ui/LuoguToolWindow.kt`
  - 改造：`api/LuoguHttpClient.kt`、`api/LuoguApiService.kt`、`src/main/resources/META-INF/plugin.xml`
  - `settings/LuoguSettings.kt`：可能加默认语言版本等配置项（可选）

---

## ADDED Requirements

### Requirement: R0 Cookie 安全存储与注入

系统 SHALL 使用 IDE 的 `PasswordSafe` 持久化用户 cookie，并在客户端启动/需要时将其注入内存 CookieJar；cookie 不得以明文写入磁盘文件。

#### Scenario: 保存登录态
- **WHEN** 用户触发登录并粘贴 cookie 后确认
- **THEN** `__client_id`、`_uid`（及可选 `C3VK` 等）被存入 `PasswordSafe`，可直接读取且不落明文文件
- **THEN** 后续发送到 www.luogu.com.cn 的请求自动携带这些 cookie

#### Scenario: 清除登录态
- **WHEN** 用户执行退出/清除
- **THEN** 从 `PasswordSafe` 删除保存的 cookie，并在内存 CookieJar 中清除对应 host 的 cookie

### Requirement: R1 手动粘贴 Cookie 登录

系统 SHALL 提供「登录」动作，弹窗让用户粘贴从浏览器复制的最小 cookie 集，并在校验通过后提示登录状态。

#### Scenario: cookie 粘贴与校验
- **WHEN** 用户在弹窗内粘贴形如 `__client_id=...; _uid=...` 的 cookie 并确认
- **THEN** 解析键值保存并注入客户端，然后调用洛谷账号校验接口
- **THEN** 校验成功时通知「已登录：<用户名>」；失败时提示可读原因且不误导为已登录

#### Scenario: cookie 不完整
- **WHEN** 粘贴内容缺失 `__client_id` 或 `_uid`
- **THEN** 立即提示「cookie 缺少 __client_id / _uid」，不发起网络校验

#### Scenario: 未登录时提交被拒
- **WHEN** 未存入有效 cookie 却执行提交
- **THEN** 提示需要先登录，不发起提交

### Requirement: R2 提交代码

系统 SHALL 提供「提交」动作：读取当前激活编辑器内的 C++ 源码，确认题目 pid 与语言版本后提交到洛谷，并返回评测记录 id。

#### Scenario: 提交当前文件
- **WHEN** 用户在打开 `Pxxxx.cpp` 时触发提交，确认 pid（默认从文件名猜测，可改）与语言版本
- **THEN** 读取文件内容，POST 提交，拿到记录 id（rid）
- **THEN** 通知「已提交到 Pxxxx，记录 id=…」，并触发后台轮询

#### Scenario: 无登录态
- **WHEN** 未登录便触发提交
- **THEN** 引导先登录，不提交

#### Scenario: 无激活文件
- **WHEN** 无激活编辑器或无 `.cpp` 文件打开
- **THEN** 提示选择要提交的文件，不擅自用空代码

#### Scenario: 提交失败
- **WHEN** 网络/CSRF/被拦截导致提交失败
- **THEN** 用 Notification 展示可读原因，不抛未捕获异常，不重复狂发请求

### Requirement: R3 评测轮询

系统 SHALL 在后台以受控频率轮询评测结果，直到终态，并在完成时更新工具窗口与通知。

#### Scenario: 轮询直到终态
- **WHEN** 拿到 rid 后开始轮询
- **THEN** 每隔一段间隔（≥1s）查询状态，期间不阻塞 UI
- **THEN** 到终态（AC/WA/TLE/MLE/成功/编译错误等）停止并展示结果，含总用时/内存/子任务得分；可能带编译错误说明

#### Scenario: 可取消
- **WHEN** 用户取消或提交新的评测
- **THEN** 停止旧轮询，释放资源，不残留后台任务

#### Scenario: 轮询异常
- **WHEN** 单次查询网络失败
- **THEN** 重试有限次并加延时，连续失败则给出可读提示并停止，不死循环

### Requirement: R4 完整工具窗口

系统 SHALL 提供侧边工具窗口，展示题目信息、提交记录与评测结果明细。

#### Scenario: 展示提交历史
- **WHEN** 有多次提交
- **THEN** 工具窗口列出提交记录（题目、状态徽标、时间、用时内存），点击选中查看详情

#### Scenario: 展示评测详情
- **WHEN** 选中一条记录
- **THEN** 展示状态（AC/WA/TLE/MLE 等）、总时间、总内存、各子任务/测试点得分，及编译错误/部分通过提示

#### Scenario: 与拉题联动
- **WHEN** 用户在工具窗口选中某题目
- **THEN** 可展示/打开该题已生成文件，或至少显示题目 pid 与名称（与前面拉题能力衔接，不重复实现拉题）

#### Scenario: 无线程冲突
- **WHEN** 工具窗口更新发生
- **THEN** 更新在 EDT（UI 线程）执行，后台轮询在非 EDT 线程执行，无线程断言错误

---

## MODIFIED Requirements

无（前三阶段能力保持不变）。

## REMOVED Requirements

无。

> 依赖前序 spec 提供的 `LuoguSettings`（应用级持久化）可扩展一个「默认语言版本 / O2」配置项，属可选增强，不在 R 级验收硬性要求内，实现时按需添加。