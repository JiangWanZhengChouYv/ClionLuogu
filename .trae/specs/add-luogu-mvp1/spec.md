# CLion 洛谷插件 MVP1（拉取题目 + 生成本地测试文件）Spec

## Why

在 CLion 中刷洛谷题时，需要在「浏览器切到题目 → 手工复制样例 → 粘贴成 .in/.out」之间反复横跳，这个过程完全机械化、且极易因样例末尾空格/换行处理不当导致本地验证失真。本 spec 交付一个最小可用插件：输入题号即可自动拉题、按约定命名生成 `Pxxxx.cpp / Pxxxx.in / Pxxxx.out` 并自动打开代码文件，把上述手工步骤压缩为一次弹窗操作。

本 spec **只覆盖第一版 MVP**，不包含登录与代码提交。

## 背景与已核实事实

以下内容已通过真实请求实测（`GET https://www.luogu.com.cn/problem/P1001`）确认，是本次设计的依据，不是推测：

| 项 | 实测结论 |
| --- | --- |
| 接口形态 | 洛谷**无官方开放 API**，题目数据来自网页 SSR 渲染结果 |
| `_contentOnly=1` | **已失效**。带该查询参数与不带，返回体完全相同（均为 43441 字节 `text/html`），不再返回 JSON |
| 反爬机制 | 无 Cookie 直连返回 `302`，`Set-Cookie: C3VK=xxxx; Max-Age=300` 并重定向到同一 URL。**必须持有 Cookie 容器并跟随重定向**，否则死循环拿不到内容 |
| 数据位置 | 响应 HTML 中 `<script id="lentille-context" type="application/json">{...}</script>` 承载全部页面数据 |
| 数据路径 | `data.problem`，字段实测为：`pid, type, name, difficulty, fullScore, tags, totalSubmit, totalAccepted, flag, provider, contenu, content, attachments, showScore, acceptSolution, acceptLanguages, samples, limits` |
| 样例结构 | `samples` 是**二维数组** `[["20 30\n", "50\n"]]`，即 `List<[输入, 输出]>`，**不是** `{input, output}` 对象数组 |
| 限制字段 | `limits.time` 单位毫秒（`[1000, ...]`），`limits.memory` 单位 KB（`[524288, ...]` = 512MB） |
| 状态字段 | 顶层 `status`（实测 200）、`template`（实测 `problem.show`）、`time`、`instance`、`locale`、`user` |
| CSRF | `<meta name="csrf-token" content="1789390426:2uW...=">`，格式为 `<时间戳>:<token>`。**MVP1 不需要**，为 MVP2 提交预留 |
| 公开题目鉴权 | 查阅公开题目**无需登录**，`user` 字段为空即游客态 |

### 需要修正的原始方案

1. **JDK 版本：17 → 21。** 官方版本对照表明确写有 IntelliJ Platform `2024.3`（branch `243`）的 Java 版本为 **21**（`2024.2+` 起均为 21，仅 `2024.1` 及更早为 17）。用 JDK 17 编译面向 243 的平台类库会直接报 `class file has wrong version 65.0`。本 spec 因此采用 **JDK 21 toolchain**；若坚持 JDK 17，则必须把目标平台降级到 CLion 2024.1（branch 241），二者不可兼得。
2. **JSON 解析路径：** 不能按「请求 `?_contentOnly=1` 直接拿 JSON」去写，必须先取 HTML，再从中抽取 `lentille-context` 脚本块。
3. **DTO 结构：** `samples` 必须按 `List<List<String>>` 建模，否则反序列化必失败。
4. **样例数量：** MVP1 只使用**第一个**样例生成 `.in/.out`（文件名固定为 `Pxxxx.in/.out`，不含序号），多样例留待后续版本。

## What Changes

- 新建 IntelliJ Platform 插件工程（Kotlin + Gradle，plugin id `com.user.clionluogu`，目标 CLion 2024.3 / since-build 243）。
- 新增可丢弃的独立 CLI 原型工程 `prototype/`，用于在写插件前用同一套技术栈（OkHttp + kotlinx.serialization）验证拉题链路；**首次提交前删除**。
- 新增 `api` 层：`LuoguHttpClient`（OkHttp + 内存 CookieJar + 浏览器伪装请求头）、`dto.kt`（`@Serializable` 数据类）、`LuoguApiService`（`suspend` 拉题）。
- 新增 `settings` 层：`LuoguSettings`（应用级持久化，保存 C++ 代码模板）+ `LuoguConfigurable`（Settings 页可编辑模板）。
- 新增 `service` 层：`ProblemFileGenService`，生成 `Pxxxx.cpp / Pxxxx.in / Pxxxx.out`。
- 新增 `action` 层：`FetchProblemAction`，弹窗输入题号、后台拉取、生成文件、自动打开。
- 更新 `plugin.xml`：注册动作到 `EditorPopupMenu` / `ProjectViewPopupMenu` 与主菜单 `Tools` 分组。
- 初始化 Git 仓库并推送 GitHub `origin main`（**推送前删除 `prototype/`**）。

