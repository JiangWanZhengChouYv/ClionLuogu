# 开发记录：ClionLuogu 1.4.2「查看题解」+ 1.5.0「每日打卡」

> 从克隆仓库到两次发版的全过程记录。第 1–7 节是 `1.4.2`（tag `v1.4.2`，
> Release：https://github.com/JiangWanZhengChouYv/ClionLuogu/releases/tag/v1.4.2），
> 第 8–10 节是 `1.5.0`（打卡）。

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

