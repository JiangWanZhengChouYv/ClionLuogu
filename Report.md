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

## 30. 第四轮：bits 根本没机会跑——两个只在真 IDE 里炸的线程/进度问题

他又报「还是不行」。先查 `idea.log`（20:29:03 加载的确实是新 1.7.0），两条栈把原因钉死了，
**都跟 bits 无关**：编译那一步压根没被执行到。

1. `java.lang.IllegalStateException at AbstractProgressIndicatorBase.setFraction`
   ← `SampleCompareService$CompareTask.run(SampleCompareService.kt:119)`。
   平台的 `ProgressIndicator` **默认是 indeterminate**，此时赋值 `fraction` 直接抛。
   我进 `run()` 第一行进度就写 `indicator.fraction = 0.05`，于是任务一进去就死，
   `onFinished()` 兜底把空结果报回面板 → 界面表现就是「什么都没跑/又变回未对拍」。
   → `run()` 开头补 `indicator.isIndeterminate = false`。
2. `SEVERE ThreadingAssertions - Read access is allowed from inside read-action only`
   ← `SampleComparePanel.saveDocumentIfModified(:402)`，即 EDT 上裸调 `FileDocumentManager.getDocument(vf)`。
   点「编译并对拍」在**保存那一步**就抛，编译自然不会发生。
   → 整段收进**一个 `runWriteAction`**（写意图自带读意图）：`findFileByIoFile / isFileModified /
   getDocument / saveDocument` 全放进去，并且只有真写了盘才回那句「已保存 P1001.cpp」。

为什么离线 81 条断言一条都没抓到，值得记下来：

- 离线探针用的是自己造的 `ProgressIndicator` 动态代理，`setFraction` 是个 no-op，永远不会抛；
  真 IDE 走 `ProgressWindow`，indeterminate 这条规则是硬的。
- `ThreadingAssertions` 只在有 `Application` 的 IDE 里开启，`java.awt.headless` 的裸 JVM 完全没有。

结论：**进程/纯逻辑可以离线验，进度与 EDT/VFS/Document 语义只能靠真 IDE**。
凡碰 `ProgressIndicator`、`Application`、VFS/Document 的代码，必须在真实 CLion 里点一遍才算验过。

顺手把上一轮批准但还没落地的「点左侧列表结果退回未对拍」一起修了，根因同一族：
`pidField` 的 `focusLost` 无条件 `probe()`，而**点列表正是让输入框失焦的动作** →
`applyTarget` 又无条件 `listModel.clear()` 重建 → 一轮结果当场蒸发（切页签回来同理）。
两层修法：① `probeIfStale()`——回车/失焦只在题号变了（或还没分析过）时才重探；
② `applyTarget` 重建前按 `sampleIndex` 快照旧行，**同题号**时把 `verdict/summary/detail` 搬回来
（编译行 `COMPILE_ROW` 也在快照里，要重新插回第 0 行，否则会被样例行重建挤掉），
状态栏改显示「上次结果 · …」且**不覆盖**右侧详情；只有新一轮 `startCompare` 才显式清空。
判据抽成 `@JvmStatic preservesResults(previousPid, currentPid)`，`UiLogicProbe` 补 3 条断言
（同题号保留 / 改题号清空 / 首次不保留），共 10 条全绿；zip 重新构建（10-02 09:21）。

「重新查找样例」在题号不变时不再清空结果是有意的：它现在的语义是**重新读盘**（补了 `.out`、
改了编译器设置、换了当前文件），结果只有下一次「编译并对拍」才更新；新出现的编号天然还是「待对拍」。

## 31. 第五轮：按用户要求撤掉 bits 兼容头，改模板

他的决定很直接：**「算了，删除所有的关于 bits 的代码，拉题只有 iostream，vector，algorithm」**。
兼容头那套（探测 + 释放 + `-isystem` 注入）本质是在替用户兜住一个坏习惯，多一个子进程、多一条注入路径、
多一类「兼容头自己缺项」的风险；不如让模板直接写真实存在的头。

删掉的：`resources/compat/bits/stdc++.h`（305 行）、`CompilerService` 里的 `SHIM_RESOURCE` /
`probeBits()` / `needsBitsShim()` / `ensureShimDir()` / `bitsProbeCache` / `Outcome.shimInjected`、
面板详情区那句「兼容头：已注入…」、`createTempDir()`（唯一使用者没了，一并删）。
`compile()` 现在就是 `<compiler> <设置参数> -o <产物> <源文件>`，少一次子进程，编译从 428 ms 降到 **328 ms**。

新 `DEFAULT_CODE_TEMPLATE`：先按「两边都有的常用头」列了 21 个（`algorithm bitset climits cmath cstdio cstdlib cstring
deque functional iomanip iostream map numeric queue set stack string unordered_map unordered_set utility vector`），
**逐个用 `clang++ -fsyntax-only -include <头>` 实测过在 libc++ 上都在**（`climit`、`scoped_lock`、`malloc.h`
这类「看着像其实没有」的名没进来）。他看完直接否掉：**「太多啦，就留三个最常用的」**——最终模板只有
`<iostream> <vector> <algorithm>`，用到别的自己在设置里加。探针因此多了一条
「模板只含 3 个 include」的断言，防它以后又膨胀。
`ProblemFileGenService` 取的就是 `settings.codeTemplate`，所以只在用户没自定义模板时生效——自定义过 bits 的仍按他自己的。

老文件不能不管：项目里已经存在的 `Pxxx.cpp` 第一行就是 bits。现在不塞兼容头，本地必然
`fatal error: 'bits/stdc++.h' file not found`。所以 `compile()` 里加一句 `withBitsHint()`：
诊断文本一出现 `bits/stdc++.h` 就在末尾补一段可操作的说明（换成哪些头、新模板已经改了）。
探针里对应的断言从「shim 注入成功」换成两条更值钱的：
**「插件默认模板原样编过」**（拿 `LuoguSettings.DEFAULT_CODE_TEMPLATE` 真编一遍）和
**「老文件留 bits 会失败，且诊断里带那句提示」**。

### 我自己这轮写坏了一次文件，记下来

改 `plugin.xml` 的措辞时用 python `s.find(...)` 定位结尾锚点，锚点字面写错返回 **-1**，
`s[:i] + new + s[-1:]` 直接把 change-notes 之后的 `<depends>`、扩展点、`<actions>`、闭合标签**全截没了**
（84 行变成 23 行）。编译期没人报错，是 `wc -l` + `tail -2` 一眼看出来的。
处理：`git checkout -- plugin.xml` 回到提交版本（这轮未提交改动只有措辞，正好重写），
再用 Edit 工具按精确字符串分两处替换。教训：**改文件别用「找索引再切片」，用带精确上下文的编辑工具；
真要脚本改，`find` 返回 -1 必须当场退出**，以及批量文本改完之后要立刻验证结构（行数、闭合标签、条目数）。

离线断言 86 条全绿（25 编译链路 + 10 UI 判据 + 39 纯逻辑 + 12 进程），
其中 25 条是拿**发布包里的 jar** 跑的，顺带确认 `compat/` 已经从 jar 里消失（`grep -c compat` = 0）。
zip 重新构建于 09:46。README / `plugin.xml` description + change-notes / `release.sh` 三处措辞同步改掉，
已知限制新增一条明说「bits 不做兼容，老文件自己换头」。
另注：他那项目里 `P1001` 只有 1 组成对样例，所以列表里就 1 组，不是漏了。

## 32. 1.7.0 发版

用户确认测试完成后，照内置 `scripts/release.sh` 一路走完（`JAVA_HOME`=CLion JBR、`HTTP(S)_PROXY`=7890、
`PATH` 带上 `/opt/homebrew/bin` 好让脚本找到 `gh`）：

- 产物自检 `com.jiangwanzhengchouyv.clionluogu 1.7.0`（jar 名与内嵌 `<version>` 都对得上才继续）；
- zip 入库 `dist/ClionLuogu-1.7.0.zip`（4.2M）→ main 推到 `16a1c4a`，tag `v1.7.0` 推上去；
- Release 建立并置为 latest：<https://github.com/JiangWanZhengChouYv/ClionLuogu/releases/tag/v1.7.0>
  （正文「本次更新」是从 `plugin.xml` 的 change-notes 里按 `<b>1.7.0</b>` 抓的，两处天然一致；
  「功能一览」那段是脚本里写死的，本轮已把 7 个页签与「现场编译 + 三个头的模板」同步改掉）；
- `updatePlugins.xml` 由脚本从**构建产物**取权威元数据重建（id / version / 描述 / 本版变更说明），
  自带 XML 重新解析与断言，推到 `625b04c`；
- jsDelivr purge 返回 `status: finished`，Cloudflare 与 Fastly 两家都刷了。

**发布后我自己复核更新通道**（不信脚本的 echo，只信抓回来的数据）：

- `.../ClionLuogu@main/updatePlugins.xml` → `version="1.7.0"`，
  `url=".../ClionLuogu@v1.7.0/dist/ClionLuogu-1.7.0.zip"`（tag 固定，不受 main 后续提交影响）；
- 该资产 URL `curl` → **HTTP 200，4415706 字节**；
- 把线上那份 zip 拉回来拆开验：内层 `plugin.xml` 是 `<version>1.7.0</version>`，
  jar 里 `compat` 条目数 **0** ——确认发出去的是「撤掉 bits、模板只留 iostream/vector/algorithm」那一版，
  不是早先带兼容头的构建；
- `gh release view v1.7.0` 附件列表含 `ClionLuogu-1.7.0.zip`。

一点提醒：本轮 zip 与之前未发布的构建同为 1.7.0（`v1.7.0` tag 之前不存在，所以没有覆盖问题）；
以后再遇到「同一版本号反复构建」，要么 bump，要么就像这轮靠行为差异认包
（这轮的指纹：对拍页有「编译器：…」那一行，点「编译并对拍」会先出编译行）。

## 33. 1.7.1：存反例、只看失败、分栏自动换向

他自己点的单（选项 2 + 4），并明确「这两个是 1.7.1」，不是 1.8.0。三件小事，每件都有一个不是那么显然的取舍：

### 存反例：字节必须不改

`CaseExportService` 把失败那组写到项目根 `{pid}_cases/`：`{pid}_{N}.in`、`{pid}_{N}.expected.out`、
`{pid}_{N}.actual.out`（+ 有 stderr 时再多一个 `{pid}_{N}.stderr.txt`）。

- **为什么还要做**：详情区的差异上下文只截 ±20 行、单行截 200 字符 —— 那是给眼睛看的。想喂调试器或
  本地重跑，必须有一份完整的输入与实际输出。所以 `SampleCompareService.Result` 新增 `actualOutput`，
  **只在失败组留**（通过的组不留，白占内存），且天然被 `BoundedCapture` 的 1MB 上限兜住。
- **不塞任何装饰**：文件里一旦加「这是 ClionLuogu 导出的」之类说明，就没法直接 `diff`、也没法直接
  当输入喂回程序。为此把「写哪些文件、写什么字节」抽成纯函数 `plannedFiles(...)`（只碰 `java.io`），
  `export()` 只负责落盘与刷 VFS —— 于是这部分的正确性**能离线断言到字节**：
  `.in` 与原文件逐字节相等、期望输出原样复制、`actual` 用当场留的 stdout、
  stderr 单独成文件（混进 actual 就毁了 diff）、没 stderr 就不产那个文件、
  超时没输出也写一个**空的** actual（输入仍然可以拿去复现）。
- **AC 清理不碰 `Pxxx_cases/`**：延续 1.6.0 的「只认三个精确名字，宁可少删」，写进已知限制，要删自己删。

### 只看失败：过滤的是视图，不是数据

