# 拉取题目描述 md 与全部样例 Spec

## Why

MVP1（`add-luogu-mvp1`）已能拉题并生成本地测试文件，但存在两个体验缺口：

1. **示例不完整**：MVP1 只把 `samples[0]`（第一组样例）写成 `P1001.in/.out`，多数洛谷题有多组样例，做题时仍然要回网页看题面和完整样例。
2. **题面不可离线看**：想读题必须开浏览器，无法在 CLion 内查看题目描述、输入输出格式与提示。

本 spec 在现有 `FetchProblemAction` 拉题流程之上做增量：拉取后**额外生成题目描述 md** 与**全部样例的 in/out 文件**，将样例集中到一个文件夹，供本地读题与对拍。

本 spec **不包含登录**（用户已确认）、不包含代码提交/评测（仍在 MVP2）。公开题拉取无需登录。

## 与 MVP1 的关系

- 前置依赖：`add-luogu-mvp1` 已交付的 `LuoguApiService.getProblem`、`ProblemFileGenService`、`FetchProblemAction`、`LuoguSettings`、`plugin.xml`。
- 本 spec **扩展** 上述能力，不改动其已确认的拉题链路与保护逻辑语义。
- 技术要点（已核实）：MVP1 调试时为修复解析失败删除了 DTO 的 `content`/`contenu` 字段（其为对象 `{name,background,description,formatI,formatO,hint}`）。要生成 md 必须**恢复 `content`/`contenu` 的建模**（用 `kotlinx.serialization.json.JsonObject` 承接），否则无法取得题面正文。

## 已核实事实

| 项 | 实测结论 |
| --- | --- |
| 题面正文 | `data.problem.content` 与 `contenu` 为对象，含 `name, background, description, formatI, formatO, hint`（字段名取自在线实测） |
| 正文格式 | `content` 内文本为 Markdown/HTML 混合（背景、描述等含 `**` 与换行）；生成 md 时需做轻量清洗，保留 Markdown 语义 |
| 多样例 | `data.problem.samples` 为 `List<List<String>>`，每个元素 `[输入, 输出]`，数量不定（MVP1 用 `samples[0]`，本 spec 取全部） |
| 样例保真 | 各 in/out 需逐字节保真（含换行/空格），UTF-8 无 BOM |
| 其他字段 | `pid/name/difficulty/tags(List<Int>)/fullScore/timeLimit(ms)/memoryLimit(KB)` 均已由现状 DTO 提供或可由 `limits` 获得 |

## What Changes

- **DTO**：恢复 `LuoguProblemDto` 对题面正文的承接（新增 `content`/`contenu` 为 `JsonObject?`），供 md 生成；`samples` 保持 `List<List<String>>` 并使用全部样例。
- **新增** `ProblemMdGenService`：根据题目数据生成 `P1001.md`（Markdown 题面）。
- **改造** `ProblemFileGenService`：
  - `.cpp` 生成逻辑不变（模板、防覆盖）。
  - 多样例写入：改为 `P1001_samples/P1001_1.in/_1.out`、`P1001_samples/P1001_2.in/_2.out`…，`_1` 为第一组（不再在根目录写 `P1001.in/.out`）。
  - 全部样例均写，不再只取第一个。
- **改造** `FetchProblemAction`：拉题流程中串接 `ProblemMdGenService` 生成 md + `ProblemFileGenService` 生成样例文件夹；自动打开 `.cpp`（默认），可通知列出 `P1001.md` 与样例文件数。
- 本 spec **不引入登录**，因此无 cookie 持久化改动；沿用现状公开题拉取。

### 本次非目标（明确不做）

- 登录、`SecureCookieStore`、`SubmitCodeAction`、评测轮询、工具窗口（均留到 MVP2/后续）。
- 将 `.cpp`/`.md` 移入子目录（`.cpp`/`.md` 仍以 `Pxxxx.cpp`/`Pxxxx.md` 置于项目根目录，仅样例进文件夹）。
- 题面 md 的完整 HTML→Markdown 转换器（采用轻量清洗 + 原样保留 Markdown 段落，够用于读题）。
- 生成 `CMakeLists.txt`。

## Impact

- Affected specs: `add-luogu-mvp1`（本 spec 在其成果上扩展）
- Affected code:
  - `src/main/kotlin/com/user/clionluogu/api/dto.kt`（恢复 content/contenu 建模）
  - `src/main/kotlin/com/user/clionluogu/service/ProblemMdGenService.kt`（**新增**）
  - `src/main/kotlin/com/user/clionluogu/service/ProblemFileGenService.kt`（多样例文件夹化）
  - `src/main/kotlin/com/user/clionluogu/action/FetchProblemAction.kt`（串接 md 生成）
  - 数据结构影响：`.in/.out` 从根目录迁到 `P1001_samples/`，**MVP1 已有的单文件行为废弃**（属破坏性变更，见下文 MODIFIED）

