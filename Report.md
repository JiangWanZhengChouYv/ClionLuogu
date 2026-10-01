# 开发记录：ClionLuogu 1.4.2 题解 / 1.5.0 打卡 / 1.6.0 AC 后清理

> 从克隆仓库到三次发版的全过程记录。第 1–7 节是 `1.4.2`（查看题解），
> 第 8–10 节是 `1.5.0`（每日打卡），第 11–14 节是 `1.6.0`（AC 后询问清理本题文件）。

## 1. 需求与既定口径

目标：在 IDE 里查看洛谷**题解**，不必切回浏览器。开工前确定的四条口径（后续未变）：

- **复用「预览」页签**做「题面 / 题解」双模式，不加第 7 个页签；
- **分页加载更多**，不做一次性全量拉取；
- **不落盘**，只在 IDE 内看，不生成 `.md`；
- 题解接口的**字段名以登录态实测为准**，不靠猜。

## 2. 环境与前期探测

- 仓库经代理克隆：`HTTPS_PROXY=http://127.0.0.1:7890 git clone …`（本机直连 GitHub 会 `Error in the HTTP2 framing layer`）。
- 本机**无独立 JDK**，Gradle 用 CLion 自带 JBR 25 运行；`settings.gradle.kts` 里有 foojay-resolver，编译期自动下载 JDK 21。
- 未登录态实测（走代理，只读 GET）得到三条关键前提：
  1. 题解数据在 `GET /problem/solution/{pid}?page=N&_contentOnly=1` 的 `lentille-context` 脚本块里 → 可复用既有解析骨架；
  2. **未登录一律 401**：`?_contentOnly=1`、纯 HTML、`X-Requested-With`、`Accept: application/json` 四种形态全被拒；
  3. 题目页 `/problem/{pid}` 的 `data` 键里**没有题解**，`problem.tutorial` 为 null → 必须走独立接口；`problem.acceptSolution` 字段存在。

## 3. 功能实现

| 文件 | 改动 |
| --- | --- |
| `api/LuoguApiService.kt` | 新增 `SolutionSummary` / `SolutionPage` 与 `getSolutions(pid, page)`；复用 `CONTEXT_REGEX`、`toNamedObjects`、`asInt/asString` 宽松解析；失败文案带 HTTP 状态码与凭据诊断 |
| `service/LuoguActions.kt` | 新增 `loadSolutions(...)`：后台线程 + 回调切回 EDT |
| `ui/PreviewPanel.kt` | 「题面 / 题解」双模式：`buildHtml` 拆成 `startHtml/endHtml` + `buildProblemHtml` / `buildSolutionHtml`；题解列表常驻可见；一次只渲染一篇正文；JCEF 缺失时退化纯文本 |
| `ui/LuoguToolWindowFactory.kt` | 注入 `previewPanel::showSolutionsFor`，各页签入口后自动切到「预览」 |
| `ui/SearchPanel.kt` / `ui/FetchPanel.kt` | 各加「查看题解」按钮（搜索作用于选中项，拉取先过 `LuoguPidValidator`） |
| `resources/marked/`、`resources/js/solution-render.js` | Markdown 渲染资产（详见第 5 节） |
| `.gitignore` | 加 `.qoder/` |
| `plugin.xml` / `gradle.properties` / `README.md` / `scripts/release.sh` | 描述与 1.4.2 变更说明、版本号、功能表与已知限制、Release 正文功能清单补题解两行 |

安全加固（顺带）：`PreviewPanel.sanitize()` 原先只剥 `<script>/<iframe>/object/embed/link` 和 `<a href>`，**不处理内联事件属性**；题解是任意用户 HTML/Markdown，故补 `EVENT_ATTR_REGEX` 清 `on*=`。

## 4. 三轮排障（本记录的主要价值）

### 4.1 「我明明登录了，却提示需要登录」

- 现象来源是我加的**本地预检查** `SecureCookieStore.hasLogin()`：它返回 false，于是显示「查看题解需要登录（到「登录」页填写…）」。
- 与事实矛盾：同一时间插件「账号」页显示头像与账号资料，而那需要带 cookie 请求 `/user/{uid}` 才拿得到；`LoginPanel` 的初始卡片读的正是**同一个存储、同一套判据**。
- 结论：预检查是最不可信的那个判据，且它把「凭据没存 / 凭据被拒 / 站点策略要求先通过本题 / 被反爬拦截」四种原因压成同一句话。
- 处置：**删掉预检查**，交给服务器判定；错误文案改为携带 `(HTTP 状态码)` + 凭据诊断（只报 `_uid` 与 `__client_id` 的**长度**，绝不输出其值）+ 403 时透服务器 `errorMessage`。删掉后请求直接 200，误报消失。

### 4.2 「已加载 8 篇」但显示「该题暂无题解」

字段名几乎全猜错。在**不接触登录态**的前提下，从两个公开来源反推出真实结构：
公开接口 `GET /article?page=1&_contentOnly=1`，以及题解路由的前端 chunk（`columba~183a77506b1246fd.js` 里能读到 `solutions.count` / `solutions.result` / `article.upvote` / `article.lid` / `article.content`）：

| 最初写法 | 真实结构 | 后果 |
| --- | --- | --- |
| 条目 `id` | **`lid`**（字符串，如 `dddotkf8`） | 条目取不到 id 被全部丢弃 → 静默显示「该题暂无题解」 |
| `num` / `pageCount` | **`count` / `perPage`**（洛谷不下发 pageCount） | 分页推不出，「加载更多」永不启用 |
| `voteCount` | **`upvote`**（另有 `voted` 表示本人是否投过） | 票数恒为 null |
| 正文是 HTML | **Markdown 源码** | 即使渲染也满是 `**` 与代码围栏 |

同时修掉一个真缺陷：**解析失败被当成「空结果」**。现在缺哪一层就把那一层的真实键名报出来（缺 `solutions` 报 `data` 键列表、缺 `result` 报 `solutions` 键列表、条目认不出报首项键列表、`count>0` 而 `result` 空也单独报）。分页改为 `hasMore = page * perPage < count`。

### 4.3 「找不到下拉框」