`allRows` 是真数据，`listModel` 只是它的一个视图（`rebuildList()` 同步，并尽量保住原本选中的那一组，
免得每刷新一次结果就跳回第一行）。判据抽成 `@JvmStatic keepsInOnlyFailed(verdict, isCompileRow)`，
四条断言钉住：通过滤掉、MISMATCH/RE/TIMEOUT/输出超限都留、**`verdict == null`（还没跑/未执行）不留**
（那不是失败，只是没跑）、编译行永远留（它失败时是唯一线索，成功时也是耗时来源）。
旁边一行「已跑 N 组 · 失败 M 组」的计数，跑完之前显示「还没跑」。

### 分栏自动换向：先查平台有没有这个开关

先查再写：2024.3/2026.2 这版 `Splitter.LackOfSpaceStrategy` 只剩 `SIMPLE_RATIO` /
`HONOR_THE_FIRST_MIN_SIZE` / `HONOR_THE_SECOND_MIN_SIZE` 三档，早先那对
`hOnRightWidthValue` / `vOnTopHeightValue`（宽度不够自动改方向）已经没有了 —— 所以只能自己挂
`componentResized`。语义用 `javap` 核实而不是猜：`JBSplitter(boolean vertical, float)` 直接把 boolean
透传给 `Splitter.<init>(ZF)`，而评测页一直用的 `JBSplitter(false, 0.4f)` 呈现为左右排，
故 `setOrientation(true)` = 上下排。阈值 460px 是经验值（比这窄，一行放不下「样例 12 + 判定 + 用时」）。
`AutoFlipSplitter` 是 `JBSplitter` 的子类，对拍页与评测页都换成它，两页手感一致。

耗时从 summary 文案里挪出来，改成 cellRenderer 里的定宽字段（`String.format("  %6d ms", …)`）——
UI 字体里数字是等宽的，所以不依赖等宽字体也能对齐成一列。

### 验证

101 条离线断言全绿：39 纯逻辑（题号/差异/样例发现）+ **22 UI 判据与反例字节**（`blockingReason` 7 条
含拿他真实项目跑的回归、`preservesResults` 3 条、`keepsInOnlyFailed` 4 条、`plannedFiles` 8 条）
+ 28 编译链路（含「默认模板原样编过」）+ 12 进程行为。其中新加的两条值得记：
失败组必须留着完整 stdout（不然「存反例」是空的），通过组必须不留（不然内存白烧）。
`gradle.properties` → 1.7.1，change-notes 顶部新增 `<b>1.7.1</b>` 条目（仍是单层 `<li>`，
不嵌套 —— `release.sh` 用非贪婪 `<li>(.*?)</li>` 切分），README 的功能表 / 功能条目 / 使用步骤第 4 条 /
已知限制（`_cases` 不被自动清理）与 `release.sh` 的功能一览同步改掉。

真实 CLion 要验的是离线验不到的那部分：窄窗口拖到 460px 以下看有没有真的改成上下排、
拖宽有没有换回来；失败行点「存反例」后 Project 视图里能不能立刻看到 `Pxxx_cases/`；
勾「只看失败」时通过组有没有正确消失、编译失败时是不是只剩编译那一行。

## 34. 1.7.2：跳行、总分、题库索引与删除题目

四件事都来自「刷完题这一圈」的体感缺口：CE 诊断写着行号却要人手数；非 AC 时看不出拿了
多少分（得一行行悬停方块）；1.6.0 的 AC 清理只在变绿那一瞬问一次，错过就只能去 Finder
手删，而 1.7.1 新加的 `Pxxx_cases/` 还不在它的口径里。第三条做成了**第 8 个页签「题目」**
（插在「对拍」之后、「登录」之前），顺手把删除从「AC 才问」变成「随时能点」。

### 跳行：白名单，而且永远不开诊断里那个路径

`service/CompileErrorLocator.kt` 只做纯文本解析，四个 `@JvmStatic` 函数都能离线断言。
几处刻意的设计：

- **白名单而不是黑名单**。诊断里大量行号指向标准库内部（`<vector>` 第 572 行、
  `bits/stl_vector.h`），跳过去没意义还会误导。本来打算维护一份系统目录黑名单，
  后来发现 basename 白名单（`[A-Za-z]{1,4}\d{1,5}|main` + 源码后缀）已经足够严——
  `vector`、`stl_vector.h`、`stdc++.h` 都过不了它，而黑名单反而会误伤真放在
  `include/` 目录下的自己的文件（探针各有一条）。
- **要打开的文件由 pid 拼出来**（`LuoguActions.openProblemFile` → 项目根 `Pxxx.cpp`），
  绝不去开诊断里写的那个路径：洛谷那边是它服务器上的临时名（`Main.cpp`），
  拿它当本地路径既不合法也不安全。白名单只用来判断「这一条错误是不是在我自己那份代码里」。
- **1-based 只换算一次**：`CompileHit.line` 一路都是 1-based（与编译器输出、探针断言同口径），
  只有 `openProblemFile` 里 `line - 1` 交给 `OpenFileDescriptor`。差一 bug 只能有一个藏身处。
- 三道闸门，任一不过就不给按钮：解析不出 / 行号超出**当次提交的代码行数**（宏展开与模板
  实例化会指到本地根本没有的行）/ 本地 `Pxxx.cpp` 不在（改过名、挪进子目录）。
- 每行先 `trim()`：`withRedirectErrorStream(true)` 合流出来的可能是 CRLF，行尾留 `'\r'`
  时 `$` 锚匹配不上，症状是「明明有 error 却点不出按钮」。`note:` 行直接丢（clang 用它指
  宏展开处）。优先第一条 `error`，全无 error 才退到 `warning`。
- **两段式兜底只在三段式没中时才试**。若顺序反了，`P1001.cpp:12:5: error:` 会被
  `^(.+?):(\d+):\s*(error|warning):` 匹配成「第 5 行」——非贪婪的 path 段会把 `:12` 吞进去。

不做「点文本区直接跳」：详情区是 `isFocusable = false` 的换行 `JTextArea`，鼠标事件拿不稳；
显式按钮还能禁用、还能在 tooltip 里说清楚为什么不能点。

### 他先撞到了一个真 bug：评测页的按钮常年是灰的

他一测就报「提交错误代码，评测页即使在正常位置按钮也是灰的」。这次不用猜，也不用他贴日志 ——
评测记录就落在他项目的 `.idea/clionluoguHistory.xml` 里，直接读那份文件就能看到根因：
**`Record` 里压根没有 `compileError` 这个字段**（option 名清单：code / id / lang / memoryKb / pid /
rid / score / status / statusCode / submitTime / subtasks / testCases / timeMs），
最后一条记录是 `pid=P1001 status=2 subtasks=0`。也就是说：

- 编译错误详情只活在内存里那一轮，**重启 IDE 就丢**；重启后 `toStatus()` 还原不出 CE 文本，
  `parseAll` 自然什么都解析不到 → 按钮恒灰，而且灰得没有理由；
- 顺带暴露第二条：**评测没跑完就重启，记录会永远停在「进行中」**，之后再也不会自己刷新。

三处一起修：

1. `Record` 加 `compileError` 字段，并在 `getState()` 的深拷贝、`update()` 的写回、以及还原侧
   三处同步（这正是历史上咬过三次的「加了新字段忘了镜像」那一类）。
2. 还原逻辑从 `LuoguToolWindow` 里的私有扩展搬成公开的 `storage/RecordRestore.kt`——
   私有文件级函数探针调不到，而这条 round-trip（`add → getState → loadState → toStatus`）恰恰是最该被断言的。
3. `refreshUnfinished()`：加载历史后把非终态的记录补轮询一次，带 `quiet = true`——
   那是**过期的跃迁**，再弹 AC 清理模态框或失败通知会很莫名其妙，只把数据刷回来。

再把「灰」变成「可诊断」：`CompileErrorLocator.choose()` 返回 `JumpChoice(hit, miss, detail)`，
六道闸门各有原因（没本地文件 / 没诊断文本 / 文本里没有位置 / 指向的是标准库 / 行号超出当次代码长度 / 成功），
`missText()` 翻成 tooltip，FOREIGN_FILE 与超范围还带上具体值（`vector:572`、`57/30`）——
下次再灰，鼠标移上去就知道该改哪。探针里对应 10 条原因断言 + 6 条持久化断言，
其中一条是端到端：从他那种记录形状（`statusCode=-1` + `Main.cpp:5:14: error` + 6 行代码）
还原出状态、再喂给 `choose()`，必须给出第 5 行。

### 但持久化只是其中一半：CE 详情的字段名本来就是错的

他装上新版再测，按钮**还是灰的**，tooltip 说的是「没有诊断文本可解析」。所以持久化那条确实是缺口，
却不是他这个症状的根因 —— **`compileError` 从来没被填上过**，评测页那块「—— 编译错误 ——」一直是空的，
只是以前没有跳行按钮，没人发现。

这次不猜字段名。`gh api search/code` 搜到 NB-Group/GuluGulu（还在维护的洛谷浏览器扩展），
它的 `src/contentScripts/views/Record/Record.vue` 读的正是同一个 `?_contentOnly=1` 数据源，里面写得很直白：
CE 面板取 `data.record.detail.compileResult.message`，并且专门有个兜底提示
「评测返回 CE 但编译器输出没带回」——说明这种记录真存在。

于是 `parseCompileError` 改成多路径命中、拿到即用：`record.detail.compileResult.message` →
`record.detail.compileError` → `record.compileResult.message` → 原来那两个顶层字段仍留着当兜底。
这与逐测试点明细早就验证过的层级（`record.detail.judgeResult.subtasks`）同级，说得通。

一条**我没照抄**的分歧：GuluGulu 注释说 `record.status` 不可靠、`2` 是 Compiling、CE 是 `10`；
我们的 `statusTextOf` 把 `2` 标成「编译错误 (CE)」。他自己的数据更支持后者 —— 那条**故意**写坏的提交
落盘就是 `statusCode=2`。所以那张表不动，只把「状态说是 CE、记录里却没详情」的行也纳入启动补拉
（`refreshUnfinished` 的第二类，上限 8 条）：不管它叫 2 还是 10，重新拉一次，拉回来就存下、按钮就亮。

NO_DIAGNOSTIC 那句 tooltip 也改成实话：「这条记录里没有编译错误详情（1.7.2 之前没存这个字段，
重新提交一次即可；洛谷偶尔也不返回编译器输出）」。

**这块离线验不到的东西要说清**：字段路径对不对，只有真连洛谷才知道（`parseCompileError` 是私有的，
`getSubmissionStatus` 要发请求带登录态），探针能守的只是「拿到文本之后的一切」。
所以他这一测不是走过场。

### 第三轮：文本拿到了，但解析器只认英文

他再测，tooltip 变成「诊断里没解析出位置信息」——说明字段路径对了（文本进来了），卡在 `parseAll`。
这句原因本身就是一条线索：**灰在 `NO_LOCATION` 而不是 `FOREIGN_FILE`，意味着连
`^(.+?):(\d+):(\d+):\s*(error|warning):` 这个形状都没匹配上**，差的不是文件名白名单，
而是分隔符或级别词本身。最可能是评测机跑在中文 locale 的 g++：

```
Main.cpp: 在函数 'int main()' 中:
Main.cpp:12:12: 错误：‘foo’ 在此作用域中尚未声明
```

于是两个正则的分隔符改成 `[:：]`（半角全角都吃）、级别词扩到 `error|warning|错误|警告`，
前缀组加 `致命`（中文 clang 的「致命错误」是一个词、中间没空格，`fatal\s+` 匹配不到），
`isError` 相应改成「关键字 ∈ {error, 错误} 或带了致命前缀」。

再补一条**下次不必再来一轮**的机制：`NO_LOCATION` 现在把最可疑的那行原样带进 tooltip
（优先含数字的行 —— `Main.cpp: 在函数…` 这种噪音行带回去没有信息量），
格式再对不上就直接看得见差在哪。探针相应加了 10 条（中文「错误」/ 中文「致命错误」/ 全角冒号 /
中文警告与错误混排仍优先错误 / 中文两段式无列号 / 样本挑带数字那行 …）。

