# Tasks

- [x] Task 9（修复，Task7 验证发现）: 修正 dto.kt 字段类型使 `getProblem` 真实解析成功（tags→List<Int>；删除 content/contenu/acceptLanguages/attachments/provider；已在线验证 P1001 解析成功）

- [x] Task 1: 搭建插件工程骨架
  - [x] SubTask 1.1: 基于 `intellij-platform-plugin-template` 生成 Gradle 工程（Kotlin、Gradle Wrapper）
  - [x] SubTask 1.2: 配置 `gradle.properties`：`pluginGroup=com.user`、`pluginName=ClionLuogu`、插件 ID `com.user.clionluogu`、`platformType=CL`、`platformVersion=2024.3`、`pluginSinceBuild=243`、`pluginUntilBuild=243.*`
  - [x] SubTask 1.3: 配置 JDK 21 toolchain（`java.toolchain` 与 Kotlin `jvmToolchain(21)`），移除模板自带的示例动作/示例工具窗口代码
  - [x] SubTask 1.4: 添加依赖：OkHttp、`kotlinx-serialization-json` 及 `kotlin("plugin.serialization")`
  - [x] SubTask 1.5: 编写最小 `plugin.xml`（`com.intellij.modules.platform` 依赖、`idea-version` 243），验证 `./gradlew buildPlugin` 通过

- [x] Task 2: 编写 CLI 原型验证洛谷拉题链路（临时工程，提交前删除）
  - [x] SubTask 2.1: 创建 `prototype/` 独立 Gradle 工程（自带 `settings.gradle.kts`，不被根工程 `include`，因此删除时不影响主构建）
  - [x] SubTask 2.2: 添加 `application` 插件 + OkHttp + `kotlinx-serialization`，实现 `Main.kt`：解析 `--pid`，请求 `https://www.luogu.com.cn/problem/{pid}`，抽取 `lentille-context`，打印 `pid`/`name`/样例数量/首样例输入输出
  - [x] SubTask 2.3: 执行 `./gradlew -p prototype run --args="--pid P1001"`，确认输出正确；再试一个错误题号，确认失败路径可控

- [x] Task 3: 实现 api 层（HTTP 客户端 + DTO + 服务）
  - [x] SubTask 3.1: `LuoguHttpClient.kt`：OkHttp 客户端、内存 `CookieJar`、`followRedirects`、浏览器伪装请求头、15s 超时
  - [x] SubTask 3.2: `dto.kt`：`@Serializable` 数据类，`samples: List<List<String>>`、`limits`、可选字段带默认值；JSON 侧 `ignoreUnknownKeys = true`
  - [x] SubTask 3.3: `LuoguApiService.kt`：`suspend fun getProblem(pid)`，用正则抽取 `lentille-context` 脚本块后反序列化到 `data.problem`；区分「题目不存在」「数据块缺失」「网络错误」三类异常
  - [x] SubTask 3.4: 补充题号格式校验工具（`^[A-Za-z]{1,4}\d{1,5}$`）

- [x] Task 4: 实现代码模板设置
  - [x] SubTask 4.1: `LuoguSettings.kt`：应用级 `PersistentStateComponent`，字段 `codeTemplate`，内置默认 C++ 竞赛模板
  - [x] SubTask 4.2: `LuoguConfigurable.kt`：设置 UI（多行文本框），注册到 `applicationConfigurable` 且 `parentId="tools"`
  - [x] SubTask 4.3: 在 `plugin.xml` 注册配置项

- [x] Task 5: 实现文件生成服务
  - [x] SubTask 5.1: `ProblemFileGenService.kt`：生成 `Pxxxx.cpp`（写操作 + VFS 刷新），内容取 `LuoguSettings.codeTemplate`
  - [x] SubTask 5.2: 生成 `Pxxxx.in` / `Pxxxx.out`：取 `samples[0]`，UTF-8 无 BOM，逐字节保真不做 trim
  - [x] SubTask 5.3: 保护逻辑：`.cpp` 已存在时不覆盖；`.in/.out` 始终覆盖；无样例时生成空文件并返回提示信息
  - [x] SubTask 5.4: 返回生成结果（文件列表 + 提示语）供动作层展示

- [x] Task 6: 实现拉题动作并注册到菜单
  - [x] SubTask 6.1: `FetchProblemAction.kt`：校验项目上下文 → `Messages.showInputDialog` 输入题号 → 格式校验 → 后台线程调用 `LuoguApiService.getProblem` → 回 EDT 调用 `ProblemFileGenService` → `FileEditorManager.openFile` 自动打开 cpp
  - [x] SubTask 6.2: 失败路径用 `Notification` 展示可读原因（网络错误 / 题目不存在 / 被拦截）
  - [x] SubTask 6.3: `plugin.xml` 注册 action，加入 `EditorPopupMenu`、`ProjectViewPopupMenu` 及主菜单 `Tools` 分组（中文文案「拉取洛谷题目」）

- [ ] Task 7: 端到端验证
  - [ ] SubTask 7.1: `./gradlew runIde` 启动开发实例，新建空项目，触发「拉取洛谷题目」输入 `P1001`
  - [ ] SubTask 7.2: 确认项目根目录生成 `P1001.cpp/.in/.out` 且 `P1001.cpp` 自动在编辑器打开；核对 `.in/.out` 内容与洛谷样例一致
  - [ ] SubTask 7.3: 验证保护逻辑（修改 `P1001.cpp` 后重新拉题，代码不被覆盖）与失败提示（输入不存在的题号）
  - [ ] SubTask 7.4: 验证设置页模板修改后生效，且拉题过程中 IDE 界面不冻结

- [ ] Task 8: 清理原型并初始化 Git 仓库
  - [ ] SubTask 8.1: 删除 `prototype/` 目录（临时验证代码，不进入版本库）
  - [ ] SubTask 8.2: 编写 `.gitignore`（`build/`、`.gradle/`、`.idea/`、`*.iml`、`local.properties` 等）
  - [ ] SubTask 8.3: `git init` + 首次提交，确认 `git status` 干净且提交中不含 `prototype/`
  - [ ] SubTask 8.4: 添加远端 `origin` 并推送 `main`（远端不存在时向用户索取仓库地址，不擅自创建）

# Task Dependencies

- Task 2 依赖 Task 1
- Task 3 依赖 Task 1
- Task 4 依赖 Task 1
- Task 5 依赖 Task 4
- Task 6 依赖 Task 3、Task 5
- Task 7 依赖 Task 6
- Task 8 依赖 Task 7
- Task 2、Task 3、Task 4 之间无依赖，可并行推进