首版把题解选择做成了 `ComboBox`。窄侧边栏里下拉只显示当前一项、看不出还有 7 篇，等于功能不可见。
改为**常驻可见的题解列表**（`JBList` + `ColoredListCellRenderer`，`1. 标题 — 作者 ▲票数`，约 120px 高、可滚动），仅在题解模式出现，点一条只向 JCEF 注入那一篇（避免整页同时 typeset），并去掉首篇的一次重复 `loadHTML`。

## 5. Markdown 渲染管线

题解正文是 Markdown，而预览页原本是 HTML 渲染器，因此：

- 引入 **marked v12.0.2**（35KB，MIT，Copyright Christopher Jeffrey）为插件内置资产 `resources/marked/marked.min.js` + `NOTICE.txt`，与既有 MathJax 同样**离线**、不走 CDN；`README.md` 许可一节同步署名。
- 新增 `resources/js/solution-render.js`，做三件事：① 先把 `$...$` / `$$...$$` 占位（否则 marked 会把公式里的 `_`、`*` 当语法）；② `marked.parse`；③ 清洗 `<script>/<iframe>/<object>/<embed>`、内联 `on*=`、`javascript:` 链接后还原公式。
- 正文以 **JSON 字面量**内嵌（`<script id="luogu-md" type="application/json">`），并把 `</` 写成 `<\/`，防止正文里的 `</script>` 提前闭合元素。
- marked 默认**原样放行** Markdown 中的 raw HTML，所以上面的清洗是必需的，不是可选的。
- MathJax 默认跳过 `pre/code`，代码块里的 `$` 不会被误当公式。

验证：在 Node 里用**真实 marked** 跑过该脚本，9 项检查全过——行内/块级公式完整、占位符无残留、代码块 `<` 未被双重转义、`<script>`/`onerror`/`javascript:` 均被清除、链接与加粗正常生成。

## 6. 构建与发版

```bash
JAVA_HOME=/Applications/CLion.app/Contents/jbr/Contents/Home \
HTTP_PROXY=http://127.0.0.1:7890 HTTPS_PROXY=http://127.0.0.1:7890 \
PATH="/opt/homebrew/bin:$PATH" ./scripts/release.sh
```

- 产物校验通过（`id=com.jiangwanzhengchouyv.clionluogu`，`version=1.4.2`）；`dist/ClionLuogu-1.4.2.zip`（4.1M）随 tag 入库。
- 提交：`eb642ce release: ClionLuogu 1.4.2`、`042db4e chore: custom plugin repository points to v1.4.2`；tag `v1.4.2` 与 Release 已发布并 `--latest`。
- 更新通道实测：`@main/updatePlugins.xml` HTTP 200 且 `version="1.4.2"`、URL 指向 `@v1.4.2/dist/…zip`；该 zip HTTP 200、4288670 字节；jsDelivr purge 返回 `finished`。
- 凭证：本机原无任何 GitHub 凭证，本次 `brew install gh` + 用户 `gh auth login`（HTTPS）+ `gh auth setup-git`。

## 7. 教训与后续

- **不要用「本地推测」替代服务器判定**。凭据是否有效只有服务器知道；预检查既误报又抹掉了证据。凡是失败路径，文案里应带可分辨的事实（状态码、服务器原文、凭据长度），而不是一句「请登录」。
- **解析失败绝不能静默当成空结果**——这是本次最难自查的一类 bug。
- 侧边栏这类窄容器里，下拉框的「还有 N 项」不可见，等于功能消失。
- 未做/待议：`orderBy`（`weight`/`time`）排序切换未暴露给用户；`acceptSolution=false` 的题目前只如实显示服务器响应，未做前置提示；`scripts/release.sh` 的 Release 正文功能清单是写死的，功能变更时需同步。
- **`./gradlew runIde` 不能用来测本插件**：Gradle 下载的沙箱 CLion 2024.3 缺 `com.intellij.modules.jcef`，插件因 `<depends>` 未满足而整体不加载，只会浪费时间。测插件请用 Install Plugin from Disk 装进真实 CLion。

---

# 追加：1.5.0「每日打卡」

> 版本 `1.5.0`，tag `v1.5.0`，Release：
> https://github.com/JiangWanZhengChouYv/ClionLuogu/releases/tag/v1.5.0

## 8. 需求与立场

启动时查一次「今天还能不能打卡」，不能打扰太多：**只弹 balloon 通知，必须点通知里的「打卡」按钮才发请求**；
不做自动打卡、不做定时轮询，一次 IDE 进程最多提醒一次。手动入口按用户要求挂在**侧边栏标题栏**
（与「清空提交记录」并排，`LuoguToolWindowFactory` 的 `setTitleActions`），不塞 Tools 菜单。
新增：`service/PunchReminder.kt`（含 `PunchReminderActivity : ProjectActivity`）、`action/PunchNowAction.kt`
（图标 `AllIcons.Actions.Checked`——`Ok` 这个字段不存在）、`LuoguSettings` 的 `punchReminderEnabled` / `lastPunchDate`、
设置页第一个 `JBCheckBox`、plugin.xml 的 `<postStartupActivity>`（EP 接口确认为 `ProjectActivity`，方法是 `execute(project)`）。

## 9. 打卡端点：绕了一大圈才走对

1. 先按「签到」搜：SPA 的 36 条路由与 145 个 chunk 里 `签到/checkin/punch` **全部零命中** → 功能只可能在旧版首页的服务端渲染里。
2. 抓到首页内联的 jQuery 处理函数：`$("[name=punch]").click → verify=$("[name=verify]").val() → $.post("/index/ajax_punch",{verify})`，
   于是照它实现成 **POST + verify 令牌**。
3. 实测打脸两次：未打卡的登录首页 `btn=1` 但 `input[name=verify]=0`；拿空 `verify=` 去 POST，服务器回
   `{"status":400,"data":"会话超时，请刷新页面后重试"}`。中途还因正则要求属性值带引号而误判「没有令牌」
   （首页写的是裸值 `name=punch`）。
4. 真端点来自公开实现 `Hughpig/LuoguAutoPunch`（`gh search repos luogu` 找到）：
   **`GET https://www.luogu.com.cn/index/ajax_punch?_=<毫秒戳>`**，只要登录 cookie + `Referer: /` + `x-requested-with`，
   **不需要 verify、不需要验证码**。首页那个 `verify` 属于另一条带图形码的旧路径（`luogu3_pre.js` 里
   `var verify = "<img src=\"/download/captcha\" …>"`），与打卡无关。