**诚实记下代价**：这一条仍是按 locale 推的，不是我实测到的洛谷输出（我没有他的登录态，
游客抓 `?_contentOnly=1` 会 302）。稳妥之处是**兼容两种写法而不是替换**，所以猜错也不会
把原本能解析的英文格式弄坏 —— 这是「猜」与「赌」的差别。

### 第四轮：他贴了截图，一轮收敛。原因是文件名没有扩展名

tooltip 变成「诊断指向的是 src:12，不是你自己那份提交」，外加一张截图，真实诊断长这样：

```
/tmp/compiler_lik1oiiu/src: In function 'int main()':
/tmp/compiler_lik1oiiu/src:12:22: 错误：‘edl’在此作用域中尚未声明
     12 |     cout << a + b << edl;
        |                      ^~~
```

三件事一次全确认：中文 locale 与全角冒号**猜对了**（能走到 `FOREIGN_FILE` 就说明正则已经命中），
噪音行 `…: In function …` 没被误收，而白名单错在——**洛谷把源码编成 `/tmp/compiler_lik1oiiu/src`，
文件名根本没有扩展名**，我那份只认带后缀的 `P1001.cpp` / `Main.cpp`。
修法：加一组无扩展名的提交文件名 `src` / `main` / `program` / `submission`（标准库的
`vector`、`stl_vector.h`、`basic_string.h` 都不在其中，白名单该拦的仍然拦得住），
并把这段真实诊断原样写进探针当回归（6 条，含「只出 1 条」「= 第 12 行第 22 列」「大写 SRC 也过」）。

**这一轮值得记的是方法**：前三次之所以要猜，是因为「灰」这个状态什么都不说。
把六道闸门各自的原因（外加最可疑那行的原文）搬到 tooltip 上之后，他一悬停就看到了
`src:12`，我一次就改对了 —— 自诊断把「再来一轮」变成了「一轮收敛」。

### 总分：拿不准就一个字符都不显示

`service/ScoreTotals.kt`。**空列表或任一子任务 `score == null`（评测进行中）→ null → UI 什么都不加**；
`0` 是合法值（全 WA 也要显示 0 分）。这条口径比功能本身重要：「AC  0 分」是看着像真话的假话。

不带 `/满分`——`fullScore` 只在题目 DTO（`api/dto.kt:26`）上，提交记录里根本没这个字段，硬凑只会显示 0。
只有 `subtaskInfo` 文本时从文本兜底（`#1: 40分`），半角与全角冒号都吃，**任一行不合式就整体返回 null**。
取值顺序（明细优先、退化到文本）收在 `totalScoreOf(status)` 一个函数里，评测页和未通过通知共用——
这种「两个地方各自 if 一遍」的东西最容易日后走岔。

### 题库索引与删除：列出即可删

`service/ProblemIndexService.kt` 只看**项目根一层**、只用 `java.io`：与 `AcCleanupService` 的删除
口径同源，且反映磁盘现状（他可能在 IDE 外面动过手），也省掉 `VirtualFile` 的读动作要求。
后缀四类（`.cpp` / `.md` / `_samples` / `_cases`）之外一律不进索引 —— 一个不可逆的删除功能
不该有「大概匹配」。**类型也要对**：`P88_samples` 如果是个文件而不是目录，既不进索引也不删。
探针里有一条集合相等断言：`presentNames()`（索引说有的）与 `targetNames(pid)` 里真实存在的
（删除能删的）必须是同一批名字——这两个口径一旦分叉，就会出现「列出来却删不掉」。
`_samples` / `_cases` 两个字面量还各自断言等于 `SampleSetService.samplesDirName` /
`CaseExportService.casesDirName`，防止以后改了落盘名而索引悄悄失灵。

`SubmissionHistoryService.latestFor(pid)` 用 `lastOrNull`（记录按追加顺序存，最后一条才是当前状态），
忽略大小写。

删除部分把 `AcCleanupService` 拆开：`targetNames` / `targetsOf` / `listingOf` / `deleteOrder` /
`confirmAndDelete(project, pid, onDone)`，AC 自动清理与手动删除走同一内核，
**并且把 `Pxxx_cases/` 也纳进 AC 清理**（于是 README、`plugin.xml`、`LuoguConfigurable`、
`release.sh` 四处措辞一起改）。原来那句 `children.filter { !it.isDirectory } + target` 只脱一层，
多了 `_cases` 嵌套就不够用，改成**递归后序**。

`deleteOrder` 的真身搬到了 `service/DeleteOrder.kt`，做成 `orderOf(targets, isDir, childrenOf, maxDepth)`
的泛型内核：`VirtualFile` 要起 IDE 才拿得到，而「子项在前、目录在后」这个判据必须能离线跑
（`VirtualFile.delete()` 删不掉非空目录）。`AcCleanupService.deleteOrder` 只是把三个访问器传进去，
走的是同一份代码，探针于是能拿真 `File` 树验后序与深度截断。
**超过 `maxDepth` 的目录不深入，只把目录本身排队**——它删除时因非空失败，于是「N 项失败」如实报出来，
而不是静默留一地孤儿文件。

确认框是唯一闸门：`confirmAndDelete` **不看** `acCleanupEnabled`（那个开关管的是「要不要主动问」，
不是「能不能删」），但也必须他点过「删除本题文件」才会走到这里。提交记录与评测历史永不碰，
这句话写进了 KDoc 第一行和确认框文案。

### 探针先抓到一个窄窗口 bug（上次是他抓的）

评测页顶行我先按「状态文字 `BorderLayout.WEST` + 两个按钮 `GridLayout(1,2)` 放 EAST」写了，
`Layout172Probe`（带 `JFrame.pack()`，量真实布局）在 240px 与 170px 两档直接量出来：
JLabel 需要 272px 宽 → 溢出容器、第一个按钮 x 变成 `-4` / `-74`，**两个组件被裁掉**。
这就是 1.7.1 他被坑过的那一类（`FlowLayout` 折行只算一行高度）。换成
「状态文字独占一行 + `WrapLayout` 装两个按钮」后三档全 0 裁掉，且比「每行一个组件」省 29px 高度，
宽的时候两个按钮仍然并排。这个方案现在是产品实现，被否掉的那版留在探针里做对照。

一处与计划的偏离：原计划写「没有可跳的位置时按钮**不出现**」，实现成**变灰 + tooltip 说明原因**——
`BorderLayout` 里隐藏子组件会留空槽，而且邻居「复制代码」本来就是常年在位、没代码时点了没反应，
行为一致更好解释。要的效果（不会跳错行）是同一个。

顺带记一条 javac 的误导性报错：探针里写 `new JPanel(java.awt.BorderLayout())` 漏了 `new`，
编译器报的是「找不到符号 类 awt 位置：程序包 java」，看着像 classpath 问题，其实是少了构造符。

### 验证

**239 条离线断言全绿**：108 条本轮新逻辑（得分合计 17 条，含状态取值顺序那 4 条、跳行解析 34 条
（其中 16 条是中文 locale、全角冒号与洛谷那个**没有扩展名的 `src`**）、灰按钮原因 13 条、编译错误持久化 round-trip 6 条、
索引判据 23 条、删除序 6 条、提交记录侧查询 4 条、索引页纯判据 5 条）+ 30 条元数据（版本三处对齐、`<depends>` 仍只有
platform+jcef、change-notes 用 `release.sh` 的同款非贪婪正则取得到 `<b>1.7.2</b>` 且不嵌 `<li>`、
README/设置页/`release.sh` 措辞跟上、jar 里五个新类都在且 `compat/` 为 0）
+ 101 条回归（28 编译链路 / 39 纯逻辑 / 22 UI 判据 / 12 进程行为，全部重编译后重跑）。
另有两份诊断式输出：`Layout172Probe`（三种宽度 × 四种方案的实际像素）、`DiagProbe`（进程合流行为）。

只能他在真 CLion 里看的（探针碰不到的那层）：

1. **先验那条卡住的记录**（CE 持久化 + 启动补轮询）：装上新版直接重启、**先别提交**，之前停在「进行中」
   的那条 `P1001` 应自己刷出编译错误（quiet：不弹通知、不弹清理框），选中它按钮应变亮并跳到第 5 行；
   若仍灰着，把 tooltip 那句原因原样贴回来（现在会带 `vector:572` / `57/30` 这类具体值）；
2. 跳行会不会差一：造一个第 12 行的编译错，跳过去光标要落在写错那一行；
3. 侧边栏拖窄：评测页「复制代码 / 跳到出错行」两个按钮、题目页底部按钮，有没有被裁（探针量过了，但真实 LaF 与字体要 IDE 里才算数）；
4. 「题目」页签：切过去自动扫描的行数对不对；选中点「删除本题文件」→ 确认框里列的东西与实际被删的是否一致、通知里的 N 对不对、列表有没有自动重扫；
5. `Pxxx_cases/` 里自己 `mkdir` 一层子目录塞文件，看有没有被递归删净（目录本身也消失）；
6. 大小写：把文件改成 `p1001.cpp`，索引与删除都要还能对上（`scan` 与 `targetsOf` 一个走 `java.io` 一个走 VFS，这里最可能不一致）；
7. 本地文件已改短或挪进子目录（诊断行号超范围 / 文件不在）时按钮是不是**灰着并写明原因**，而不是跳错行；
8. 总分：正在评测（子任务无分数）时详情区应当**什么都不显示**，跑完才出现「各子任务得分合计 N 分」；重启后从持久化记录算出的分要和新的一样。

## 35. 1.7.3：整体现代化（他说「UI 看起来很土」）

功能上 1.7.2 已经够了，这一版纯改呈现。他的原话是「这个 UI 开起来还是很土」，然后补了一句「让整体现代化一点，插件大没关系」——
所以目标不是调色，而是把**信息的组织方式**换掉。

### 病根不在配色，在「所有信息拼成一个大字符串」

对着他那张截图能数出四件事：

1. 评测详情是一个 `JTextArea`（`lineWrap=true`、没设字体），里面塞了元信息 + `—— 编译错误 ——` + 原始诊断 +
   `—— 提交的代码 ——` + 整份源码。所以诊断被折成 `In funct` / `ion` 那种断词，而且**鼠标划不动、复制不了**
   （`isFocusable=false`）。
2. 分隔线、项目符号、对齐全是手搓 ASCII：`|`、`→`、`•`、`·`、`—— X ——`、`String.format("  %6d ms")`、
   `padStart(4)`、全角空格。
3. 整个插件只有 2 个图标（标题栏那两个动作），面板内零图标、零层级。
4. 间距各写各的，还有 4 处普通 `FlowLayout` —— 就是 1.7.1 把他坑过一次的那种。

### 决定不做的两件事（和理由）

- **不上 Kotlin UI DSL v2**（`panel { row {} }`）。它返回 `DialogPanel`，工具窗口内容里 `onApply/onIsModified`
  全用不上；首选宽度按 MigLayout 算，460/240/170 三档得重新验一遍；而收益只是「间距统一」。
  把 879 行的 `SampleComparePanel` 重写一遍换这个不值。继续 GridBag + `WrapLayout` + `AutoFlipSplitter`。
- **没有把所有按钮换成 `LinkLabel`**（计划里写了）。真做下来发现次级按钮**变灰**比**消失**更有用：
  上一轮那个 bug 就是靠灰按钮的 tooltip 一次收敛的，隐藏掉就没得悬停了。
  所以只加图标、保留 `JButton` 与 disabled 态；`LinkLabel` 那部分改动作废。
  这条是执行中改的主意，写下来免得下次又照计划走一遍。

### 做了什么

