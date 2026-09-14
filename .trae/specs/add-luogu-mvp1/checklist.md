# Checklist

## 工程与构建

- [ ] 插件工程可构建：`./gradlew buildPlugin` 成功，产物位于 `build/distributions/`
- [ ] `gradle.properties` 中插件 ID 为 `com.user.clionluogu`，`platformType = CL`、`platformVersion = 2024.3`、`pluginSinceBuild = 243`
- [ ] 工程使用 JDK 21 toolchain（对应平台 2024.3 的 Java 21 要求），构建无 JDK 版本不匹配报错
- [ ] 已移除官方模板自带的示例动作与示例工具窗口代码，不残留无关样板
- [ ] 依赖已就位：OkHttp、kotlinx-serialization-json，构建无 duplicate class 告警

## CLI 原型

- [ ] `prototype/` 为独立 Gradle 工程，未被根工程 `include`，删除后不影响主构建
- [ ] `./gradlew -p prototype run --args="--pid P1001"` 输出题号 `P1001`、题名 `A+B Problem`、样例数量与首样例输入输出
- [ ] 原型能穿过多级重定向与 `C3VK` Cookie 挑战，最终取得 `lentille-context` 数据（证明 Cookie 容器 + 重定向配置正确）
- [ ] 原型对不存在的题号能给出可读错误而非崩溃

## 网络与解析

- [ ] `LuoguHttpClient` 携带浏览器形态请求头（User-Agent / Accept-Language / Referer），实测不被 403 拦截
- [ ] `LuoguHttpClient` 使用内存 Cookie 容器，`302 + Set-Cookie: C3VK` 场景下自动回传 Cookie 并拿到 200 正文
- [ ] 请求超时上限 ≤ 15 秒，超时/断网抛出被业务层捕获的异常，不崩溃
- [ ] `dto.kt` 中 `samples` 按 `List<List<String>>` 建模，与实测返回结构 `[["20 30\n","50\n"]]` 一致
- [ ] JSON 解析启用 `ignoreUnknownKeys = true`，新增/缺失字段不会导致解析失败
- [ ] `LuoguApiService.getProblem("P1001")` 返回 `pid = "P1001"`、`name = "A+B Problem"`，首样例为 `["20 30\n", "50\n"]`
- [ ] 题目不存在、数据块缺失（疑似拦截）、网络错误三类失败被区分为可读异常，无静默空对象
- [ ] 题号格式校验生效，非法输入不发网络请求

## 文件生成

- [ ] 生成 `P1001.cpp`、`P1001.in`、`P1001.out` 三个文件于项目根目录
- [ ] `.cpp` 内容取自可配置的代码模板
- [ ] `.in` / `.out` 内容与接口返回样例逐字节一致，未做 trim 或换行归一化，编码为 UTF-8 无 BOM
- [ ] 已存在的 `.cpp` 不被覆盖（用户代码安全），`.in` / `.out` 正常覆盖
- [ ] 无样例题目仍生成 `.cpp`，`.in`/`.out` 为空文件并有提示
- [ ] VFS 变更在写操作中执行且写入后刷新，文件在项目视图立即可见

## 设置

- [ ] 未修改设置时使用内置默认 C++ 竞赛模板
- [ ] 设置页（Tools 分组）可编辑模板，Apply/OK 后持久化，重启 IDE 仍保留且影响新生成文件

## 交互与线程

- [ ] 动作可从编辑器右键菜单、项目视图右键菜单、主菜单 Tools 三处触发
- [ ] 输入 `P1001` 后自动在编辑器打开 `P1001.cpp`
- [ ] 空输入/非法格式有即时提示且不发请求
- [ ] 失败通过 IDE Notification 展示可读原因，无未捕获异常，无 stderr 噪音
- [ ] 无打开项目时触发动作安全退出
- [ ] 拉题期间 IDE 界面保持响应，网络请求未运行在 EDT
- [ ] 自动打开文件在 EDT 执行，无线程断言错误

## 交付与仓库

- [ ] `./gradlew runIde` 实测通过：输入 `P1001` 完整走通「拉题 → 生成 → 自动打开」
- [ ] 实测保护逻辑：修改 `P1001.cpp` 后重新拉题，用户代码不被覆盖
- [ ] `prototype/` 已在首次提交前删除
- [ ] `.gitignore` 覆盖 `build/`、`.gradle/`、`.idea/`、`*.iml` 等，`git status` 无构建产物
- [ ] 首次提交内容中不含 `prototype/`
- [ ] 代码已推送到 GitHub `origin/main`（远端缺失时已向用户确认，未擅自创建仓库）