5. 响应语义：`code` **200** 成功（`more.html` 是当日运势文案，剥标签后转述给用户）、
   **201**「今天已经打过卡了」（也记 `lastPunchDate`，当天不再提醒）、**401** cookie 失效（提示重新登录）。
   解析写成 `parsePunchResponse`：先按 JSON 取 `code`/`status`，失败再回落正则——两种信封都见过。
6. 走对之后把错路留下的东西全删了：`VERIFY_ELEMENT_REGEX`、`VALUE_ATTR_REGEX`、`tagValue()`、
   `PunchState.verify`、`FormBody` 导入，一个不留。

## 10. 发版与教训

- 发版仍走内置 `scripts/release.sh`（带 `JAVA_HOME`=CLion JBR、`HTTP(S)_PROXY`=7890）：
  提交 `1fc7086 release: ClionLuogu 1.5.0`、`334081f chore: … points to v1.5.0`，tag 与 Release 已推，
  `@main/updatePlugins.xml` → `version="1.5.0"`，zip HTTP 200 / 4310491 字节，jsDelivr purge `finished`。
- **别把页面里出现的某个变量名当成协议的必需项。**首页 JS 里读 `verify`，我就假定打卡必需 verify，
  连猜两轮判据；真正的端点是免校验的 GET。正确顺序应是先找一份能跑通的外部实现或文档，再动手写解析。
- 用户界面位置这类事别自己发挥：下拉框、Tools 菜单两次被否，最后落在标题栏与「清空记录」并排才对。

---

# 追加：1.6.0「AC 后询问是否清理本题文件」

## 11. 需求与口径

拉一题会在项目根留下 `{pid}.cpp`、`{pid}.md`、`{pid}_samples/`，AC 之后要手动删。
功能：评测**变成 AC 的那一刻**弹窗问一句要不要删掉这三样。三条已确认口径：

- **每次 AC 都问**（不按题号去重、不跨重启记忆）；
- **确认后全删**，不因 `.cpp` 有未保存改动而跳过——但弹窗必须把「未保存的修改会一并丢弃」写在脸上；
- 提供**关闭入口**，放设置页（与打卡提醒开关并排），不做成弹窗第三按钮。

## 12. 触发点：一个会连弹两个模态框的坑

落点选在 `ui/LuoguToolWindow.kt` 的 `updateSubmission(rid, status)`——`trackSubmission` 把轮询的
`onUpdate` / `onDone` 都汇到这里，且已在 EDT 语义内（模态框必须在 EDT）。

关键坑：`JudgePollingService.startPolling` 对一次 AC 会回调**两次**——先 `onUpdate(AC)`，
紧接着 `isTerminal(12)` 成立 → `finish → onDone(AC)`（JudgePollingService.kt:81-84），
两者都打到 `updateSubmission`。若判据写成 `status.statusCode == 12`，同一次 AC 会连弹两个框。
→ 改成**状态跃迁**判断：`entry.status?.statusCode != AC_CODE && status.statusCode == AC_CODE`，
且必须在 `entry.status = status` 赋值**之前**取旧值。

另一条：`loadHistory()` 恢复历史记录时是直接给 `entry.status` 赋值、不走 `updateSubmission`，
所以重启不会对着昨天的 AC 弹框——这个性质要保住，别把清理逻辑挂到加载路径上。

## 13. 删除实现与边界

`service/AcCleanupService.kt`：

- 只认项目根下三个**精确名字**（`{pid}.cpp` / `{pid}.md` / `{pid}_samples`，目录名与
  `ProblemFileGenService.sampleFolderName` 对齐），不递归搜索、不按内容匹配；三者都不存在就**不弹空提示**。
- 弹窗列出实际存在的待删项，样例目录额外报「N 个样例文件」。
- 确认后：先把目录展开成「子文件在前、目录在后」，逐个 `FileEditorManager.closeFile`，
  再在 `runWriteAction` 里按序删除（`VirtualFile.delete` 不允许删非空目录）；
  单个失败（只读/被占用）不中断其余，最后 `markDirtyAndRefresh` + 通知「已删除 N 项（M 项失败）」。
- **提交记录不动**：删的是工作区文件，`SubmissionHistoryService` 保留。

两个 SDK 签名意外（都靠编译报错发现，不是猜出来的）：

- `FileEditorManager.closeFile` 是单参 `(VirtualFile)`——我按印象试了 `(file, false)` / `(null, file)` 都被否；
- `VirtualFile.delete()` 在 2024.3 需要 `ProgressIndicator` 参数，写 `file.delete()` 报
  「No value passed for parameter 'p0'」，改成 `file.delete(null)`。

设置项 `acCleanupEnabled` 同样要记得在 `LuoguSettings.loadState` 里补赋值——这里漏过一次会重启丢值。

## 14. 验证与发版

- 冒烟清单（用户已按此在真实 CLion 验过并通过）：一次 AC 只弹一个框；删除后三类文件消失、
  提交记录仍在；同题再 AC 仍弹；点「保留」一个不少；`.cpp` 有未保存改动时确认后正常删除；
  关掉开关后不再弹且重启仍生效（验 `loadState`）。
- **测插件仍然不能用 `runIde`**（沙箱 CLion 2024.3 缺 `com.intellij.modules.jcef`，插件整体不加载），
  照旧 Install Plugin from Disk 装 `build/distributions/ClionLuogu-1.6.0.zip`。
- 发版走内置 `scripts/release.sh`（带 `JAVA_HOME`=CLion JBR、`HTTP(S)_PROXY`=7890），
  产物校验、`dist/` 入库、tag、Release、`updatePlugins.xml` 重建与 jsDelivr purge 全由脚本完成。
- 顺手修掉文档里的一处陈旧：README 还写着打卡入口在「菜单 洛谷 → 洛谷每日打卡」，实际在侧边栏标题栏。

---

# 追加：1.7.0「本地样例对拍 + 评测未通过通知」

## 15. 需求与口径

两件事都指向同一个痛点——**待在 IDE 里的时间不够长**：