**`ui/DetailPanel.kt`（新）** —— 结构化详情区：`TitledSeparator` 分节、`SimpleColoredComponent` 键值行
（标签 `GRAYED_SMALL`、值 `REGULAR`，不靠全角冒号对齐）、每个正文块是**独立**的等宽只读文本域
（`lineWrap=false` + 横向滚动 + 编辑器底色 + 描边），可以选中复制。

关键设计是 `sectionsFromText(raw)` 这个**纯函数**：它把对拍页原有的那些字符串生产者
（`introText` / `compileDetail` / `detailOf`，以及被探针按字节断言的 `SampleDiff.contextBlock`）
**一个字都不改**地切开——开头连续的 `键：值` 行变键值行，每个 `—— 标题 ——` 开一节。
于是 879 行文件里的字符串逻辑没动，呈现换了。`BlockText.splitHeader` 是同一条路的纯函数版。

`evalSections` / `indexSections` 两个页面用自己的结构化入口（评测页有真的字段，没必要再拼字符串再拆）。

**判定配色改成明暗两套**（`VerdictColors.kt`）—— 这条是探针先发现的：
原来 13 个裸 `java.awt.Color` 是从洛谷抄的深色主题值，`#001277`、`#0e1d69`、`#262626` 在白底上糊掉；
但**反过来在深色主题下它们同样几乎看不见**（与列表底色对比度 1.1~1.8）。
也就是说这个 bug 从 1.0 就在，只是两套主题下各瞎一部分，谁也没当成 bug。
现在表在 `PALETTES` 里、`verdictColorOf` 从表构造 `JBColor`，另开一个 `verdictPalette(code)` 给探针取值
（JBColor 解析成哪一套取决于运行时主题，探针拿不到那个上下文，只能把两套分别验）。
`VerdictSquareIcon` 改圆角 + 描边，「还没结果」用空心圈——实心灰块会被当成一个真判定。
`TestCaseSquares` 里手搓的亮度公式换成 `ColorUtil.isDark()`。

**其余 chrome**：列表行分段着色（题号加粗、`#rid` 灰小、状态按判定染色），去掉 `|` 和 `→`；
四个列表加 `setEmptyText`；评测/题目/搜索三个列表加 `ListSpeedSearch`（敲题号即定位）；
按钮加 `AllIcons`；拉取/搜索/提交/登录四页的状态行加 `setStatus(text, error)`（错误色），
搜索页原来把多行结果塞进单行 `JBLabel` 的地方改成「取首行 + 全文进 tooltip」；
普通 `FlowLayout` 全部换 `WrapLayout`；`LoginPanel` 那两个 `columns=40` 收到 20、
`__client_id（浏览器 F12 → …）` 那串长标签挪进 tooltip（窄侧边栏里它自己就被裁）。

**预览页**：样式从 `PreviewPanel.themeCss()` 的字符串拼接挪进 `resources/css/preview.css`，
Kotlin 只负责把 `@BG@`/`@FG@`/`@ACCENT@` 等按当前主题替换；字号字体改从 `UIUtil.getLabelFont()` 取
（原来写死 13px，在放大字体的屏幕上小一圈）；难度/时限/内存/分数那组改成 `.chip` 徽章条，
题解作者行同理。**CSS 只用 CSS 2.1 子集**——无 JCEF 时同一份样式要喂 `JEditorPane`，
它遇到 flex/grid 会静默降级，排版塌了没人报错（这条写进文件头注释了）。

### 他要的那条：提交文件名判断不再靠猜

`CompileErrorLocator.choose()` 加 `localFileName` 入参，判定分三层：
① 调用方给的真实文件名（对拍页传 `target.sourcePath` 的 basename，评测页传 `"$pid.cpp"`）；
② OJ 常见名名单（`src`/`Main.cpp`/`P\d+.cpp`…）；
③ 兜底：名字全对不上，但行号落在提交代码长度内、且路径不像头文件/库
（不以 `<` 开头、不含 `/include/`、`/usr/`、`/bits/`，后缀不是 `.h/.hpp/...`，也不是 `.s`/`.o` 这类中间产物）。
tooltip 上走兜底会写明「诊断里写的是 xxx，按你提交的那份文件跳」——用了兜底要看得见。

一个容易写错的点：**先卡行号再分层**。反过来（先分层再卡行号）会让「本地名字那条恰好超出代码长度」
把本来能跳的兜底那条一起废掉，探针里专门留了一条测这个顺序。

### 探针

新增 `Ui173Probe`（41 条：`splitHeader` 8 条、`sectionsFromText` 12 条、`evalSections` 8 条、
`indexSections` 6 条、配色对比度 6 条 + 等宽字体）与 `Layout173Probe`（真 `DetailPanel` 装进 `JFrame`，
460/240/170 三档各测「被裁组件 = 0」）。**合计 296 条全绿**（含 1.7.2 那 121 条与更早的 101 条回归）。

两条踩到的环境事实，记下来省下次的时间：
① 探针里用平台组件（`SimpleColoredComponent`、`JBScrollPane`）必须加
`--add-opens java.desktop/javax.swing=ALL-UNNAMED`（还有 `javax.swing.plaf.basic`、`java.awt`），
否则 `InaccessibleObjectException`；
② macOS 上 `JBScrollPane` 会走 `MacScrollBarUI` → JNA，需要
`-Djna.boot.library.path=<CLion>/lib/jna/aarch64 -Djna.nosys=true -Djna.noclasspath=true`，
那个 `.jnilib` 不在任何 jar 里，光加 classpath 没用。

也有三条断言是**我自己写错预期**：`sectionsFromText` 从「一节」改成「多节」之后，
老断言还在要求 `size()==1`。这种 FAIL 是有用的——它说明新行为比原先写的预期更好，改断言而不是改代码。

### 只有他眼睛能定的

浅色 / 深色两套主题下 13 档判定的实际观感；预览页新 CSS（含无 JCEF 兜底那条路）；
速度搜索的输入手感；详情区现在能用鼠标选文字（这条改了 `isFocusable`，焦点/滚动行为可能跟着变）；
以及「土不土」本身——这是他的判断，不是断言能覆盖的。

## 36. 1.7.4：提交页一直说「未打开编辑器」

1.7.3 刚发出去他就报了：提交页一直显示没打开编辑器，交不了。
（他对比的是更早那版**弹窗**提交——那个是按文件名去取代码的，所以一直好使。）

### 根因：它只看「当前选中的那一个」编辑器

`SubmitPanel.readCurrentCode()` 读的是 `FileEditorManager.selectedTextEditor`，于是两种常见情形都会拿到 null：

1. 多标签里 `P1001.cpp` **开着**，但当前选中的是 `main.cpp`（或刚点过别的页签）；
2. 文件就在项目根，只是**没在编辑器里打开**——这恰好是他现在的状态：测 1.7.2 的删除功能时把
   `P1001.*` 删了又新拉了一道题，编辑器里开着的是别的文件。

对拍页一直是按题号去项目根找源文件的（`probeCompareTarget` 里 `File(base, "$pid.cpp")`），
提交页没道理更挑——**同一个插件里两个页面取「这道题的代码」用了两套规则**，这才是病根。

### 改法

`readCode(pid)` 两级来源，判定抽成纯函数 `pickCodeOrigin(openFileNames, wantedName, diskFileExists)`：

- **按题号在所有已打开的文件里找**（`manager.openFiles` + `getEditors(vf)`），不看当前选中项；
  文件名比较忽略大小写（大小写不敏感的卷上会差一次）；
- 没有 → 退到项目根 `Pxxx.cpp`，直接 `java.io` 读（走到这一路说明它没在编辑器里打开，磁盘就是唯一真相；
  比绕 VFS + `FileDocumentManager.getDocument` 少一层，也顺带避开 EDT 上那个 `ThreadingAssertions` 坑）；
- 两处都没有 → 说「找不到 `Pxxx.cpp`：编辑器里没打开，项目根也没有这个文件」，
  不再拿「未打开编辑器」这种半截真相糊弄。

预览旁边把**来源写出来**（「编辑器里打开的 P1001.cpp（含未保存的修改）」/「项目根的 P1001.cpp（编辑器里没打开它）」）+ 行数——
「交的是哪一份」这种事不该让人猜。顺带让题号跟着当前编辑器走（手输的不会被覆盖，
和对拍页同一条规则），以前那个默认值是 `init` 时算一次就定死了。

### 追加：预览不会刷新（同一天第二条回报）

第一条修完，他接着说「**这个不会刷新，我把窗口关了也不刷新，改成 1s 刷一次**」。
根因是我原本只在 `addNotify()` 里刷一次，而**工具窗口的 Content 是缓存的**：切页签、
甚至把侧边栏整个关掉再打开，`removeNotify`/`addNotify` 都不一定再触发——面板活着，
`init` 那次读到的文本就一直挂在 `JBTextArea` 里。这类「靠生命周期回调刷一次」的假设，
在 Content 缓存面前不成立，只能自己定个表。

改成 `javax.swing.Timer(1000)`（Swing 定时器天然在 EDT 回调，不用再 `invokeLater`）+ 三条约束：

- **每 tick 只做「便宜探测」**：编辑器那一路看 `document.modificationStamp` + `textLength`，
  磁盘那一路只 `stat`（`length()` + `lastModified()`），拼成签名；**签名没变就直接 return**，
  既不重读全文也不重画。每秒复制一份源码字符串给 EDT 添堵是没必要的。
- **重画要保住滚动位置**：`showPreview` 先记下 `viewport.viewPosition`，换完文本再夹回
  `[0, 内容尺寸 - 视口尺寸]` 复位。不夹的话，改短代码会 `InvalidComponentException` 式跳顶，
  每秒跳一次比不刷还烦。
- **面板不在容器里就停表**：`removeNotify()` 里 `ticker.stop()`，别在背后空转；
  `refreshPreview` 开头还挡一层 `project.isDisposed`（定时器与 project 生命周期不必同步）。

`提交` 按钮走 `refreshPreview(force = true)` 再交 `currentCode`——**交的一定是刚刚那一份**，
不是最多 1 秒前的缓存；状态行的「N 行」也因此和真实提交内容一致。
`题号` 输入框加 `addActionListener`（回车即重看），不用等下一个 tick。

探针补 6 条（`pickCodeOrigin`：题号文件在第二个标签里 / 只有 `main.cpp` 时退磁盘 / 大小写 /
两处都没有 / 开着别的题 / 编辑器开着就不读磁盘）+ 3 条 `countLines`（空串 0 行、无结尾换行 2 行、
有结尾换行 3 行），合计 **305 条全绿**（272 Java + 33 元数据），布局探针 460/240/170 三档被裁 0。

**只能他点一次确认的**：定时器在有真实 `Project` 的 IDE 里才跑，签名短路是否真能保住滚动位置、
1 秒的延迟手感如何，都量不了。

**值得记的两条**：一是「按当前选中项取上下文」在 IDE 插件里特别常见、也特别容易错——
用户的心智模型是「我在做 P1001 这道题」，不是「我此刻焦点在哪个标签」，
以后碰到「取这道题的 X」一律按题号找、找不到再退磁盘，几个页面共用同一份判定。
二是**工具窗口 Content 会缓存面板**，所以「界面数据的刷新」不能挂在 `addNotify` 上；
要么挂真正的事件（`FileDocumentManager.addDocumentListener`、`VfsChangeListener`、
`FileEditorManagerListener`），要么像这样自己定表 + 签名短路。

## 37. 1.8.0：左侧题目栏 + 底部运行栏（他要「洛谷 IDE Plus」）

1.7.4 还压着没发，他先挑了功能 2 并给了更大的框架：**「插件挪到左边，底下弹出个自测，还能用上 CLion 这么好用的 IDE，就是洛谷的 IDE Plus 版本」**。
四个澄清问题他一个没回（AskUserQuestion 返回空），我按自己标的推荐项定了：底部装**自测 + 对拍 + 提交**、stdin **不落盘**、每次**都重编**、anchor **只改注册不加开关**。