### 本次非目标（明确不做）

- 登录、Cookie 安全存储（`SecureCookieStore`）、`SubmitCodeAction`、评测轮询、侧边工具窗口 `LuoguToolWindow`（均为 MVP2）。
- 生成 `CMakeLists.txt` / 自动配置 CLion 运行目标。因此生成的 `.cpp` 在 CLion 中需用户自行纳入 CMake 目标才能直接运行调试。
- 多语言模板、多样例分文件、题目正文渲染。

## Impact

- Affected specs: 无（本仓库首个 spec）
- Affected code: 全新工程，将创建
  - `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`
  - `src/main/kotlin/com/user/clionluogu/{action,api,service,settings}/`
  - `src/main/resources/META-INF/plugin.xml`
  - `prototype/`（临时，提交前删除）
  - `.gitignore`、`README.md`

---

## ADDED Requirements

### Requirement: R1 插件工程骨架

系统 SHALL 提供一个可构建的 IntelliJ Platform 插件工程，使用 Kotlin、Gradle，插件 ID 为 `com.user.clionluogu`，构建目标为 CLion 2024.3（branch 243）。

#### Scenario: 构建通过
- **WHEN** 在工程根目录执行 `./gradlew buildPlugin`
- **THEN** 构建成功，产物出现在 `build/distributions/`，且不出现「duplicate class」或 JDK 版本不匹配告警

#### Scenario: 构建目标与元数据正确
- **WHEN** 检查 `gradle.properties`
- **THEN** 包含 `platformType = CL`、`platformVersion = 2024.3`、`pluginSinceBuild = 243`、`pluginUntilBuild = 243.*`，且 JDK toolchain 为 21

### Requirement: R2 CLI 原型验证拉题链路

系统 SHALL 提供一个独立于插件构建的 `prototype/` Gradle 工程，使用 OkHttp + kotlinx.serialization，接收 `--pid` 参数并打印题目关键信息，用于在编写插件前验证接口可行性。该工程 SHALL 在首次 Git 提交前被删除。

#### Scenario: 原型成功拉到题目
- **WHEN** 执行 `./gradlew -p prototype run --args="--pid P1001"`
- **THEN** 标准输出包含 `P1001`、题目名 `A+B Problem`、样例数量 ≥ 1，以及第一个样例的输入输出内容

#### Scenario: 原型验证重定向与 Cookie
- **WHEN** 原型在无缓存 Cookie 的情况下发起首次请求
- **THEN** 请求最终返回 `200` 且拿到 `lentille-context` 数据块（证明 Cookie 容器与重定向跟随配置正确）

### Requirement: R3 HTTP 客户端

系统 SHALL 提供 `LuoguHttpClient`，封装 OkHttp，具备：内存级 Cookie 容器、跟随重定向、浏览器伪装请求头、连接/读取超时。

#### Scenario: 伪装请求头
- **WHEN** 发起任意洛谷请求
- **THEN** 请求携带 `User-Agent`（桌面 Chrome 形态）、`Accept-Language: zh-CN,zh;q=0.9`、`Referer: https://www.luogu.com.cn/`，不出现 `403` 拦截

#### Scenario: Cookie 容器跨重定向生效
- **WHEN** 首次请求收到 `302` 并携带 `Set-Cookie: C3VK=...`
- **THEN** 客户端自动保存该 Cookie 并在跟随的重定向请求中回传，最终得到 `200` 正文

#### Scenario: 超时兜底
- **WHEN** 网络不可达或响应超时
- **THEN** 抛出被业务层捕获的异常，不产生崩溃，超时时间不超过 15 秒

### Requirement: R4 题目数据获取与解析

系统 SHALL 通过 `LuoguApiService.getProblem(pid)` 以 `suspend` 函数形式返回题目数据，解析路径为：HTML → `lentille-context` 脚本块 → JSON → `data.problem`。

#### Scenario: 正常解析
- **WHEN** 传入合法题号 `P1001`
- **THEN** 返回对象中 `pid == "P1001"`、`name == "A+B Problem"`、`samples` 为 `List<List<String>>` 且首元素为 `["20 30\n", "50\n"]`

#### Scenario: 题号不存在
- **WHEN** 传入不存在的题号（如 `P99999`）
- **THEN** 返回可读的业务异常（如「题目不存在」），而非反序列化异常或空指针

#### Scenario: 数据块缺失
- **WHEN** 响应中找不到 `lentille-context` 脚本块（例如被反爬挑战页替换）
- **THEN** 返回明确异常，提示可能被拦截/需要重试，不静默返回空对象

#### Scenario: 未知字段容错
- **WHEN** 洛谷在 `data.problem` 中新增或删除字段
- **THEN** JSON 解析配置 `ignoreUnknownKeys = true` 与可选字段默认值，使解析不失败