- **A 本地对拍**：样例 `.in/.out` 早在拉题时就躺在 `Pxxx_samples/` 里，但要验证代码却只能提交上去
  等一轮排队评测。本地跑一遍就能知道的错，没必要走网络。
- **B 未通过通知**：提交后切去写别的代码，判成 WA/CE 时插件毫无反应（只有 AC 会弹 1.6.0 的清理框）。

四条已确认口径：新增**第 7 个页签「对拍」**（不塞进提交页）；可执行文件**自动找 + 手动兜底，并按项目记住**；
**只通知非 AC 终态**（AC 已有清理弹窗，不叠加）；失败时展示**首个差异处 + 局部上下文**，外加退出码与 stderr。

## 16. 动手前的四项核实（结论直接决定实现形状）

- **不需要任何新增 `<depends>`**：`GeneralCommandLine` / `OSProcessHandler` / `ProcessAdapter` 在 `lib/util.jar`，
  `ProgressManager` / `Task.Backgroundable` 与已在用的 `ApplicationManager` 同属 `lib/util-8.jar`，
  `FileChooser` / `Notification*` 在 `lib/app-client.jar` —— 全是 platform 核心。CLion 的 CMake API 是
  bundled module（`com.intellij.modules.clion.cmake`，藏在 `plugins/clion-ide/lib/clion-ide.jar`），**绝不碰**，
  否则插件被绑死在 CLion 上。`SampleCompareService.kt` 的类文档里写死了这句禁令。
- **`VirtualFile` 在 2024.3 没有 `isExecutable()`** → 可执行判定只能走 `java.io.File.canExecute()` + 名称精确匹配。
- **`GeneralCommandLine.withInput(File)` 是真的 `Redirect.from(file)`**（反编译 `toProcessBuilder()` 确认）
  → 不用手写 stdin 管道，天然 EOF，也不会因大输入把 64KB 管道塞死、父子互等。
- **`CapturingProcessHandler` 是无界缓冲** → 不能用它的 `runProcess(timeout)` 捷径，否则一个 `while(true) puts` 的
  本地程序能把 IDE 内存吃满。自己写 `BoundedCapture`：超 1MB 立刻 `destroyProcess()`（这正是平台自己超时用的手段）。

**工具链上的一个新发现**：`~/.gradle/jdks/eclipse_adoptium-21-*/Contents/Home/bin/javap` 是存在的
（foojay 给构建自动装的 JDK 21），以前只能用 `tr` + `strings` 刮常量池，现在平台 API 可以直接 `javap` 出
完整签名。这次靠它当场确认了 `ProcessOutputType` 是 `Key<Object>` 的子类、`FileChooser` **没有 `getInstance()`**。

## 17. 分层实现

| 文件 | 职责 | 为什么这么切 |
| --- | --- | --- |
| `api/LuoguPidValidator.pidFromFileName` | 文件名 → 题号 | 原先 `SubmitPanel` 只做 `removeSuffix(".cpp")`，`临时 文件.cpp` 会塞进垃圾初值；两处（提交/对拍）共用一个归属者，永不分叉 |
| `ui/CurrentFilePid` | 当前编辑器文件 / 题号 / 是否有未保存改动 | EDT 专用的三个薄方法，替代散在面板里的 `FileEditorManager` 拼写 |
| `storage/CompareTargetService` | 项目级记住产物路径（`clionluoguCompare.xml`） | 全 var + 默认值、`getState()` 手工深拷贝（照 `SubmissionHistoryService`）；读回**重新校验** `isFile && canExecute`，`cmake --clean` / 换机器都会让旧路径自然失效 |
| `service/ExecutableLocator` | 在 `cmake-build*` 里按约定找产物 | 纯 `java.io` 后台遍历（`walkTopDown` 深度 8、跳过 `CMakeFiles/Testing/_deps/.` 开头目录）；判据=**名称精确等于题号**（Windows 另接受 `.exe`）+ 大小 `[512B, 256MB]` + `canExecute`；多候选取 mtime 最新并在 UI 如实说「另有 N 个候选」；`extraPathEntries` 把产物目录注入子进程 `PATH`（Windows MinGW 运行时 DLL 需要）。**不用 VFS**：`cmake-build-*` 动辄上万文件，`VirtualFile.children` 会触发刷新且需读动作 |
| `service/SampleSetService` | 发现样例 | 目录/文件名与 `ProblemFileGenService` 逐字对齐；**按 n 数值升序**（字典序会把 `_10` 排到 `_2` 前）；单独报「有 `.in` 无 `.out`」的编号 |
| `service/SampleDiff` | 规范化 / 首个差异 / 上下文渲染 | 纯对象、零 IDE 依赖。`normalize()` 只做「每行 rstrip（含 `\r`，挡 CRLF）」+「砍尾部连续空行」，**不做**行首折叠或 token 化——洛谷不忽略，跟着忽略就会把真错判成对；超 1MB 直接拒读；单行超 200 字符行内截断（否则 `lineWrap=false` 的面板会被一行 1MB 浮点串撑爆）；stdout 与 `.out` 都显式 UTF-8 |
| `service/SampleCompareService` | 进程 + Task + 判定 | 见下 |
| `ui/SampleComparePanel` | 第 7 页签 | 见 §20 |
| `service/JudgeNotifyService` | 非 AC 终态通知 | 见 §21 |

**判定顺序互斥**（`runOne`）：`overLimit → OUTPUT_TOO_MUCH`、`timedOut → TIMEOUT`、`cancelled → NOT_RUN`、
`exit != 0 → RUNTIME_ERROR`、`.out` 过大 → `ERROR`、有差异 → `MISMATCH`、否则 `PASS`。
`exitCodeHint` 把 Windows 两类非零码分开写：`-1073741515`(0xC00007B) 说「缺运行时 DLL，MinGW 的 libstdc++/libwinpthread 不在 PATH 上」，
`-1073741819`/`139` 说段错误，`134` 说 SIGABRT。不分开的后果是 Windows 首次体验就「插件乱报 RE」。

**为什么用 `Task.Backgroundable(interactive=false)` 而不是裸 `executeOnPooledThread`**：要能取消（裸线程被中断只断 Java 线程，
**子进程变孤儿继续吃满一核**）、要进度可见（N 组 × 5s）、`onSuccess` 由平台保证在 EDT 派发、构造上保证后台线程不弹模态。
「停止」按钮只置 `AtomicBoolean`，真正的 `destroyProcess()` 发生在后台线程的等待循环里——**不在 `onCancel` 里 kill**，那是 EDT。