### 为什么这么分

病根不是少一个功能，是**「写码 → 跑 → 看输出」这条最高频的回路被拆在两处**：跑要在右侧一堆页签里找「对拍」，
而 IDE 自己的运行输出在底部。分家之后左侧 = 题目侧（评测 / 拉取 / 搜索 / 预览 / 题目 / 登录），
底部 = 运行侧（自测 / 对拍 / 提交），和 CLion 的 Run 窗口同一个位置。

自测补的是对拍页一句硬拒绝：`Pxxx_samples/` 里没有成对的 `.in`/`.out` 就「啥也干不了」。
而调 WA 的第一步往往就是「我造个输入，看它到底打印什么」——这一刀下去，**「没样例」不再是死路**。

### 三条不做什么（都是被之前的教训逼出来的）

- **stdin 不落盘**：按题号存进项目级 XML（新 `storage/SelfTestInputService`）。造一个新目录名
  就等于给 `ProblemIndexService` / `AcCleanupService` / `DeleteOrder` 那套「只认四个精确名字」的删除口径
  添一处「删不到 or 删错」的风险；而这份输入本来就只是临时试。想留档他自己复制到 `Pxxx_cases/`。
- **不给「用上次产物」**：那要靠 mtime 猜产物新旧，正是他之前否掉的那类猜测（找产物 / bits 兼容头同一条线）。
  重编一两秒换「看到的输出一定来自现在这份代码」。
- **不显示内存**：子进程内存没有可移植拿法（`getrusage(RUSAGE_CHILDREN)` 要 JNI、`/usr/bin/time` 不保证存在）。
  界面连「内存」两个字都不出现——不说比瞎说好。

### 两个必须抽出来的东西

1. **`service/ProcessRunner.kt`**：`SampleCompareService.runOne` 原来把「起进程 + 超时 + 取消 + 有界捕获」
   和「读期望 + 比对 + 差异上下文」揉在一起，而自测只要前者。**复制那 60 行就是两份会各自腐烂的代码**。
   抽出 `Run`/`Outcome` 后 `runOne` 退化成「跑 + 比」，`Request`/`Result`/`Report`/`Verdict` 一字未动。
   三条纪律原样搬：stdin 走**文件重定向**（不是管道）、`ParentEnvironmentType.CONSOLE` + 注入编译器目录到
   `PATH`、自己用 `BoundedCapture` 收而**不用 `CapturingProcessHandler`**（无界缓冲，刷屏能撑爆 IDE）。
   判定优先级也保住：**超限 > 超时 > 取消 > 退出码**（超限与超时都是被杀，但「输出爆量」才是他要看的原因）。
2. **`service/SubmissionTracker.kt`**：提交记账原来绑在 `LuoguToolWindow.trackSubmission` 上，
   把「写持久化 + 起轮询 + 刷评测页」三件事捆在一起。提交页搬到底部之后，这条捆绑变成**会丢数据的 bug**：
   左侧窗口从没打开过 → `evalWindow()` 是 null → 记录整个不落盘，连重启后的补轮询都找不到它。
   现在持久化与轮询**无条件做**，UI 能找到就更新；`updateSubmission` 退化成只管界面，
   补拉老记录那条路（`refreshUnfinished`）也改走 `applyStatus`，落盘只有一处。

另外 `ui/LocalRunGates.kt` 收走了「跑不起来」的四条文案：自测需要的是**去掉样例那一关**的同一条链
（`blockingReason` 的顺序与产出与搬迁前逐字一致，探针在断言）。`LuoguActions.saveIfModified`、
`CompilerService.executableName`、`LuoguSettings.compareCompilerArgList` 同样是从两份重复里收成一份。

### 探针从 /tmp 搬进仓库（这轮最大的教训）

2026-10-03 一次重启把 `/tmp` 清了，那 305 条断言的**源码整个没了**——它们本来就该是仓库的一部分。
现在在 `probes/`：`run.sh`（自动找 Gradle 下的 JDK 与 CLion transforms 里的平台包）、
`CoreProbe.java`（纯逻辑 80 条）、`ProcessProbe.java`（**真 clang++ 真子进程** 24 条）、
`LayoutProbe.java`（布局）、`meta.py`（元数据）。跑法：`bash probes/run.sh [core|process|layout|meta]`。

重建时踩到的坑，都写进 `run.sh` 注释了：

- `-cp` 必须带上探针自己的输出目录（`build/probes`），否则「找不到或无法加载主类」；
- classpath 一律**绝对路径**（相对路径在 `cd` 之后整个失效，症状是只有新增类报「找不到符号」）；
- macOS 自带 bash 3.2 在 `set -u` 下展开**空数组**直接报错 → 开关用普通字符串；
- 平台 Swing 要 `--add-opens java.desktop/javax.swing{,.plaf.basic}` + `java.awt`，
  macOS 滚动条还要 `-Djna.boot.library.path=$PLAT/lib/jna/aarch64`；
- JVM 不自己退出（平台共享的 `I/O pool` 是非 daemon 线程）→ 探针末尾 `Runtime.halt()`，
  runner 那边取 pid 再 kill，且**别 `| grep`**（缓冲，看不到进度）；
- 4 条一开始红的断言都是**我的期望写错**，不是代码错：`splitHeader` 剥的是「第一个**非空**行」
  （开头空行不算内容）；`sectionsFromText(null)` 给空列表才是对的；`contextBlock` 的行格式是
  `✗    5: line5`（`padStart(4)` + 冒号），我按记忆写成 `5 | line5`。

`ProcessProbe` 里那条「kill 到收尾要几秒」的量测（超时 600ms 实际花 5.0s）说明：
`destroyProcess()` 是 SIGTERM 补刀 + `awaitTerminated(2000)`，所以**被判超时的程序会让「运行」按钮多灰几秒**。
不是 bug，但值得知道；断言阈值因此放到 15 秒（它要抓的是「等满编译上限 120 秒」那种回归）。

### 覆盖面

本轮重建后的数字：**108 条 Java 断言（core 84 + 真子进程 24）+ 36 条元数据 + 布局 4 档 72 个位置 / 被裁 0**，
比丢掉的那 305 条**覆盖窄**——`ScoreTotals` / `ProblemIndexService` / `DeleteOrder` / `AcCleanupService`
那几个旧套路的断言还没搬回来，它们的保护现在只剩代码里的注释。这是这轮已知欠账。

底部窗口形状（宽而矮）的量法是新的：`LayoutProbe` 在 **1200×180 / 900×120 / 500×260 / 460×400**
四档开真 `JFrame` + `pack` 量详情区，因为 `AutoFlipSplitter` 按**宽度**换向，在宽而矮的底部窗口里
根本不救场——自测面板的输入 / 输出因此直接用 `JSplitPane(VERTICAL_SPLIT)`，没套那个自动换向的分栏。

### 只能在真实 CLion 里点的

左侧栏第一次要不要手动拖（`anchor` 只是默认值，IDE 记住用户拖过的布局）；底部窗口默认高度够不够看输出；
「运行」时 `cin >>` 读满就停、死循环 5 秒被终止、刷屏被截断；换题号后输入各自回来、重启还在；
编译失败的诊断 + 跳到第 N 行落的是项目根那份；两个窗口的页签互相跳转（对拍页的「去拉取」、
通知的「查看」跳评测）都还对不对；从底部提交时**从没打开过左侧窗口**，记录有没有正常出现在评测页。
以后碰到「取这道题的 X」，一律按题号找，找不到再退磁盘，两个页面共用同一份判定。

## 38. 1.8.0 追加：他试完之后的三条

他装好试过，回三条。

**1）「底下太臃肿了，改成左边输入，右边输出」。** 我 1.8.0 第一版是上下排（当时的判断是「底部窗口宽而矮，
所以纵向分栏」）。方向错在**矮不是问题，横向浪费才是**：底部窗口能一千多像素宽，左右排每一块都够宽。
改用现成的 `AutoFlipSplitter`（按宽度换向：宽 → 左右，窄 → 上下）。顺带减 chrome——两块 `TitledBorder`
换成一行细说明（边框 + 标题吃掉两行高），次级动作（停止 / 跳到第 N 行 / 去拉取 / 换编译器）从 `JButton`
换成 `LinkLabel`。这正是 1.7.2 那条坑的解药：**链接 `setVisible(false)` 不留空槽**，
不像隐藏的按钮会在 `WrapLayout` 里留一段空白。

**2）「对拍能自己设定空间时间，测试复杂度，默认时空在题里」。** 时间上限本来就有（写死 5 秒）；
内存这块 1.8.0 第一版是**明确不做**的（「子进程内存没有可移植的拿法」）。他要，就重做一遍判定标准：
**不按 `os.name` 猜，而是实测** —— 真拿 `/usr/bin/time -l` 与 `-v` 跑一条 trivial 命令，
**能解析出峰值才算可用**（本机 macOS 报 `peak memory footprint` = 字节，实测通过）。
量不到就**禁用字段 + tooltip 写明原因**，绝不拿 0 当「没超」。
默认值取题面：`Pxxx.md` 里那两行（`**时间限制**: 1000 ms` / `**内存限制**: 131072 KB`，KB → MB 由 `ProblemLimits` 解析）。
新增判定 `Verdict.MEMORY_LIMIT`，颜色直接借评测页的 MLE（状态码 4），不新调色板。
两条必须处理的细节：**包一层 `time` 之后被杀的是测量器**，所以必须
`setShouldDestroyProcessRecursively(true)`，否则超时 / 取消会留下跑飞的孤儿进程；
**报表与程序的 stderr 是同一条流**，展示前得剥掉报表行（`ResourceMeter.stripReport`）。

**3）「我拉取题目以后还显示项目根没有 P1001.cpp」。** 真 bug，而且是设计缺陷：`probeIfStale()` 的门槛是
`targetPid == pid && target != null` → 只要探过一次（那时文件还不存在）就**永不重探**，
而「拉题完成」与运行侧之间没有任何事件。修法是**盯磁盘**：新增 `LocalRunSignature`，
每秒给出「源文件长度 + 样例目录条目数与 mtime」的签名，变了就重探（只 `stat`，不读文件内容）。
两个面板共用同一条签名，`running` / `probing` 时不打扰。

### 探针跟着长的两条防呆

`probes/run.sh` 现在**先跑 `compileKotlin`** 再断言：改完 `ResourceMeter` 忘了编译，探针照样红 ——
「跑的是旧字节码」这种坑必须自动化挡掉，不能靠我记得。

本轮 124（core）+ 33（真子进程）+ 36（元数据）全绿。其中：

- 两条一开始红的是**我的期望值写错**（1025 KB 是跨进第 2 兆，不是 1 兆；非 `PASS` 的行本来就该留完整 stdout）；
- 一条红的是**真 bug**：`isReportLine` 用 `matches()` 去匹配 `        real         0.00s` 会漏
  —— 数字后面还跟着单位 `s`，于是那三行计时会混进用户看到的 stderr。改成 `containsMatchIn` 修掉，断言留着。
- 真子进程那 33 条里，测量器那条是端到端的：编一个 `malloc(40MB)+memset` 的程序，
  断言「峰值量得到 > 30 MB」「上限 8 MB 判 MLE」「上限 512 MB 判正常」「stdout 仍是 `ok\n`（报表没混进去）」
  「对拍链路上 `Verdict.MEMORY_LIMIT` 与 `peakMemoryMb` 都对」。

### 现在只能在真实 CLion 里点的（追加）

- 拉完题**什么都不点**，一秒内运行侧就该把「项目根没有 Pxxx.cpp」变成能跑；
- 内存字段：题面有 128 MB 就自动填上，量不到内存的机器上应该是**灰的 + 说明**；
- 超内存那一行显示成「超内存 200 MB / 上限 8 MB」，方块颜色与评测页 MLE 一致；
- 跑飞（死循环）被判超时之后，活动监视器里**不该留下同名孤儿进程**（递归杀那条只在 IDE 里能验）；
- 底部窗口左右排下输入区够不够宽（一行 20 个数看不看得全）。