---

## ADDED Requirements

### Requirement: R1 题目描述 md 生成

系统 SHALL 在拉取题目后生成 `Pxxxx.md`，内容为 Markdown 形式的题面描述，置于项目根目录；若题目无正文则生成占位并提示。

#### Scenario: 正常生成题面 md
- **WHEN** 拉取 `P1001` 并生成
- **THEN** 项目根目录生成 `P1001.md`，包含题号、题名、难度、标签、题目背景/描述/输入输出格式、提示，以及全部样例（以 Markdown 代码块呈现）

#### Scenario: 题面正文缺失
- **WHEN** 题目无 `content`/`contenu`（或为空）
- **THEN** 仍生成 `P1001.md`，正文区显示「暂无题目描述」，不报错

#### Scenario: 正文清洗
- **WHEN** `content` 内包含 Markdown 语法
- **THEN** 生成的 md 保留其 Markdown 段落（换行、`**`、列表、代码块），不产生乱码或 HTML 标签泄漏

### Requirement: R2 全部样例文件生成

系统 SHALL 将题目的**全部**样例写入 `Pxxxx_samples/` 文件夹，命名为 `Pxxxx_1.in/_1.out`、`Pxxxx_2.in/_2.out`…，逐字节保真，UTF-8 无 BOM。

#### Scenario: 多样例
- **WHEN** 题目有 N 组样例（N≥1）
- **THEN** 生成 `Pxxxx_samples/` 下共 N 组 in/out（`_1` 到 `_N`），每文件内容与接口返回逐字节一致（不做 trim/换行归一化）

#### Scenario: 无样例
- **WHEN** 题目的 `samples` 为空
- **THEN** 不创建 `Pxxxx_samples/`（或创建空文件夹），并返回提示「该题无样例」，`.cpp`/`.md` 仍生成

#### Scenario: 样例文件夹可覆盖
- **WHEN** 已存在同名 `Pxxxx_samples/`
- **THEN** 其中样例文件被重写（样例以接口最新为准）

### Requirement: R3 md 与样例在拉题流程中串接

系统 SHALL 在 `FetchProblemAction` 中，于成功拉取到题目后，依次生成 `Pxxxx.md` 与 `Pxxxx_samples/` 全部样例，完成后给出汇总通知（md 路径 + 样例数量）；`.cpp` 仍正常生成并自动打开。

#### Scenario: 完成通知
- **WHEN** 拉取 `P1001` 成功且全部生成
- **THEN** 显示通知，包含「已在根目录生成 P1001.md」「样例 N 组，位于 P1001_samples/」，并自动打开 `P1001.cpp`

#### Scenario: 失败不影响核心
- **WHEN** 题面正文缺失或样例为空
- **THEN** 不中断 `.cpp`/`.md` 生成，通过通知提示缺失项，不抛未捕获异常

### Requirement: R4 DTO 恢复题面正文字段

系统 SHALL 使 DTO 能承接题面正文数据，供 md 生成使用。

#### Scenario: 兼容现有解析
- **WHEN** 使用修复后的 DTO 拉取 `P1001`
- **THEN** 解析仍成功（不因恢复字段而回归），且 `content` 或 `contenu` 可读到题面对象

---

## MODIFIED Requirements

### Requirement: 多样例文件规范（原 MVP1 R5）

MVP1 规定 `.in/.out` 取 `samples[0]` 写为根目录 `Pxxxx.in/.out`。本 spec 将其替代：

**Reason**: 只写第一个样例不足以覆盖大多题目，且样例散落根目录不便于对拍。
**Migration**: `.cpp` 生成与防覆盖逻辑保持不变；`.in/.out` 改为 `Pxxxx_samples/Pxxxx_{n}.in/.out`（全部样例）。旧版本生成的根目录 `Pxxxx.in/.out` 不再由新逻辑产生；若存在历史文件，新逻辑不主动删除（交由用户）。

### Requirement: 拉题动作生成范围（原 MVP1 R7）

MVP1 的 `FetchProblemAction` 只生成 `.cpp/.in/.out` 三文件。本 spec 扩展其输出。

**Reason**: 需要题面 md 与完整样例。
**Migration**: 拉取流程保持「输入题号 → 校验 → 后台拉取 → 生成 → 自动打开 cpp」骨架；生成阶段扩展为「md + 全部样例」，自动打开仍指向 `.cpp`。

## REMOVED Requirements

无（`.in/.out` 根目录单文件行为随 MODIFIED 一并废弃，不再单列）。