## 18. 离线验证：64 条断言抓出两个真 bug

沙箱不能 `runIde`（缺 JCEF），但这次刻意把 `SampleDiff` / `ExecutableLocator` / `SampleSetService` / `SampleCompareService.runOne`
写成**无 Project 依赖**的形态，于是能直接拿构建产物在 JVM 里跑断言：

- 纯逻辑（`DiffProbe`，52 条）：题号推断（含 `临时 文件.cpp`/`main.cpp`/`P1001.tar.gz`/纯数字都回 null）、
  CRLF 与行尾空格与尾部空行仍判 ✓、行首缩进与中间空行**必须**判 ✗、首个差异行号、缺失/多余两种文案、
  上下文半径与 200 行上限与单行截断、`.out` 超 1MB 拒读、UTF-8 读回中文、样例**按数值排序**（`_13` 排在 `_2` 后而非 `_10` 前）、
  孤儿输入、产物定位的六类排除（`.pdb`/`.o`/`.ilk`、<512B、不可执行、非 `cmake-build*` 目录、`CMakeFiles` 内）、
  多候选取最新、`P3003.exe` 命中。
- 进程链路（`ProcessProbe`，12 条）：`withInput` 真把 `.in` 喂进 stdin（`read a b; echo $((a+b))` 判 PASS）、
  期望不符判 MISMATCH 且带行号与两侧上下文、中文 stdout 按 UTF-8 判 PASS、`exit 3` 判 RE 且 stderr 单独收到、
  `while true; do :; done` 判 TIMEOUT 且 **1.5s 超时后 4s 内返回**、刷屏 3.4MB 判 OUTPUT_TOO_MUCH（超 1MB 即被杀）、
  `.out` 5.5MB 判 ERROR 而不读入内存、停止标志置位判 NOT_RUN 且 **`pgrep -f` 查不到残留进程**。

两个只有跑起来才会暴露的 bug：

1. **`SampleDiff.contextBlock` 行号错位 + 越界**：循环里写成 `lines[i]`，而 `i` 是 1 起的行号 → 标签 300 显示的是 `line-301`；
   更要命的是差异落在**最后一行**时（期望 8 行、实际只输出 3 行 → `Issue.line=4`，或 `at == lines.size`）直接
   `IndexOutOfBoundsException`，异常在 EDT 回调里炸掉整行渲染。改成 `lines[i - 1]`，并补了两条边界断言（`at=500`、`at=1`）。
2. **平台把「命令行回显」混进了输出流**：`ProcessAdapter.onTextAvailable` 的 `outputType` 除了 stdout/stderr，
   还会有 `ProcessOutputType.SYSTEM`——`OSProcessHandler` 会先推一条 `[system]` + 可执行文件绝对路径。
   原写法「非 stderr 即 stdout」让**每一组**的实际输出都多出一行产物路径，本地全判 MISMATCH。
   现在按 `ProcessOutputType.isStdout/isStderr` 双向判定，其余类型直接丢弃。
   （`ProcessOutputType` 本身就是 `Key<Object>` 的子类，`getStdout()/getStderr()` 是 `ProcessOutput` 的**实例文本访问器**、
   不是流 `Key`——一开始照印象写 `ProcessOutput.getStderr()` 编译不过，`javap` 一看就明白了。）

顺带纠正一个印象错误：**`FileChooser` 是纯静态类，没有 `getInstance()`**（2024.3 只有
`FileChooser.chooseFile(descriptor, project, toSelect)` 等 8 个静态方法）；`title` 得用 `withTitle(...)` 链式设置；
`createSingleFileOrExecutableAppDescriptor()` 正好是「选可执行文件」的现成描述符。

最后一条留档：探针 JVM 跑完不退出，`kill -QUIT` 出来的线程转储显示挂住的是平台共享的
`"I/O pool 1/2/3"`（非 daemon，`ProcessHandler` 的读流池），11 次跑进程**总共只有 3 条**、没有按次累积，
所以不是我们的 handler 泄漏，IDE 里由 Application 统一收尾，不需要额外 `handler.destroy()`。

## 19. 题号推断收口

`SubmitPanel.guessedPid()` 改成一行 `CurrentFilePid.guess(project)`：`P1001.cpp` 行为不变，怪名字回落。
`LuoguPidValidator` 里 `pidFromFileName` 用 `substringBeforeLast('.', name)`——`P1001.tar.gz` 得到 `P1001.tar`，
过不了格式校验返回 null，不会误认成题号。

## 20. 对拍页 UI 与装配

`ui/SampleComparePanel.kt`（第 7 页签）：

- NORTH 三行：`题号 + [重新查找] [选择可执行文件…]` / `exeLabel` / CENTER 是
  `JBSplitter(false, 0.35f)`（照评测页）左 `JBList<Row>` 右 `JTextArea`（MONOSPACED、`lineWrap=false`）。
- **配色不改 `VerdictColors.kt`**：`Verdict` 映射到既有状态码后调 `verdictColorOf`
  （`PASS→12` 绿、`MISMATCH→6` 红、`RUNTIME_ERROR→7` 紫、`TIMEOUT→5` 蓝、`OUTPUT_TOO_MUCH→3` 蓝、其余 null 灰），
  与评测页视觉一致，也不用新增一套颜色常量。不复用 `TestCaseSquares`（那是密集网格，放不下「第 7 行不同」这种文案）。
- 缺失提示一律**内联、不弹窗**：题号推不出 → 空输入框 + 提示手输；没有样例目录 → 详情区打印认的路径 + **「去拉取」按钮**
  （`LuoguTabs.openTab(TAB_FETCH)`）；缺 `.out` → 该行「缺少期望输出」；找不到产物 → 把「按哪四条顺序找过、在哪个目录找」写出来并引导手动选。
  与计划的小偏差：「去拉取」放在下方面板条而不是详情区里——详情区是 `JTextArea`，塞不进按钮，改成文案指一句「点下方」。