## 39. 1.8.0 第二轮：运行栏的专属排版（他说「对拍和提交被拉伸得很长」）

第一轮我只把**自测页**按底部窗口的形状重做了，对拍页与提交页是从右侧边栏整块搬下来的，
纵向一件都没动。量出来才是实锤（`probes/LayoutProbe.headerExperiment`）：

| 形状 | 实测首选高度 |
| --- | --- |
| 一件一行 ×6（对拍页原来的 north） | **> 150px** |
| 同一批控件横排一行（1200px 宽） | **≈ 32px** |

底部窗口的可用高度本来就只有 120–200px —— 头部比窗口还高，列表和详情自然被挤成一条缝。
`AutoFlipSplitter` 按**宽度**换向，管的是横向，救不了纵向。

### 改了什么

1. **对拍页 north 6 行 → 1 行**：题号 / 时限 / 内存 / 编译器标签 / 换编译器按钮横排；
   「题号（空=跟当前文件）」这句说明挪进 `pidField.emptyText`（说明不该独占一行）。
   底部再合并成一行：按钮 + 状态文字同条 `WrapLayout`。
2. **提交页 form 5 行 → 1 行**：题号 + 语言下拉横排，预览区吃满剩下的全部高度。
3. **删掉两个按钮**：「刷新预览」（提交页）与「重新查找样例」（对拍页）。
   前者被 1.7.4 的每秒自动重看取代，后者被 1.8.0 的磁盘签名自动重探取代 —— 留着就是白占一行，
   还多两个可能点错的东西。要立刻重看仍然有：回车、点别处（失焦）都是 force 路径。
4. **状态行规则收成一处**：`ui/StatusRow.kt`。
   「出错就染错误色 + 整句进 tooltip」这条原先在**拉取 / 搜索 / 登录 / 提交 / 自测**五个面板各写一遍，
   对拍页的 `warnLabel` 还自己写了一版琥珀色。这正是 1.7.2 那次「状态栏写对、详情区写反」的成因 ——
   同一条判散在多处，早晚有一处不写。现在四个面板调同一处，并把 `JBColor(亮, 暗)` 补上
   （原来裸 `Color(0xE0,0x80,0x00)` 在浅色主题下发虚）。
   探针钉住五条：正常句不染错误色、正常句不留残留 tooltip、错误句染色 + tooltip、
   从错误回正常要复原、`firstLine` 全空白时给一个空格（空串会让标签高度塌下去，布局跟着跳）。
5. **底部三页各配图标**：`AllIcons.Actions.Execute / RunAll / Upload`
   （`javap com.intellij.icons.AllIcons$Actions` 核过 —— 1.7.3 那份清单里没有 RunAll 和 Upload，
   照抄就会撞「找不到符号」）。
6. **详情区块的最小高度没改代码**，因为 `DetailPanel.blockComponent` 早就把每块夹在
   `coerceIn(scale(48), …)`（约三行）里了。本轮把它变成断言（`blockMinimumHeight`），
   防止以后为了「多塞几块」把下限调没。

### 一处判断修正：没有把次级动作都改成链接

计划里写过「停止 / 存反例 / 跳到第 N 行 / 去拉取」换成 `LinkLabel`。做完头部压缩之后我再看了收益：
**行高问题已经解决，剩下的只是宽度**，而 `LinkLabel` 隐藏时不留空槽的好处在这里不明显，
代价是它们有真实的**禁用态**（不能存反例、不能跳行时按钮要灰掉），
`LinkLabel` 继承自 `JLabel`，禁用与点击的语义得另想办法兜。
所以只把一直可用的「换编译器…」留在按钮、`去拉取` 保留按钮，**不为了少几个像素牺牲禁用态**。
（1.7.2 那条「隐藏按钮会在 `GridLayout` 里留空槽」的坑，这里用的是 `WrapLayout` + 单个 `setVisible(false)`，
不在同一个坑里。）

### 探针

`LayoutProbe` 新增两组成员（`headerExperiment` + `blockMinimumHeight`），并**修了探针自己的一个弱点**：
原来头部用的是普通 `FlowLayout` —— 那正好是被裁掉第二行的那个形状，探针却测不出来，
因为它量的是「直接子项有没有越界」，而折出去的行根本不在 `preferredSize` 里。现在头部也用 `WrapLayout`。
`run.sh` 加一条防呆：**`javac` 失败就整体退出**。
本轮写 `StatusRow` 时 `@JvmStatic` 少了 `@JvmOverloads`，Java 侧三参调用编不过，
而 `run.sh` 照旧跑了一份**上一次留下的 CoreProbe.class**，报出 124 全绿 ——
假绿就是这么来的。`meta.py` 加 4 条钉子（头部不许回到一件一行、两个按钮不许复活、
状态行必须走共用规则、底部三页必须有图标）。

现在：**134（纯逻辑）+ 33（真子进程）+ 36→45（元数据）+ 布局 72 个位置 0 裁切 / 尺寸断言全过**。

## 40. 1.8.0 又一条回报：「自测为什么修改不了时限、内存」

他一句话里两个症状，根子是**两个不同的 bug**，而且都是我自己的假设造成的。

**1）内存字段真的被禁用了 —— 因为测量器在他机器上探测失败。**
我探测「这台机器能不能量子进程峰值内存」时，跑的是 `/usr/bin/time -l /bin/true`。
`/usr/bin/time` 在（`ls -l` 过），但 **`/bin/true` 在他这台 macOS 上不存在**：

```
ls: /bin/true: No such file or directory      存在 /usr/bin/true
```

探测拿不到输出 → `available()` 返回 null → 按设计把内存字段 `isEnabled = false` → 界面表现就是「改不了」。
修法不是改成 `/usr/bin/true` 就完事（那只是换一个假设），而是**列一组候选挨个试**：
`/usr/bin/true`、`/bin/true`、`/bin/pwd`、`/bin/echo`，每个都先 `File.isFile` 再真跑，
**唯一验收标准是「能解析出峰值」**。这正好是他反复讲的那条：别靠猜环境做适配。

**2）时限不是被禁用，是被**覆盖**回去了。**
「他改过就别用题面值盖」这个标记（`limitsTouched`）原来只在**失焦**时才置真。
而他改上限的时候，编辑器那边存一次盘就改了**磁盘签名** → 每秒那次自动重探触发 →
`applyTarget` 看见 `limitsTouched == false` → 把题面的 `1000 ms` 盖回去。
所以「敲了、没走开、值就变了」。改成 **一敲字就算改过**（挂 `DocumentListener`，`LimitFields.markWhenTyped`）。

顺带第三条是我自己埋的：上一轮把对拍页头部压成一行时，
`limitsRow()` 变成了**没人调用的死代码**，而那两个失焦监听就挂在这个死函数里 ——
于是对拍页的上限字段**一个监听都没有**，任何一次重探都会盖掉他的输入。
`meta.py` 现在钉住这条（`limitsRow` 不许存在 + `LimitFields.markWhenTyped` 必须在）。

还有一条关于**探针本身**的教训，比前面几条更要紧：`ProcessProbe` 里内存那组断言写成
`if (meter == null) System.out.println("SKIP …")`。这台机器上测量器探测失败 →
九条断言被**静默跳过**，而输出末尾照旧「33 通过 / 0 失败」。假绿就是这么来的。
现在改成 `check("这台机器能探测到峰值内存测量器", meter != null)` —— **不许 SKIP，测不到就是失败**。

同时把「量不到就禁用字段」这个设计本身也撤了：一个灰掉的输入框读起来像插件坏了，
更好的做法是让他填、值照显示，然后在概览里写「128 MB（这台机器量不到子进程内存，不生效）」，
判定路径不变（峰值为 null 时绝不判 MLE）。

探针：**142 + 34 + 50** 全绿（`LimitFields` 的规则、`unenforcedText`、概览那两行、
`meta.py` 三条钉子），`javac` 失败会整体退出这条防呆本轮也第一次真的生效了
（Java 侧 `Function0<Unit>` 的 lambda 写法编不过，runner 直接停下并说明「跑的是旧 class」）。

## 41. 1.8.0 发版

他说「发版，测试完成了」，跑的是仓库自带的 `scripts/release.sh`（没有手搓 gh 命令）。
本机 `bash probes/run.sh` 先过一遍：**142（纯逻辑）+ 34（真 clang++ 真子进程）+ 50（元数据）+ 布局四档 0 裁切**。

发出去的六步与复核（每条都是自己取回来的，不是看脚本回声）：

| 项 | 结果 |
| --- | --- |
| Release | `v1.8.0`，标题 `ClionLuogu 1.8.0`，资产 `ClionLuogu-1.8.0.zip` 4,602,191 字节 |
| 资产可达 | `curl -sIL` 跟完 302 → `HTTP/2 200`，`content-length` 与资产大小一致 |
| 更新通道 | `raw.githubusercontent` 与 jsDelivr **两边都是 `version="1.8.0"`**（purge 返回 `status: finished`，CF/FY 都 true） |
| 下载链 | 通道里的 url 指 `cdn.jsdelivr.net/...@v1.8.0/dist/ClionLuogu-1.8.0.zip`，实测 200 / 4,602,191 字节 |
| 包内描述符 | `<version>1.8.0</version>`；`<depends>` 恰好两条（platform + jcef）；`<toolWindow>` 两条（`ClionLuogu left` / `ClionLuoguRun bottom`）；`compat/` **0 条**；`css/preview.css` 在 |
| 新类 | 12 个全在包里（ProcessRunner / SelfTestService / SelfTestPanel / SelfTestInputService / LocalRunGates / SubmissionTracker / LuoguRunToolWindowFactory / ResourceMeter / ProblemLimits / LocalRunSignature / StatusRow / LimitFields） |
| change-notes | release.sh 同款非贪婪正则切出的首条 = 1.8.0，长 1287 字符、**不嵌 `<li>`**（嵌了就会被截断） |
| git | `5c37c71 release` + `f43203e chore: 仓库指向 v1.8.0` 都已推；`HEAD == origin/main`；tag `v1.8.0` 在远端 |

这一版的三件事：**窗口分家**（左题目栏 6 页签 / 底部运行栏 3 页签）、**自测**（手打输入跑一份，输入按题号记住、每次重编、不比期望输出）、
**可设时空上限**（默认取 `Pxxx.md` 那两行；内存实测外部 `time`，量不到就显示但写明不生效，判「超内存」借评测页 MLE 的颜色）。
附带修的：提交记账脱离评测页（原来从底部提交而左侧窗口没开过会整个不落盘）、拉完题不再报「项目根没有 Pxxx.cpp」、
时限/内存两个字段改不动（`/bin/true` 不存在 + 只在失焦才标记改过 + 一段死代码带走了监听）。

**探针这一轮最大的收获不是新增断言，是抓出两种假绿**：`javac` 失败后 runner 照旧跑旧 class 报「全绿」；
以及环境依赖的检查写成 `SKIP` 时，九条断言静默跳过而末尾照样「33 通过」。两条都已经改成硬失败。

只能他在 IDE 里确认的（发版后照旧）：左侧栏第一次要不要手动拖；底部窗口默认高度下三个页签是不是一进来就看得见内容；
明暗两套主题的判定颜色；预览页新 CSS 与无 JCEF 兜底；真实 CE 的跳行；超内存那行与孤儿进程（递归杀只有真 IDE 能验）。

## 42. 1.8.1：底部运行栏不许挤左侧栏（插件替他开 IDE 的宽屏布局）

他那句「CLionLuoguRun 我希望不要挡到左侧的 CLionLuogu（就是只占右边代码编辑区）」我**整条漏了** ——
上一轮只处理了「臃肿」和「专属 UI」两条，没做这条也没说我漏了。这条不是排版问题：
JetBrains 的布局规则是**上下条横跨整宽、侧边条被夹在中间**，插件改不了自己那条带占多宽。

