# Checklist

## Cookie 安全存储

- [x] cookie 用 `PasswordSafe` 持久化，无明文文件落盘（grep 无 `写 cookie 到文件` 代码路径）
- [x] `SecureCookieStore` 提供 save/load/clear/hasLogin（__client_id 与 _uid 齐全判定）
- [x] `LuoguHttpClient` 可将持久化 cookie 注入内存 CookieJar，并可清空
- [x] 有登录态时请求自动携带 cookie；清除后不再携带（登录显式注入 + 首次 getClient 时从 PasswordSafe 惰性重载入 CookieJar，重启后仍恢复）

## 登录

- [x] `LoginAction` 弹窗粘贴 cookie，解析 `__client_id`/`_uid` 等并保存注入
- [x] cookie 校验：缺失 __client_id/_uid 立即提示，不发网络
- [x] 账号校验成功通知「已登录：<用户名>」，失败给可读原因（后台 getCurrentUser 回 EDT 通知；实际登录态取得需真机）
- [x] 支持清除登录态
- [x] `LoginAction` 在 Tools/右键注册

## 提交

- [x] `LuoguApiService.submitCode` 带 X-CSRF-Token/Referer/Origin/X-Requested-With，返回 rid
- [x] C++ 语言 id 表（98/11/14/17/20/23 each with O2）
- [x] `SubmitCodeAction` 读取当前激活 `.cpp`、确认 pid（默认文件名猜测）+语言、后台提交、通知 rid
- [x] 未登录/无激活文件/提交失败各走可读路径，不抛未捕获异常
- [x] `SubmitCodeAction` 在 Tools/右键注册

## 评测轮询

- [x] `getSubmissionStatus(rid)` 返回状态码/用时内存/子任务得分/编译错误
- [x] `JudgePollingService` 可取消、≥1s 间隔、有限重试、终态停止、不死循环
- [x] 提交拿 rid 后自动轮询并在完成时更新通知与工具窗口

## 工具窗口

- [x] `LuoguToolWindow` 侧边面板含提交历史/详情区
- [x] 提交历史列表 + 点击展示详情（状态/子任务得分/编译错误）
- [x] UI 更新在 EDT，轮询在后台线程，无线程断言错误
- [x] `plugin.xml` 注册 tool-window 扩展点

## 构建与交付

- [x] `./gradlew compileKotlin` / `buildPlugin -x buildSearchableOptions` 全部通过
- [x] login/submit/judge/tool-window 类均存在于产物 jar
- [x] 无明文 cookie 落盘、无测试残留
- [ ] 改动已本地 commit（未擅自 push）
- [ ] runIde 本机评估：粘贴 cookie → 登录 → 提交 → 工具窗口看结果（无法自动，需用户）