- **唯一的模态弹窗**只在用户刚点「开始对拍」、且产物确实比 `Pxxx.cpp` 旧或编辑器有未保存改动时出现一次
  （`Messages.showYesNoDialog`，「继续对拍 / 先去 Build」），切页签、探测、缺失都不弹。
- 定位优先级：本窗口手选 → 该题号项目级记住 → `cmake-build*` 自动找 → 本项目最后一次手选；
  手选一次即 `rememberExePath(pid, path)`，重启仍记得。
- `addNotify()` 自动重新探测（刚 Build 完产物时间会变，不该让用户记得手动刷新），
  正在对拍或已有探测在飞时跳过（`probing` 标志，避免两条回调互相覆盖）。
- 装配：`LuoguTabs.TAB_COMPARE`；把 `ClearHistoryAction` 里那 4 行取窗口的写法收拢成 `LuoguTabs.evalWindow(project)`（两处共用）；
  新增 `action/CompareAction`（克隆 `SubmitCodeAction`）注册进 洛谷 组 + 编辑器/项目视图弹出菜单；
  **`setTitleActions` 保持两个按钮不变**（打卡与清空记录才是标题栏级的手势）。

## 21. 评测未通过通知

挂点仍在 `updateSubmission` 的 `runOnEdt` 块内、**`entry.status = status` 之前**取旧值，三条判据缺一不可：

```kotlin
val reachedAc = status.statusCode == AC_CODE && entry.status?.statusCode != AC_CODE
val failedFirstTime = JudgePollingService.isTerminal(status.statusCode) &&
    status.statusCode != AC_CODE &&            // 12 也在 TERMINAL_CODES 里，必须显式排除
    !JudgePollingService.isTerminal(entry.status?.statusCode)   // 跃迁守卫：一次终态会回调两次
```

- 文案：`$pid ${statusText}` + 记录号 + 用时/内存 + **首个未通过测试点**（走 `subtaskResults`，编号 `id+1` 与评测页方块一致；
  `detail` 不下发时回落 `subtaskInfo`）+ CE 时编译错误首行。
- `NotificationType.ERROR` + `NotificationAction.createSimpleExpiring("查看")` → `openTab(TAB_EVAL)` 后
  `invokeLater` 调新增的 `LuoguToolWindow.selectSubmission(rid)`，它只设 `selectedIndex` + `ensureIndexIsVisible`，
  渲染完全复用列表监听器 → 零新增渲染代码。
- `JudgeNotifyService` 内**严禁 `Messages.*`**（会与 AC 清理模态抢 EDT）；`loadHistory()` 不走 `updateSubmission`，
  所以重启不会把昨天的 WA 重放成通知——这个既有性质继续保住。
- 设置项 `judgeNotifyEnabled`（可空 Boolean + 默认 true）+ **`loadState` 补一行**（1.5.0/1.6.0 都在这里踩过），
  `LuoguConfigurable` 第三个 `JBCheckBox` 并同步 `isModified`/`apply`/`reset` 三处（漏 `isModified` 表现为「改了不脏、OK 灰着」）。

## 22. 元数据与待验证清单

`gradle.properties` → 1.7.0；`plugin.xml` description 加两条 `<li>` + change-notes 顶部 `<b>1.7.0</b>`
（条目内**不再嵌套** `<li>/<ul>`，`release.sh:94` 用非贪婪 `<li>(.*?)</li>` 切分会截断）；README 的「6 个页签」→ 7、
功能表插「对拍」行、使用步骤拆成「写码 → 先对拍 → 提交 → 看评测」并顺延、已知限制补四条（不触发构建、不做 SPJ、
按约定找产物、项目需已落盘）；`scripts/release.sh` 里写死的页签数与功能清单同步改掉。`updatePlugins.xml` 仍由脚本重建。

`buildPlugin` 通过（zip 4.4MB，plugin.xml 里 `<version>1.7.0</version>`，`<depends>` 仍只有 platform + jcef）。
**仍需用户在真实 CLion 验**：P1001 Build 后全绿 → 改错一行重新 Build 出现「第 N 行不同」且行号对；10 组以上不按字典序；
`.out` 尾部塞空行仍判 ✓；死循环显示超时且活动监视器无残留；`1/0` 显示 RE + 退出码 + stderr；只清 `cmake-build-*` →
提示未找到且「开始」禁用、手动选一次后**重启仍记得**；改 `.cpp` 不保存 → 提示「早于源码 / 未保存」且点开始才弹一次；
提交一份 WA → balloon **只出现一次**、点「查看」跳评测页并选中该 rid；提交 AC → 只有清理弹窗、没有 balloon；
关掉开关 → 不弹且重启仍生效；清空记录后点旧通知的「查看」→ 只切页不崩。

## 23. 第一轮实测后的修复：对拍页「输不了题号、也不会自动分析」

他装了 1.7.0 实测：**未通过通知正常**，但对拍页「输不了题号，也不能自动分析」。
先查 `~/Library/Logs/JetBrains/CLion2026.2/idea.log`（加载的确实是 1.7.0，且**没有任何插件异常**）
→ 说明是布局/交互问题而不是崩溃。两个根因都是我的：

1. **嵌套 `FlowLayout` 在窄侧边栏会把折行的第二行整块裁掉**。我把「题号 label + 输入框」和
   「重新查找 + 选择可执行文件…」各塞进一个 `FlowLayout` 行，而 `FlowLayout.preferredSize`
   **只按单行算高度**：容器放不下时内容确实折行了，但父容器仍只有一行高 → 折出去的那半截
   既看不见也点不到。仓库里 `WrapLayout` 存在的唯一理由就是补这个高度计算，我没用它。
   离线用纯 Swing 复刻同一棵容器树、把宽度压到 240 / 170 / 150 实测（`LayoutProbe`）：
   240px 下「选择可执行文件…」`y=55` 而容器高正好 55 → **被裁掉**；同形态换 `WrapLayout` → 分配高度 96、全部可见；
   170px 下连输入框都折进第二行（父行只有 26px 高）→ 就是他说的「输不了题号」。
   最终形态改成**每行一个组件、整行铺满**（题号 label、输入框、按钮行、产物 label、提醒 label 各一行），
   按钮行用 `WrapLayout`；150px 宽实测零裁掉。底部（开始/停止/去拉取 + 状态）同样从
   `FlowLayout` + `BorderLayout.WEST/CENTER` 换成 `WrapLayout` 行 + 整行状态，避免同一类裁切。