平台自带反向开关：`UISettings.wideScreenSupport`（界面里
`Settings → Appearance & Behavior → Appearance → Widescreen tool window layout`，
也可以 ⌘+点击分割条临时切）。开了之后**侧边占满全高、上下条只占中间编辑区的宽度**，正好是他要的。
证据链（都是在这版发行包里查的，不是凭印象）：
`AppearanceConfigurableKt` 里那个复选框 `cdWidescreenToolWindowLayout` 绑的是 `getSettings()` 上的一个
`KMutableProperty0`，`UISettings` 上对应的就是 `getWideScreenSupport()/setWideScreenSupport(boolean)` +
`fireUISettingsChanged()`。
（`getInstanceOrNull()` 在 javap 里有、Kotlin 侧解析不到，所以用 `getInstance()` 包一层 `runCatching`。）

他选的是「插件帮你把这个开关打开」，所以我做了 —— 但**写用户的 IDE 全局偏好必须有自律**，三条：

1. **只在「现在是窄屏布局」且「我们从来没动过」时改**：判据抽成纯函数
   `WideLayout.shouldApply(currentlyWidescreen, alreadyAdopted)`，四条真值全进探针
   （其中一条就是「他后来自己关了，我们绝不再打开」——不跟用户抢方向盘）；
2. 动过一次就把 `LuoguSettings.wideScreenLayoutAdopted` 记下来，**永不再动**；
3. 通知里给一条 **「撤销（关掉宽屏布局）」**，一键回到原样，正文写清楚这个开关在 IDE 哪儿。

时机选在**底部运行窗口的内容第一次被创建**时（`LuoguRunToolWindowFactory`），
而不是启动活动 —— 没用到这个窗口就不该碰他的布局。

另外这一版是被权限层拦下来过一次：改全局 IDE 偏好这种动作，即使计划里选了，
也要**当轮明确确认**再落地。我停下来问了一句，他回「好的」之后才写。

探针：**146（纯逻辑，含 `shouldApply` 四条真值）+ 34（真子进程）+ 55（元数据）** 全绿，布局四档 0 裁切。

只能他验的：更新到 1.8.1 后，第一次打开底部运行栏应当弹一条通知、左侧栏立刻不再被夹短；
点「撤销」应当恢复原布局且**之后启动不再弹**。


### 追加（同一版）：三栏、时限口径、回车

他接着提三条。

1. **「概览和 stderr 应该放在一个单独的窗口里，左输入 / 中输出 / 右概览」** —— 自测页从两栏变三栏：
   `stdoutPanel` 只放 stdout，`sidePanel` 放概览 + 编译诊断 + stderr；两层都用 `AutoFlipSplitter`，
   窗口被拖窄时每层各自塌成上下排。断言钉住「右边那栏不许再放一份 stdout」（重复最糟）。
2. **「时限不包括编译时间」** —— 代码本来就是这样（编译走 `COMPILE_TIMEOUT_MS`，运行走用户设的 `timeoutMs`），
   **问题在界面没说**：等了好几秒会以为 1000 ms 把编译算进去了。现在概览拆三行
   「时限（不含编译）/ 编译 · X ms / 运行用时 · Y ms」，字段 tooltip 同步写明。
3. **「增加回车的适配性，有些地方无法通过回车确定」** —— 把所有输入框审了一遍：拉取页题号、搜索页关键词、
   登录页两栏、自测与对拍的时限/内存字段原来都不吃回车，逐个补上；登录页做成「第一栏回车跳第二栏」
   而不是直接登录（两栏都得粘）。**提交页的语言下拉与「提交」按钮故意不给回车**（写操作要实点），
   并把这句理由写进代码注释，免得以后有人当 bug 修掉。

另外一件必须交代的：动手前工作区里已经有一份**没写完的三栏改动**（`SelfTestPanel.kt` 改了 51 行，
引用不存在的 `scroll()`、`head`/`actions` 的构造被删掉却还在用，编不过）。**它不是我这两轮写的**，
来源我说不清。我没有回退它，而是接着收尾：补 `scroll()`、把 `head`/`actions` 挂回去、
补 `commitLimits()` 与右栏的 stderr。收尾后 `run.sh` 的 javac 防呆立刻抓到两份探针还在用被换掉的
`sections(...)`（现在是 `outputSections` / `sideSections`），改完 **151 + 34 + 58 全绿**。

## 43. 1.8.2：预览页对洛谷的适配（JCEF 判定、题面走 Markdown、元信息不粘连）

他贴了一份 B2002 的题面文本：元信息糊成一句「难度普及-时间限制1000 ms内存限制131072 KB分数…」、
`[受信任的用户](https://help.luogu.com.cn/…)` 按字面显示、正文没有列表排版，然后说
「**优化一下适配，改成 JCEF + 对洛谷的适配**」。

### 三条根因，一条是我自己差点造出来的

1. **JCEF 被误判成「这台机器没有」**。面板原来是这么写的（1.4~1.8.1 都是）：
   ```kotlin
   private val browser: JBCefBrowser? = initBrowser()   // 字段初始化，问一次定死
   private fun initBrowser() = if (PluginManagerCore.isPluginInstalled(...) && JBCefApp.isSupported()) JBCefBrowser() else null
   ```
   取证（都在他机器上读到的，不是印象）：
   - `~/Library/Logs/JetBrains/CLion2026.2/idea.log` 里 `#c.i.u.j.JBCefApp` 打出过
     `jcef version: remote_144.0.15.3416…`，`--framework-dir-path=…/plugins/jcef-plugin/jcef/Frameworks/…` —— **他的 CLion 有 JCEF，而且起得来**；
   - 那句日志的时间是**启动后 3.8~9.7 秒**（10-03 是 +3816ms，10-04 是 +5240ms/+9698ms）。
   - `plugins/jcef-plugin/lib/jcef-plugin.jar/META-INF/plugin.xml`：`<id>com.intellij.modules.jcef</id>`，
     而真正把 JCEF 拉起来的 `applicationService class="…JBCefStartup" preload="notHeadless"` 挂在
     `intellij.platform.ui.jcef` 这个**延迟加载模块**上（`lib/modules/intellij.platform.ui.jcef.jar`，V2 模块化布局）。
     于是「工具窗口在启动早期建面板 → 那句 `isSupported()` 问得太早 → false 被字段永久缓存」。
   - 顺带一句 `isPluginInstalled("com.intellij.modules.jcef")`：那个 id 确实作为 bundled plugin 存在，
     但它是不是被这个 API 认成「已安装」我没有证据，而且**逻辑上多余**——`<depends>` 已经硬依赖它，
     真缺的话插件根本加载不上，走不到这一行。删掉。

   **我自己差点写进 Report 的错误结论**：先按「JBR 里搜 jcef」判断，`find CLion.app -iname "*jcef*"` 从
   `/Applications/CLion.app` 根目录跑**返回空**，我据此准备写「你这台 CLion 的运行时不带 JCEF」。
   两条推翻它：日志（上面那行 framework-dir-path）与直接 `ls Contents/plugins | grep jcef`。
   原因是那个从根目录跑的 find 本身不可信（同一条件从 `Contents/plugins` 起跑就命中）。
   **教训：一条 `find` 的空结果不能当证据，尤其是 2026.x 把原生件从 JBR 挪进了 `plugins/`。**

2. **题面正文从来没进 Markdown 解析器**。`body.section("description")` 拿到的是洛谷那套方言的原文，
   直接拼进 HTML —— marked 只挂在题解那条路上。所以 `[文字](链接)`、`- 列表` 原样显示。
3. **元信息靠 CSS 分开**。`<span class="chip">` + `display:inline-block; margin-right:6px` 是 JCEF 里才成立的排版；
   兜底的 `JEditorPane` 只认 CSS 2.1（丢 display、丢 margin），而**复制走的更是纯文本**，
   `<b>难度</b>普及-` 就粘成了他那句「难度普及-时间限制…」。

### 改法

- `browser()` 懒建：`browserCache` 为空才问 `JBCefApp.isSupported()`，问到能用为止；`dispose()` 判空释放。
- 题面每段（背景 / 描述 / 输入格式 / 输出格式 / 提示）交 marked：`bodyBlocksHtml(blocks, usesJcef, literal)` 生成
  `<div class='md' data-luogu-md='luogu-md-N'>原文</div>` + 同名 `<script type='application/json'>` 载荷。
  **宿主 div 里先摆着原文**，marked 万一没跑，读到的是原文而不是空白；
  `rendererScripts()` 只在 JCEF 路径注入 marked + 渲染脚本（兜底绝不能带脚本，`JEditorPane` 会把 JSON 当正文打印）。
- 元信息：`metaChipsHtml(cells)` → `<p class='chip'><b>难度</b>：普及-</p>`。
  一行一枚靠**块级标签**（JCEF 里 CSS 收成 inline-block，兜底里就是天然的一行一枚），
  键值分隔符**写在标记里**而不是靠 CSS —— 这样复制出来的纯文本也是「难度：普及-」。
- 链接一律摘 `href`、去处留在 `title`（`neutralizeLinks`）：这块正文嵌在 IDE 面板里，
  一点就把整块预览导航到洛谷官网，得重新双击才能回来；危险协议（`javascript:` 等）连去处都不显示。
  与 Kotlin 侧 `sanitize()` 原本就剥 href 的口径一致。
- 兜底那句话改成「JCEF **此刻**不可用…稍等重新双击这道题即可」，不再写「这台机器的 JCEF 不可用」——
  按根因 1，那句话在启动早期是**假的**。

### 你提的两处，处置不同（说清为什么）

- **「懒建 browser」= 采纳**，就是上面第一条；`isPluginInstalled` / `findId("com.intellij.javake")` 那两行
  在工作区里已经不存在（`grep -rn` 空），`javake` 与 JCEF 也无关。
- **「`<depends>` 改 optional + config-file」= 不改**，三条理由：
  ① 它是**运行时可用性**声明，不是编译期 classpath 开关 —— `compileKotlin` 现在就能编过
  （`build.gradle.kts` 里 `intellij { modules = listOf("com.intellij.modules.jcef") }` 才是管 classpath 的那一处）；
  ② 硬依赖不可能造成「JCEF 不可用」—— 模块真缺时 IDE 判依赖不满足，**整个插件不会加载**，
  症状是「左侧找不到 ClionLuogu」而不是「题面是纯文本」；他的日志里插件正常加载（`1.8.1`）。
  ③ 真改成 optional，`jcef-support.xml` 里就得放一份**不引用 `JBCefBrowser` 的预览面板**，
  否则模块缺失时加载 PreviewPanel 直接 `NoClassDefFoundError` —— 那是把「加载不了」换成「加载了但预览整块没了」，
  而现在这条路径已经有兜底（JEditorPane + 原文）。另外「`<depends>` 恰好 platform + jcef」是他自己定的长期约束。
- **`@Volatile` 不加**：`browserCache` 只在 `browser()` / `dispose()` 里碰，而这两条都在 EDT 上 ——
  `LuoguActions` 每个回调都是 `invokeLater { … }`（`grep` 24 处），不存在后台线程读到旧 null 的窗口。

### 探针

- `CoreProbe`：`previewHtmlRule()` 14 条 —— 一枚一个 `<p>`、旧的 span 形状不许回来、
  分隔符在标记里、宿主 id 与载荷 id 成对、**兜底一个脚本都不嵌**、宿主里有原文、
  `jsonLiteral` 把 `</script>` 写成 `<\/script>`（否则载荷提前闭合，后面整页脚本报废；
  为此把 `jsonLiteral` 挪进 companion 让探针能直接钉）。**151 → 165 全绿**。
