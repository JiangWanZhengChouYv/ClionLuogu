# ClionLuogu

> 在 CLion 里一站式刷洛谷：**拉题 → 预览 → 写码 → 提交 → 看评测**。

[![Release](https://img.shields.io/github/v/release/JiangWanZhengChouYv/ClionLuogu?v=1)](https://github.com/JiangWanZhengChouYv/ClionLuogu/releases)
[![License](https://img.shields.io/github/license/JiangWanZhengChouYv/ClionLuogu?v=1)](LICENSE)
![CLion](https://img.shields.io/badge/CLion-2024.3%2B-blue)
![Platform](https://img.shields.io/badge/IntelliJ%20Platform-plugin-black)

一个运行在 **CLion** 上的洛谷插件：把拉题、预览、提交、看评测全搬进 IDE，不用在浏览器和编辑器之间来回切。

## 演示

<!-- 录好 15–30 秒 GIF 后放到 docs/demo.gif，并把下面这行取消注释 -->
<!-- ![demo](docs/demo.gif) -->

> 🎬 待补演示（建议录：搜索 → **双击结果预览题面** → 提交 → **逐测试点方块一个个变绿**）

## 功能

右侧边栏共 **6 个页签**：

| 页签 | 能做什么 |
| --- | --- |
| **评测** | 提交历史 + 详情。详情区按子任务分行展示**逐测试点彩色方块**（正方形、自动换行、悬停看编号/状态/耗时/内存）；当次提交的**源码可查看、可复制** |
| **拉取** | 输入题号 → 生成 `Pxxxx.cpp`、`Pxxxx.md` 与**全部样例**到 `Pxxxx_samples/` |
| **搜索** | 关键词搜题 → 结果列表 → 拉取选中；**双击结果直接预览题面** |
| **预览** | 题面 / **题解** 双模式：题面渲染 **LaTeX 公式**（内置 MathJax）+ **图片**，样式跟随 IDE 明暗主题；切到「题解」按页拉取该题题解，正文是 **Markdown**（内置 marked.js 渲染，公式同样走 MathJax），已加载的题解以列表平铺、点一条看一篇 |
| **提交** | 确认题号 / C++ 语言版本、看代码预览、一键提交（需要时弹验证码） |
| **登录** | 填 `__client_id` / `_uid`（从浏览器 Cookie 复制），也支持退出登录 |

- **评测记录按项目持久化**（含源码与语言），重启 IDE 不丢。
- **每日打卡提醒**：启动时查一次今日还能不能打卡，能则弹一条右下角通知，**点了通知里的「打卡」按钮才发请求**；错过通知可按侧边栏标题栏的打卡图标手动来一次，开关在 `Settings → Tools → 洛谷拉题`。
- **AC 后清理**：某题评测变成 AC 时弹窗问一句，确认后删掉项目根下的 `Pxxx.cpp` / `Pxxx.md` / `Pxxx_samples/`（含全部 `.in/.out`）；提交记录保留，不想要提醒可在同一处关掉。
- 拉下来的 `.md` 是**规范 Markdown**，用 IDE 自带的 Markdown 预览也能渲染公式。
- 评测轮询**按 rid 并发**，连续提交互不打断；网络请求全在后台线程，界面不卡。
- 侧边栏标题栏只有两个按钮：「清空提交记录」与「每日打卡」。

## 安装

1. 到 [Releases](https://github.com/JiangWanZhengChouYv/ClionLuogu/releases) 下载最新的 `ClionLuogu-x.y.z.zip`
2. CLion → `Settings / Preferences` → `Plugins` → 齿轮 ⚙ → **Install Plugin from Disk…** → 选中刚下载的 zip
3. 重启 CLion，右侧边栏出现 **ClionLuogu**

> 提示：插件目录下**不要同时存在多个同 id 的版本**（例如手工留的旧版备份），否则可能加载到旧的那一份。

## 环境要求

- **CLion 2024.3+**（已在 2026.2 实测）
- 依赖 CLion 自带的 **Markdown 插件**与 **JCEF**（默认都启用）

## 使用

1. **登录**：浏览器 `F12` → `Application` → `Cookies`，复制 `__client_id` 与 `_uid`，填进「登录」页。
2. **搜题 / 拉题**：搜索页输入关键词 → 双击某条**预览题面**，或选一条点「拉取选中」生成文件。
3. **写码 → 提交**：打开生成的 `.cpp` 写代码，切到「提交」页确认题号与语言 → 提交。
4. **看评测**：切到「评测」页，详情区会显示逐测试点的彩色方块，悬停查看耗时 / 内存。
5. **看题解**：预览页点「题解」→ 上方列表点一篇看正文 → 「加载更多」翻下一页（需已登录）。
6. **打卡**：启动后若今日未打卡，右下角会弹通知，点 **「打卡」** 才会真的发请求；错过了就按侧边栏标题栏上「清空提交记录」旁边的**打卡按钮**（✓）手动来一次。
7. **AC 后清理**：评测变绿的瞬间会弹窗问「删除本题文件？」，列出实际存在的 `Pxxx.cpp` / `Pxxx.md` / `Pxxx_samples/`；点「删除」即清掉，点「保留」什么都不动。

## 与其它方式对比

|  | ClionLuogu | vscode-luogu | 浏览器 | Dev-C++ |
| --- | --- | --- | --- | --- |
| 编辑器 / IDE | CLion（C++ 调试与重构最强） | VS Code | — | 老旧 |
| 拉题 + 样例落盘 | ✅ | ✅ | ❌ | ❌ |
| 提交 + 评测看板 | ✅ 逐测试点方块 | ✅ | 网页 | ❌ |
| 题面公式 / 图片预览 | ✅ | ✅ | ✅ | ❌ |
| 记录持久化 | ✅ 项目级（含源码） | 部分 | 云端 | ❌ |

## 已知限制

- 题面里的**图片需要联网**加载。
- **题解需要登录**：洛谷的题解接口未登录一律返回 401，未填 Cookie 时「题解」会提示先登录。
- **打卡没有状态查询接口**：入口只在旧版首页服务端渲染，插件靠「页面里还有没有 `[name=punch]` 打卡按钮」判断（打过卡按钮就消失）。你要是先在网页上打了卡，插件当天仍可能提醒一次——点下去只会得到服务器「已经打过卡」的提示，不会重复打卡。
- 题面中**字面意义的 `$`**（非公式）可能被当成公式定界符，个别题目可能出现误判。
- 登录与验证码都需要**你本人**参与；插件不做自动登录。
- **AC 清理只认三个精确名字**：项目根下的 `Pxxx.cpp` / `Pxxx.md` / `Pxxx_samples`。改过名、挪进子目录、或一题写了好几份代码的，插件都不会去碰——删除不可逆，宁可少删。
- 仅支持**一份已保存的登录态**，没有多账号切换。

## 免责声明与合规

- 本项目是**非官方**工具，与洛谷（luogu.com.cn）无关。
- 插件只使用**你本人提供的登录态**、模拟**你本人**的常规操作（拉题 / 提交 / 查询 / 打卡）。
  它**不会**自动登录、**不会**绕过验证码、**不提供**批量刷题功能；**打卡也不会自己点**——
  启动只发一条通知，必须你亲手点「打卡」才发出那一个请求，且一次启动最多提醒一次。
  请遵守洛谷的服务条款，使用不当的后果由使用者自负。
- 内置的 MathJax（`src/main/resources/mathjax/tex-svg.js`）版权归 MathJax 项目所有，
  按 **Apache-2.0** 许可分发，详见同目录下的 `NOTICE.txt`。
- 内置的 marked（`src/main/resources/marked/marked.min.js`，v12.0.2）用于把题解正文的 Markdown
  渲染为 HTML，版权归 Christopher Jeffrey 所有，按 **MIT** 许可分发，详见同目录下的 `NOTICE.txt`。

## 从源码构建

```bash
./gradlew buildPlugin -x buildSearchableOptions   # 产物在 build/distributions/
./gradlew runIde                                   # 启动一个带此插件的 CLion 调试实例
```

需要 JDK 21。

## 致谢

- 接口调用与评测状态码映射参考了 [vscode-luogu](https://github.com/Infinideast/vscode-luogu)。

## 许可

[MIT](LICENSE)