2. **题号只在构造时猜一次**。工具窗口内容在启动时就建好，那时他还没打开任何 `.cpp`，`pidField` 一直是空的；
   之后每次进页签 `probe()` 都因「题号为空」直接返回——这就是「不能自动分析」。
   改成 `syncPidFromEditor()`：进页签 / 点「重新查找」/ 输入框失焦时，若输入框为空**或内容仍是上次自动填的那个值**
   （用 `autoPid` 记着）就跟当前文件走；手输的题号不会被切页签冲掉。

顺带补的四处：

- 输入框**失焦即自动分析**，不必再点「重新查找」。
- `CompareTarget` 加 `exeSource` 枚举：窄标签里只显示「自动查找 / 本题记住 / 本窗口手选 / 沿用上次」，
  完整说明与绝对路径进 tooltip 与详情区；`另有 N 个候选`、`产物早于源码约 N 分钟`、`编辑器有未保存改动`
  挪到独立的 `warnLabel` 行（橙色），不再挤成一行被截断。
- **`startCompare` 加 `targetPid != pid` 守卫**：分析在后台线程，改完题号马上点「开始对拍」会拿上一轮的
  样例/产物去跑——属于会给出错误结论的那类 bug；现在提示「正在按新题号重新分析」并重发探测。
- `CompareTask` 补 `onFinished()` 兜底：`run()` 抛异常或中途取消时不走 `onSuccess`，少了这句面板会永久卡在
  「对拍中」（开始禁用、停止也已禁用，等于整页死掉）。

另外清掉一个 `when is exhaustive so 'else' is redundant` 警告（`Result.verdict` 非空，`summaryOf` 的
`else -> ""` 是死分支）。这一轮 zip 仍叫 1.7.0，靠**布局本身就是指纹**区分新旧包：新包题号输入框独占整行、
按钮行会真的折行；旧包挤在一行且缺「选择可执行文件…」。

---

# 追加：1.7.0 第二轮返工——对拍改成「插件自己编译」

## 24. 为什么把整条找产物链路删掉

他的结论很直接：**「别找可执行文件了，没用，找不到，改成自己编译代码吧」**。
找产物这条路的前提是「该题正好是一个 target、且产物名 == 题号、且他先手动 Build 过」，
三个条件在他真实项目里凑不齐；而且「插件让你先去 Build 再回来点按钮」本身就是把最该省的那步留给他做。

动手前先量了本机环境（四条都是实测，不是印象）：

- `/usr/bin/clang++` = **Apple clang 21.0.0**；PATH 上 `clang++/g++/c++/gcc/clang` 全指向 `/usr/bin`。
- **CLion 没有可复用的编译器**：`/Applications/CLion.app/Contents/bin/clang/mac/aarch64/bin` 里只有
  `clangd`、`clang-tidy`、`clazy-standalone`、`llvm-symbolizer`——一个也不能拿来编代码。
- **`bits/stdc++.h` 不存在**：`#include <bits/stdc++.h>` 实测 `fatal error: 'bits/stdc++.h' file not found`；
  `/usr/include/c++/*`、`CommandLineTools/usr/include/c++/*`、`/opt/homebrew/include/c++/*` 三处 glob 零命中。
  clang 的头搜索路径（`-###` 打出来的）是 `-I/usr/local/include` → SDK 的 `usr/include/c++/v1` →
  `usr/lib/clang/21/include` → `usr/include`。
- **机器上没有别人的适配可以复用**：`/usr/local/include/bits`、`~/CLionProjects/**`、本工作区都搜过，
  也没有任何 `include_directories()`。所以兼容头只能插件自带。

## 25. 兼容头：先筛表，再套 `__has_include`

`resources/compat/bits/stdc++.h` 的内容不靠抄，靠**逐个问本机 clang**：
`printf 'int main(){}' | clang++ -std=c++17 -fsyntax-only -x c++ -include <头> -`，104 个候选里 101 个可用，
没有的是 `climit`、`scoped_lock`（libc++ 把它放在 `<mutex>` 里）、`malloc.h`。
我自己起草时还多写了四个根本不存在的名：`cstrings.h`、`multiset`、`multimap`、`promise`（后两个分别是
`<set>`、`<future>` 的内容），同样被这条筛子抓出来。

生成后又加了一层保险：**每一项都包 `#if __has_include(<头>)`**。理由是别的 clang/libc++ 版本可能缺我这台机器上有的头，
而兼容头自己把一份本来能编过的代码弄挂，是最难查的那种 bug。最终 95 项、305 行。

注入条件是**探测结果**而不是平台判断：`needsBitsShim()` 用一次 `-fsyntax-only` 的纯语法检查问编译器
「你有没有 bits」，没有才 `-isystem <临时目录>`。这样 g++/MinGW 走它自己的真头，我们的 shim 完全不参与，
不会遮蔽真头（这是当初最容易翻车的点）。

## 26. 代码上的取舍

- **删掉**：`ExecutableLocator`、`CompareTargetService`（连 `clionluoguCompare.xml` 一起作废，不做兼容读取——
  没人发布过带它的正式版）、`CompareTarget` 的 exe 四优先级与 `ExeSource`、UI 的「选择可执行文件…」和三行产物状态。
  `CurrentFilePid.hasUnsavedChanges` 也删了：现在编译前直接把那一个文件写盘，「提示他保存」变成多余。
- **`CompilerService`**：`detect()`（覆盖路径 → PATH → IDE 自带 MinGW）、`versionOf()`（`--version` 首行，按路径缓存）、
  `needsBitsShim()`、`compile()`。命令是 `<compiler> <设置里的参数> [-isystem shim] -o <产物> <源文件>`，
  上限 120 s / 512 KB，**诊断走合并流**（`withRedirectErrorStream(true)`，编译器的话本来就在 stderr）。
  仍然只用 `GeneralCommandLine` + `OSProcessHandler`，类文档里写死「不碰 CMake / CLion 工具链 API」。
- **编译挪进同一个 Task**：`SampleCompareService.Request.compile` 非空时，`CompareTask.run()` 先编译，
  **编不过就一组都不跑**、直接收尾并报诊断。这样只有一个进度条目、一次取消，`停止` 对编译同样生效。