- 新开 `probes/js-render-check.mjs`（14 条）：真的把 `marked.min.js` 载进 `node:vm` 沙箱跑 `renderMarkdown`，
  断言 `[受信任的用户](…)` 变成 `<a>` 且没有 href、`- 列表` 变成 `<ul><li>`、
  `$a_1 * b_2$` 原样交给 MathJax 且不长出 `<em>`、脚本与 `onerror` 被剥、`javascript:` 不可点也不显示去处。
  `run.sh` 加了 `js` 套件：`LUOGU_NODE` → PATH → `~/.nvm/versions/node/*/bin/node` 依次找，
  **找不到就在末尾明说「这部分等于没验证」**（1.8.0 两次假绿都是跳过不吭声）。
  我这边的沙箱看不见 `~/.nvm`（`ls` 与执行都报不存在），所以这 14 条是我用 node-repl MCP `await import()` 那**同一份文件**跑的：
  `JS 正文渲染探针：检查 14 条，失败 0 条`。他终端里 `node probes/js-render-check.mjs` 应当同样全绿。
- `meta.py`：版本 1.8.2、change-notes 首条是 1.8.2 且 1.8.1 那条没丢、
  Kotlin 与 JS 两侧 `data-luogu-md` 成对、题解原来的 DOM 契约（`luogu-solution` / `luogu-md`）没动、
  代码里不许再出现 `private val browser` / `isPluginInstalled` / `javake` / `findId`、
  发布包里 `solution-render.js` / `marked.min.js` / `tex-svg.js` 都在。
  两条顺手修的真 bug：`inner[0]` 在版本号刚改还没 `buildPlugin` 时**直接崩**，看起来像「元数据没问题」；
  断言被 KDoc 里引用的旧代码字样命中（懒建的反例正好写在注释里），改成只看代码行。

### 只有他能验的

JCEF 的延迟初始化窗口、marked 排版出来的实际观感、以及「链接摘了 href 之后 IDE 里点题面链接不再跳走」
都只能在真实 IDE 里看；手点清单在交付那条消息里。

**补一条硬证据（这一轮查的，关于「要不要把 `<depends>` 改成 optional」）**：
`com/intellij/ui/jcef/JBCefBrowser.class` 与 `JBCefApp.class` **只在**
`plugins/jcef-plugin/lib/modules/intellij.platform.ui.jcef.jar` 里 ——
把 `Contents/lib/*.jar` 全扫了一遍，核心 jar 里一个都没有。
所以「optional 依赖时 `JBCefBrowser` 类仍可解析」这个假设在这台发行包上不成立：
模块没 loaded 时，任何引用它的类（我们的 `PreviewPanel` 就有 `browserCache: JBCefBrowser?` 字段）
在链接阶段就 `NoClassDefFoundError`。真要做 graceful，必须把碰 JCEF 的那半边挪进
`config-file="jcef-support.xml"`、主描述符里只留一份纯文本面板 —— 那是另开一轮 structural 改动，
不是改一行 plugin.xml。
反过来说：正因为现在是**硬依赖**，模块缺失时 IDE 直接不加载插件（症状是「找不到 ClionLuogu」），
我们才敢在 `browser()` 里只问一句 `JBCefApp.isSupported()`。

## 44. 1.8.2（第二批）：本地编译改用 CLion 选的编译器 + 「这台 mac 不是 GCC」检查 + 头文件按编译器走

他两条需求：①「mac 且 CLion 编译器非 GCC 时提示 `brew install gcc` 然后使用；自测和对拍改成用 CLion 选的编译器」
②「clang++ 就保持标准头，不是 clang++ 或不是 mac 就用 bits」。

### 先把这台机器的编译器事实测出来（不测就会写错）

| 可执行文件 | `--version` 首行 | 该怎么判 |
| --- | --- | --- |
| `/usr/bin/g++` | `Apple clang version 21.0.0 (clang-2100.3.34.2)` | **clang**（名字骗人） |
| `/usr/bin/clang++` | `Apple clang version 21.0.0 (clang-2100.3.34.2)` | clang |
| `/opt/homebrew/bin/g++-16` | `g++-16 (Homebrew GCC 16.2.0) 16.2.0` | **GCC**（他今天 19:53 才 `brew install gcc`） |

实测编译（同一份 `#include <bits/stdc++.h>` + `accumulate`）：
`g++-16` 编过并跑出 `3`；`clang++` 第一行就 `fatal error: 'bits/stdc++.h' file not found`。
两条结论直接决定了实现：
1. **身份只能按 `--version` 判，不能按文件名** —— 按名字认会把 `/usr/bin/g++` 当 GCC，于是给 clang 塞 bits；
2. **brew 装完没有裸 `g++`**，只有 `g++-16` —— 提示文案必须写这句，否则他装完发现「还是没用」。

### 编译器怎么找：读 CMakeCache，不碰 CLion 的 API

`ClionToolchain` 只读 CMake 自己写的 `CMakeCache.txt`（项目根 + 一层子目录里最近修改的那份），
取 `CMAKE_CXX_COMPILER`。CLion 的 profile / `-D` / 工具链选择最后都落到这一行，所以它就是
「IDE 里到底用谁编的」；而 `com.jetbrains.cmake.*` 那些类在 CLion 自己的 bundled module 里，
依赖它就得再往 `<depends>` 上加一层、把插件绑死在某个 CLion 版本上。
解析时那几行同名前缀全要避开：注释行、`CMAKE_CXX_COMPILER-ADVANCED`、`_ARG1`、
以及配置失败时的 `CMAKE_CXX_COMPILER-NOTFOUND`（这条要是不避，值就成了字符串 `CMAKE_CXX_COMPILER-NOTFOUND`）。

`detect()` 的优先级：**设置里填的 > CLion 选的 > PATH/MinGW**。
每一级都带出处（`Origin`），界面那一行写成 `编译器：clang++（Apple clang…）· CLion 选的 · 建议装 GCC`；
后缀只 7 个字是因为**头部只有一行高**（1.7.1/1.7.2 两次被裁掉的都是第二行），全文在悬停里。
「设置里填的不可用」这句话在**任何**回落路径上都得说（包括改用 CLion 的那一个）——
静默换编译器是最难自己发现的那种错。

### 检查与提醒

判据抽成纯函数 `shouldRecommendGcc(isMac, flavor) = isMac && flavor != GCC`；
`UNKNOWN` 也算「不是 GCC」（mac 上探测失败的基本就是系统那套 clang，而这条只是建议、不拦路）。
通知**按编译器路径去重**、只发一次（`gccAdviceShownFor`），理由是：他换成 `g++-16` 之后这事就不成立了，
而换回 clang 或 brew 升了版本号算**新情况**，值得再提一次。面板那行常驻，通知会被关掉而界面不会。

### 头文件按编译器走，以及那个不对称

`defaultCodeTemplate(isMac, flavor)`：mac 且不是 GCC → 标准头；其余 → bits。
唯一跟他原话有出入的一处是**「还没探测到编译器时」**：我给的是标准头而不是 bits。
不对称在这里——标准头在 GCC 上照样编得过（探针真编了一遍），bits 在 clang 上第一行就炸；
拿不准时选那个「猜错也不出事」的。这条在 KDoc 和 change-notes 都写明了。

另一处坑是自己造的：`codeTemplate` 的 getter 变成按编译器之后，设置页显示的是「当时那套默认」，
而后台探测随时可能把 `lastFlavor` 换掉 → `isModified()` 以为他改过 → 他顺手点 Apply →
那份旧默认被当成「自定义模板」存下来，从此**换编译器也不再换头文件**。
所以 setter 里加了「内容等于任一份内置默认就不算自定义」。

### 顺手抓到一条真 bug：`loadState` 漏镜像

`LuoguSettings.loadState()` 抄了 6 个字段，**漏了 1.8.1 新加的 `wideScreenAdopted`**。
写出去是好的（`options/clionluogu.xml` 里确有 `wideScreenLayoutAdopted=true`），读回来丢了。
当时没暴雷是因为 IDE 自己把宽屏布局也持久化了（`options/ui.lnf.xml` 里 `WIDESCREEN_SUPPORT=true`），
判据 `!currentlyWidescreen` 恰好还是 false；可只要他**自己关掉宽屏布局再重启**，
插件就会再打开一次 —— 正是那段代码承诺过不干的事（跟他抢方向盘）。
补了一行镜像，并加了 `settingsRoundTrip`：逐个字段写出去再读回来必须还一样，
`meta.py` 里另有一条「每个 private 后备字段都必须出现在 loadState 里」的结构检查（防止下次又漏）。

### 他追问出来的两个洞（都补了）

1. **「第一次拉题时 `lastFlavor` 还是 UNKNOWN，于是永远先拿标准头」** —— 这个洞是真的。
   他建议加 `ProjectActivity` 在项目打开时预探测；我做的是**在 `fetchAndGenerate` 的后台线程里、
   切回 EDT 之前**补一次 `ensureCompilerFlavor(project)`（读 CMakeCache + 一次 `--version`，同一路径有缓存）。
   理由：`generateFiles` 在 EDT 上、不能再起子进程，而拉题流程本身就在后台线程，顺手就能准备好；
   启动预探测则每次打开项目都要付一次进程开销，而他可能这一整个 session 都不拉题。
   两条都治这个洞，选便宜的那条。（底部运行栏第一次出现时 `probeCompareTarget` 也会准备一次，两边都覆盖到了。）
2. **「换了 CLion 的编译器，界面还挂着旧编译器的探测结果」** —— 源码与样例一个字没动时，
   每秒的签名看不出任何变化，「改了没用」会第三次发生。
   签名加了第三项 `cache:<mtime>`（只 `stat`，绝不读内容），`CMakeCache.txt` 一变就重探。
   为此把 `cacheFile` 改成**两段找、命中就早退**（先 `cmake-build*` 与项目根，再退到子目录广撒，
   仍然有 80 个的上限、仍然不递归），免得每秒把项目根的子目录全 `stat` 一遍。
   四个调用点统一走 `LocalRunSignature.ofProject(basePath, pid)`：
   **每秒算的那串与探测回来后写 `diskSignature` 的那串必须是同一个口径**，
   否则每秒都不相等 → 每秒重探 → 每秒抢滚动位置（1.7.4 那次的形状）。

### 探针

`CoreProbe` 165 → **230**：`compilerFlavorRule`（6 份真实 `--version` 首行 + 提醒的真值表 + 文案要点）、
`clionCacheRule`（cache 文本的四个陷阱、多 profile 取最近修改、目录里没有 cache / null / 不存在都不炸）、
`codeTemplateRule`（四种组合 + 「UNKNOWN 退回标准头」那个不对称 + bits 那份只有一行 include）、
`settingsRoundTrip`（逐字段写出→读回，含「内置默认原样存回 ≠ 自定义」）、
`compilerLineRule`（出处字样、后缀不吃掉前缀、回落说明优先于建议、PATH 兜底要解释原因）、
`signatureStability` 里新增的 cache 那 7 条（cache 出现 / mtime 变 / 不存在的文件 / `ofProject` 同一口径）。
`ProcessProbe` 34 → **57**：真起进程验优先级与回落报备、`lastFlavor` 跟着变、
从真写的 cache 端到端读出可执行文件，以及**真编译**：`g++-16` 编过 bits 模板、
同一份模板给 clang 必然失败且诊断里有那句提示、标准头在 GCC 上也编得过。
这台机器没有 GCC 时那组打一行 `NOTICE`（大声跳过，不静默）—— 上一版「假绿」就是这么来的。
`meta.py` 87 → **122**：多了一条**结构性**的「每个 `private var` 后备字段都必须出现在 `loadState` 里」
（1.8.1 漏 `wideScreenAdopted` 那类 bug 从此不再是靠人记得住），
以及 `ClionToolchain` 只 import `java.*`、面板不许残留两参数签名、
`ensureCompilerFlavor` 必须排在 `invokeLater { generateFiles }` 之前。