### Requirement: R5 本地测试文件生成

系统 SHALL 提供 `ProblemFileGenService`，在项目根目录生成 `Pxxxx.cpp`、`Pxxxx.in`、`Pxxxx.out` 三个文件，并 SHALL 保护用户已有代码不被覆盖。

#### Scenario: 成功生成三个文件
- **WHEN** 对 `P1001` 执行生成，且项目根目录不存在同名文件
- **THEN** 生成 `P1001.cpp`（内容为代码模板）、`P1001.in`（第一个样例输入）、`P1001.out`（第一个样例输出），编码为 UTF-8 无 BOM

#### Scenario: 样例内容保真
- **WHEN** 写入 `P1001.in` / `P1001.out`
- **THEN** 内容与接口返回值逐字节一致（包含样例中已有的换行与空格），不做 trim 或换行归一化

#### Scenario: 不覆盖已有代码
- **WHEN** 项目根目录已存在 `P1001.cpp` 且其中含有用户代码
- **THEN** 该文件内容保持不变，仅覆盖 `P1001.in` / `P1001.out`

#### Scenario: 无样例题目
- **WHEN** 题目 `samples` 为空
- **THEN** 仍然生成 `.cpp`，`.in`/`.out` 生成空文件，并提示「该题无样例」

#### Scenario: 文件名来自题号
- **WHEN** 题号为 `P1001`、`B2001`、`CF1A` 等合法形态
- **THEN** 直接使用题号作为文件名主干，不做大小写改写

### Requirement: R6 可自定义代码模板

系统 SHALL 提供应用级持久化设置，保存生成 `.cpp` 时使用的 C++ 代码模板，并 SHALL 在 IDE 设置界面（Tools 分组）提供编辑入口。

#### Scenario: 默认模板可用
- **WHEN** 用户从未修改过设置
- **THEN** 使用内置默认 C++ 竞赛模板（含 `bits/stdc++.h`、关闭流同步、`int main` 返回 0）

#### Scenario: 模板可持久化
- **WHEN** 用户在设置页修改模板文本并点击 Apply/OK，随后重启 IDE
- **THEN** 修改后的模板被保留，且新生成的 `.cpp` 使用该模板

### Requirement: R7 拉题动作与交互

系统 SHALL 提供 `FetchProblemAction`，用户可通过编辑器右键菜单、项目视图右键菜单与主菜单 `Tools` 触发，弹窗输入题号后完成拉取与生成。

#### Scenario: 正常拉题并打开
- **WHEN** 用户在项目中触发动作并输入 `P1001`
- **THEN** 在项目根目录生成三个文件，并自动在编辑器中打开 `P1001.cpp`

#### Scenario: 题号格式校验
- **WHEN** 用户输入空字符串、仅空白字符或非法格式（如 `hello`、`1001` 不含字母前缀）
- **THEN** 给出即时提示并中止请求，不发起网络调用

#### Scenario: 失败有可读反馈
- **WHEN** 拉题因网络错误、题目不存在或反爬拦截而失败
- **THEN** 通过 IDE 通知（Notification）展示可读原因，不抛出未捕获异常，不打印到 stderr

#### Scenario: 无项目上下文
- **WHEN** 动作在无打开项目时被触发
- **THEN** 安全退出，不抛异常

### Requirement: R8 线程模型

系统 SHALL 保证所有网络请求不在 UI 主线程（EDT）执行，且所有 VFS 写入与编辑器操作在正确的线程与写锁下执行。

#### Scenario: 网络不阻塞 EDT
- **WHEN** 拉题请求耗时较长
- **THEN** IDE 界面保持响应，不出现 UI 冻结

#### Scenario: 正确的写入姿势
- **WHEN** 创建/写入文件
- **THEN** 通过写操作（write action）执行 VFS 变更，并在写入后刷新 VFS 以确保文件在项目视图中可见

#### Scenario: 编辑器操作在 EDT
- **WHEN** 执行自动打开 `P1001.cpp`
- **THEN** 该操作在 EDT 上执行，不出现线程断言错误

### Requirement: R9 仓库初始化

系统 SHALL 初始化 Git 仓库，忽略构建产物，并将代码推送到 GitHub `origin` 的 `main` 分支；推送前 SHALL 删除临时的 `prototype/`。

#### Scenario: 忽略构建产物
- **WHEN** 执行 `git status`（在构建之后）
- **THEN** `build/`、`.gradle/`、`.idea/`、`prototype/build/` 等均被忽略，不进入暂存区

#### Scenario: 提交前完成清理
- **WHEN** 创建首次提交
- **THEN** 提交内容中**不包含** `prototype/` 目录及其任何文件

#### Scenario: 推送 origin main
- **WHEN** 首次提交完成且远端已配置
- **THEN** 代码被推送到 `origin/main`；若远端尚未创建，则先记录待用户提供仓库地址，不擅自创建

---

## MODIFIED Requirements

无。

## REMOVED Requirements

无。
