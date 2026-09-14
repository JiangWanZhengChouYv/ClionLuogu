# Checklist

## DTO 与解析

- [x] `LuoguProblemDto` 新增 `content`/`contenu`（`JsonObject?`），`ignoreUnknownKeys=true` 保持，现有 `P1001` 解析不回归
- [x] 在线拉取 `P1001` 能读到题面对象（content 或 contenu）

## 题目描述 md

- [x] `ProblemMdGenService` 生成 `P1001.md`，含题号/题名/难度/标签/背景/描述/输入输出格式/提示 + 全部样例代码块
- [x] 正文缺失时输出「暂无题目描述」并正常生成，不报错
- [x] 正文保留 Markdown 语义（换行/`**`/列表/代码块），无 HTML 标签泄漏
- [x] md 写入在项目根目录，编码 UTF-8 无 BOM，VFS 写操作 + 刷新

## 全部样例进文件夹

- [x] 多样例生成 `Pxxxx_samples/Pxxxx_{n}.in/.out`（`_1` 起，全部 N 组）
- [x] 每个 in/out 与接口返回逐字节一致，未 trim/换行归一化，UTF-8 无 BOM
- [x] `.cpp` 仍生成于根目录，防覆盖逻辑保持不变
- [x] 无样例时不建样例文件夹（或空），提示「该题无样例」，`.cpp`/`.md` 仍生成
- [x] 样例文件夹可重写（以接口最新为准）
- [x] 根目录不再生成旧的 `Pxxxx.in/.out` 单文件

## 动作串联

- [x] `FetchProblemAction` 拉取成功后生成 `P1001.md` + 全部样例
- [x] 完成通知聚合 md 路径 + 样例组数 + 样例文件夹路径
- [ ] 仍自动打开 `P1001.cpp`（GUI/IDE 运行时行为，需真机验证）
- [x] 缺失项不中断、不抛未捕获异常，仅通知提示

## 构建与交付

- [x] `./gradlew compileKotlin` 通过（Task1/3/4 相关改动无编译错误）
- [x] `./gradlew buildPlugin -x buildSearchableOptions` 通过
- [ ] P1001 实测：`P1001.md` + `P1001_samples/P1001_1.in/.out` + 根目录 `P1001.cpp` 齐备（需 runIde 落盘验证）
- [x] 无测试残留、改动已本地 commit（未擅自 push）（commit 77b46f0）

## 本机 GUI 验证（runIde，无法自动）

- [ ] runIde 输入 P1001 后确认 md + 样例文件夹生成、cpp 自动打开、通知正确