- **产物先删再编**：`compile()` 起手 `output.delete()`。上一次失败留下的旧二进制如果被接着跑，
  给出的是一个**完全错误的结论**——这条有专门断言（先编成功、再把源码改坏重编，产物必须不存在）。
- **临时目录而不是项目里**：`Files.createTempDirectory("clionluogu-compare")`，不污染他仓库、
  不会被 1.6.0 的 AC 清理误删、也不会被 `cmake --clean` 干掉；`PATH` 额外注入编译器所在目录（MinGW 的 DLL 在那儿）。
- **设置放应用级**：`compareCompilerPath` / `compareCompilerArgs`（默认 `-std=c++17 -O2 -w`）——
  编译器是机器级的事，跟项目无关。两处 `loadState` 补上，`isModified` 里对空参数框做了
  「空 = 默认值」归一，否则「填回默认」会被判成已改动、OK 按钮永久脏着。
- **对拍页一个模态弹窗都不剩**：跑的是刚编出来的东西，不存在「产物比源码旧」还要问一句的情况；
  编辑器里那份没保存就直接把那**一个文件**写盘，状态栏老实写「已保存 P1001.cpp」。

## 27. 离线验证又抓到两件事

74 条断言（39 纯逻辑 + 23 新编译链路 + 12 进程行为），全部在我这边跑完，不占他时间。
其中两条是写探针时才暴露的：

1. **兼容头没释放时，插件会静默退化成「让 clang 报 not found」**。第一次跑 CompileProbe 就是这副样子：
   `needsBitsShim` 判对了（true），但 `shimInjected=false`，用户看到的是
   `'bits/stdc++.h' file not found`，会以为是插件没适配洛谷的写法。根因是探针 classpath 少了
   `build/resources/main`（资源在 IDE 里由 jar 提供），但产品代码那个静默分支是真问题——
   现在 `needsShim && shimDir == null` 直接返回一条明确诊断：临时目录不可写 / 请改用带 bits 的编译器。
2. **`cout << __int128` 在 libc++ 上编不过**（`use of overloaded operator '<<' is ambiguous`），
   而洛谷的 g++ 有那个重载。这是本地与评测机的真实差异，我把它做成一条显式断言
   （「已知差异：…诊断要说清楚」），并写进 README 的已知限制，而不是让它悄悄变成"用户代码错了"。

断言还覆盖：自动找到 `/usr/bin/clang++` 且版本串含 Apple clang；不可用覆盖路径被挡掉并回落、
`/usr/bin`（目录）也不算编译器；shim 判定 + 释放 + 缓存；一段用了 `gcd`/`bitset`/`unordered_map`/`__int128`
的 OI 代码**编译→跑样例→判 PASS**，期望改一个数字→MISMATCH 且定位到第 1 行；语法错→失败且诊断带 `error:` 与行号、
旧产物已删；多文件题→「undefined」链接错；`-std=c++14` 下 `std::gcd` 编不过（证明设置参数真的传进去了）、
改回 17 又过；停止置位时不报成功。最后用**发布包里的 jar** 重跑一遍 23 条全绿，
并确认 `compat/bits/stdc++.h`（6495 字节）确实从 jar 里被释放出来——这条路径就是真实 CLion 会走的路径。

编译耗时：探针侧 407–428 ms（含 bits 全量 + `-O2`），所以每次点都重编、不做 mtime 缓存是对的。

## 28. 待他在真实 CLion 验

进对拍页 → 编译器行显示 `编译器：clang++（Apple clang version 21.0.0 …）`；打开 `P1001.cpp`
**改了不保存**直接点「编译并对拍」→ 状态栏出现「已保存 P1001.cpp」且跑的是新代码；
故意一个语法错 → 第一行红、右侧完整 `error:` 诊断、其余组写「未执行（编译失败）」；
`Settings | Tools | 洛谷拉题` 里把参数改 `-std=c++14` 后重编立刻生效；编译器填一个不存在的路径 →
提醒行说清「设置里的编译器不可用，已回落到 …」；删掉 `Pxxx.cpp` 只留样例 → 说「没东西可编」并显示它找的路径。
通知那半（上一轮已验过）不受影响。

## 29. 第三轮修复：文件明明存在，却报「没东西可编」

他实测立刻撞上：`/Users/markzhang/CLionProjects/HomeWork/P1001.cpp` 明摆着在（173 字节），
界面却说「找不到源文件：<那个真实路径>」。`ls` 一验文件确实在——根因是一行写反的判据：

```kotlin
val missingSource = t.sourcePath?.let { "找不到源文件：$it" }   // 错：sourcePath 非空 = 文件存在
```

`CompareTarget.sourcePath` 非空表示**找到了**，我把它当成「缺失」的证据，于是文件在→阻塞、文件不在→放行，
整条 UI 判断正好反了（而且文案里打印的还是那个真实存在的路径，看起来格外荒谬）。
状态栏那半的 `t.sourcePath == null -> "项目根没有 $pid.cpp"` 是对的，两处重复同一串条件、一处写反——
这解释了为什么它躲过了编译和代码审读。

修的时候顺手把这串判据从 Swing 里搬出来：`SampleComparePanel.blockingReason(pid, target, fallbackBase)`
（`@JvmStatic`，只吃 `CompareTarget`，不碰 `Project`/组件），返回 `Block(short, detail)` 或 null，
状态栏文案与详情区文案由同一个分支给出，不再有两处条件。这样它就能离线断言，7 条全绿：

- 五种阻塞各归各位（没落盘 / 没样例目录 / 目录里没成对样例 / 项目根没源文件（详情要带上找的路径）/ 没编译器）；
- **三样都齐 → 返回 null**（这条就是今天的回归断言）；
- 再拿他**真实项目**只读跑一遍：`源文件存在=true 成对样例=1 目录=true → 可以开始`。

教训一条：凡是「可空字段表示在不在」的判断，写的时候就要顺手补一条「齐全时为 null」的断言，
否则判反只会在界面上变成一句看起来很荒谬的假话，编译器一声不吭。
另注：他那项目里 `P1001` 只有 1 组成对样例，所以列表里就 1 组，不是漏了。


