#!/usr/bin/env python3
# 元数据断言：版本、依赖声明、change-notes 结构、发布包里的类与资源。
#
# 为什么单开一份 python：发版脚本 release.sh 是用**非贪婪正则**从 change-notes 里切第一条
# 当 Release 说明的，「条目里嵌 <li>」会把说明截断——这种坑只有拿同款正则对着真发布包跑才抓得住。
# 1.8.0 起它和 Java 探针一起放在仓库里（/tmp 里那次被重启清掉了，305 条整个没了）。
import glob
import os
import re
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.abspath(os.path.dirname(__file__)))
FAIL = []
PASS = 0


def check(name, ok, actual=""):
    global PASS
    if ok:
        PASS += 1
    else:
        FAIL.append(f"{name} 实际=[{actual}]")


def read(path):
    with open(os.path.join(REPO, path), encoding="utf-8") as f:
        return f.read()


def newest_zip():
    found = sorted(glob.glob(os.path.join(REPO, "build/distributions/ClionLuogu-*.zip")), key=os.path.getmtime)
    return found[-1] if found else None


props = read("gradle.properties")
version = re.search(r"^version\s*=\s*(\S+)", props, re.M).group(1)
check("gradle.properties 有版本号", bool(version), version)
check("版本是 1.8.2", version == "1.8.2", version)

xml = read("src/main/resources/META-INF/plugin.xml")
notes = re.search(r"<change-notes><!\[CDATA\[(.*?)\]\]></change-notes>", xml, re.S)
check("change-notes 存在", notes is not None)
notes_text = notes.group(1) if notes else ""

# release.sh 的同款切法：第一条 <li>，非贪婪
first = re.search(r"<li>(.*?)</li>", notes_text, re.S).group(1)
check("最新一条是 1.8.2", "<b>1.8.2</b>" in first, first[:80])
check("1.9.0 这个号不该出现（本地编译那批改动并回 1.8.2 发）", "1.9.0" not in notes_text, "")
check("1.8.1 那条还在", "<b>1.8.1</b>" in notes_text)
check("1.8.0 那条还在", "<b>1.8.0</b>" in notes_text)
check("本版说明非空且够长", len(first.strip()) > 120, len(first))
check("条目里不嵌 <li>（否则 release.sh 会截断）", "<li>" not in first, first[:80])
check("1.7.4 那条还在（历史不覆盖）", "<b>1.7.4</b>" in notes_text)
check("描述里有 CDATA 收尾", xml.count("]]>") >= 2, xml.count("]]>"))

depends = re.findall(r"<depends>([^<]+)</depends>", xml)
check("依赖声明恰好两条", len(depends) == 2, str(depends))
check("依赖就是 platform + jcef",
      depends == ["com.intellij.modules.platform", "com.intellij.modules.jcef"], str(depends))

wins = re.findall(r'<toolWindow\s+id="([^"]+)"\s+anchor="([^"]+)"', xml)
check("注册了两个工具窗口", len(wins) == 2, str(wins))
check("主窗口在左侧", ("ClionLuogu", "left") in wins, str(wins))
check("运行窗口在底部", ("ClionLuoguRun", "bottom") in wins, str(wins))
for factory in re.findall(r'factoryClass="([^"]+)"', xml):
    rel = "src/main/kotlin/" + factory.replace(".", "/") + ".kt"
    check(f"工厂类存在：{factory}", os.path.exists(os.path.join(REPO, rel)), rel)

tabs = read("src/main/kotlin/com/user/clionluogu/ui/LuoguTabs.kt")
check("页签常量有自测", 'TAB_SELFTEST = "自测"' in tabs)
check("有底部窗口 id", 'RUN_TOOL_WINDOW_ID = "ClionLuoguRun"' in tabs)
check("openTab 带默认窗口参数", "windowId: String = TOOL_WINDOW_ID" in tabs, "签名变了")

# 发布包内容（要跑过 buildPlugin）
zip_path = newest_zip()
if zip_path:
    with zipfile.ZipFile(zip_path) as outer:
        inner = [n for n in outer.namelist() if n.endswith(f"ClionLuogu-{version}.jar")]
        check("发布包里有且只有一个同名插件 jar", len(inner) == 1, str(inner))
        # 版本号刚改、还没 buildPlugin 时这里会是空：要报这一条，但**后面的检查照跑**
        # （以前直接下标，整份 python 崩掉，看起来像「元数据没问题」）
        if inner:
            with zipfile.ZipFile(outer.open(inner[0])) as jar:
                names = jar.namelist()
                for cls in ["ProcessRunner", "SelfTestService", "SelfTestPanel", "SelfTestInputService",
                            "LocalRunGates", "SubmissionTracker", "LuoguRunToolWindowFactory",
                            "ClionToolchain", "CompilerLine", "PreviewPanel"]:
                    hit = [n for n in names if n.endswith(cls + ".class")]
                    check(f"打包了 {cls}", bool(hit), "")
                check("没有 compat/（bits 兼容头必须已经删干净）",
                      not any(n.startswith("compat/") for n in names), "")
                check("打包了 preview.css", any(n == "css/preview.css" for n in names), "")
                for res in ["js/solution-render.js", "marked/marked.min.js", "mathjax/tex-svg.js"]:
                    check(f"打包了 {res}", any(n == res for n in names), "")
                meta = jar.read("META-INF/plugin.xml").decode("utf-8")
                check("插件 jar 里的 version 与 gradle.properties 一致",
                      f"<version>{version}</version>" in meta,
                      re.search(r"<version>([^<]*)</version>", meta).group(1) if re.search(r"<version>([^<]*)</version>", meta) else "无")
                check("包里的正文渲染脚本就是仓库那份（支持 data-luogu-md）",
                      "[data-luogu-md]" in jar.read("js/solution-render.js").decode("utf-8"), "")
else:
    print("（没有发布包，跳过 jar 检查：先跑 ./gradlew buildPlugin）")

readme = read("README.md")
check("README 提到了自测", "自测" in readme)
check("README 不再说 8 个页签", "8 个页签" not in readme, "")
check("README 说了左侧题目栏 + 底部运行栏", "底部" in readme and "左侧" in readme)

rel = read("scripts/release.sh")
check("release.sh 功能一览有自测", "自测" in rel)
check("release.sh 不说 8 个页签", "8 个页签" not in rel, "")
check("release.sh 页签数与新布局一致", "左侧 6" in rel or "6 页签" in rel or "底部" in rel, "")

# 第二轮排版的两条钉子：头部不许回到「一件一行」，被自动重看取代的按钮不许复活
compare = read("src/main/kotlin/com/user/clionluogu/ui/SampleComparePanel.kt")
check("对拍页头部不再一件一行（addNorth 应当已经没了）", "addNorth(" not in compare, "")
check("对拍页不再挂「重新查找样例」按钮", "重新查找样例\")" not in compare or "reprobeButton" not in compare, "")
submit = read("src/main/kotlin/com/user/clionluogu/ui/SubmitPanel.kt")
check("提交页不再挂「刷新预览」按钮", 'JButton("刷新预览")' not in submit, "")
status_row = read("src/main/kotlin/com/user/clionluogu/ui/StatusRow.kt")
check("状态行着色收成一处", "getErrorForeground" in status_row, "")
for panel in ["FetchPanel.kt", "SearchPanel.kt", "LoginPanel.kt", "SubmitPanel.kt", "SelfTestPanel.kt"]:
    body = read("src/main/kotlin/com/user/clionluogu/ui/" + panel)
    check(f"{panel} 用共用的状态行规则", "StatusRow." in body, "")
runf = read("src/main/kotlin/com/user/clionluogu/ui/LuoguRunToolWindowFactory.kt")
check("对拍页不留已经没人调用的 limitsRow（重排后的死代码）", "limitsRow" not in compare, "")
check("两个面板都不许再用「禁用内存字段」代替说明",
      "memoryField.isEnabled" not in compare and
      "memoryField.isEnabled" not in read("src/main/kotlin/com/user/clionluogu/ui/SelfTestPanel.kt"), "")
check("上限字段一敲字就算改过", "LimitFields.markWhenTyped(timeField)" in compare, "")
check("底部三页各有图标", runf.count("AllIcons.Actions.") >= 3, str(runf.count("AllIcons.Actions.")))

report = read("Report.md")
check("Report 有 1.8.0 一节", "## 37" in report and "1.8.0" in report)
check("Report 有 1.8.1 一节", "## 42" in report and "1.8.1" in report)
check("Report 有 1.8.2 一节（JCEF 判定的取证过程要留档）",
      "## 43" in report and "JBCefStartup" in report, "")
wide = read("src/main/kotlin/com/user/clionluogu/ui/WideLayout.kt")
check("宽屏布局只打开一次（有「动过」标记才不再动）", "wideScreenLayoutAdopted" in wide, "")
check("通知里有撤销", "撤销" in wide and "revert()" in wide, "")
check("拉取/搜索/登录的上限与题号框都吃回车", all(".addActionListener" in read(f) for f in [
    "src/main/kotlin/com/user/clionluogu/ui/FetchPanel.kt",
    "src/main/kotlin/com/user/clionluogu/ui/SearchPanel.kt",
    "src/main/kotlin/com/user/clionluogu/ui/LoginPanel.kt",
    "src/main/kotlin/com/user/clionluogu/ui/SampleComparePanel.kt",
    "src/main/kotlin/com/user/clionluogu/ui/SelfTestPanel.kt"]), "")
check("提交页明确不给回车（写操作要实点）", "故意不响应回车" in submit, "")
selftest = read("src/main/kotlin/com/user/clionluogu/ui/SelfTestPanel.kt")
check("自测页是三栏（左输入 / 中 stdout / 右概览+stderr）",
      "stdoutPanel" in selftest and "sidePanel" in selftest and "outputSections" in selftest, "")
check("运行窗口创建时才应用", "WideLayout.applyOnce()" in read(
    "src/main/kotlin/com/user/clionluogu/ui/LuoguRunToolWindowFactory.kt"), "")
check("Report 有第二轮排版一节", "## 39" in report)

# ---- 1.8.2：预览页对洛谷的适配（JCEF 判定 + 正文走 marked + 元信息不粘连）----
preview = read("src/main/kotlin/com/user/clionluogu/ui/PreviewPanel.kt")
# KDoc 里会引用「旧写法」的原文当对照，所以结构类断言一律只看**代码行**（去掉注释行）
preview_code = "\n".join(l for l in preview.splitlines() if not l.strip().startswith("*"))
render_js = read("src/main/resources/js/solution-render.js")
css = read("src/main/resources/css/preview.css")
check("browser 是懒建（不许回到字段初始化那一句）",
      re.search(r"^\s*private (val|var) browser\b", preview_code, re.M) is None, "")
check("JBCefBrowser 只在 browser() 里建一次并缓存", preview_code.count("JBCefBrowser()") == 1,
      preview_code.count("JBCefBrowser()"))
check("建起来的那一份存进 browserCache（反复渲染不会反复建）",
      "browserCache = it" in preview_code and "browserCache?.let { return it }" in preview_code, "")
check("dispose 判空释放（懒建之后不能无条件 dispose）", "browserCache?.dispose()" in preview_code, "")
check("多余的 isPluginInstalled 判定已经删干净（连 import 都不留）",
      "isPluginInstalled" not in preview_code and "PluginManagerCore" not in preview_code, "")
check("没有 javake 那类凭猜测加上的插件 id 判定", "javake" not in preview_code and "findId" not in preview_code, "")
check("JCEF 与否由 isSupported 单独决定", "JBCefApp.isSupported()" in preview_code, "")
check("正文交 marked：Kotlin 侧写 data-luogu-md", "data-luogu-md=" in preview_code, "")
check("正文交 marked：JS 侧按同一个属性找宿主", 'querySelectorAll("[data-luogu-md]")' in render_js, "")
check("两侧成对（JS 用的 id 就是 Kotlin 生成的 id）", "luogu-md-$index" in preview_code and 'getAttribute("data-luogu-md")' in render_js, "")
check("题解原来的 DOM 契约没动（宿主 id 两边都在）",
      "luogu-solution" in preview_code and 'getElementById("luogu-solution")' in render_js, "")
check("题解载荷的 id 也照旧（脚本按它取 JSON）",
      'id=\\"luogu-md\\"' in preview_code and 'renderInto(host, "luogu-md")' in render_js, "")
check("marked 与渲染脚本只在 JCEF 路径注入", "rendererScripts(sb, usesJcef)" in preview_code and "if (!usesJcef) return" in preview_code, "")
check("元信息一枚一个 <p>（不是会粘连的 span）", "<p class='chip'>" in preview_code, "")
# Kotlin 里那个形状的字面量是 `<span class=\"chip\">`（反斜杠是源码的一部分，不是转义）
check("旧的 <span class=chip> 徽章形状不许回来", '<span class=\\"chip\\">' not in preview_code, "还留着 span 版")
check("键与值之间的分隔符写在标记里，不靠 CSS", "<b>$key</b>：$value" in preview_code, "")
check("兜底不嵌 JSON 载荷", "if (usesJcef) {" in preview_code, "")
check("CSS 认这套新 class", all(s in css for s in [".chip", ".md", ".fallback-note", ".meta"]), "")
check("兜底那句话不说「这台机器没有 JCEF」", "这台机器的 JCEF 不可用" not in preview, "")
check("JS 侧链接摘 href（面板嵌在 IDE 里，不许被导航带走）", "function neutralizeLinks" in render_js, "")
check("渲染失败不把正文抹成空白（JS 侧不许写 textContent 兜底语）",
      "textContent = \"渲染失败" not in render_js and "textContent = \"题解渲染失败" not in render_js, "")
check("JS 探针在仓库里且被 run.sh 认成套件",
      os.path.exists(os.path.join(REPO, "probes/js-render-check.mjs"))
      and "js-render-check.mjs" in read("probes/run.sh") and "js)" in read("probes/run.sh"), "")
check("跳过没跑的套件要喊出来（不许静默假绿）", 'SKIPPED="$SKIPPED js-render-check.mjs"' in read("probes/run.sh"), "")

# ---- 1.8.2 第二批：用 CLion 选的编译器 + 头文件按编译器走 ----
toolchain = read("src/main/kotlin/com/user/clionluogu/service/ClionToolchain.kt")
# KDoc 里会写「不去调 com.jetbrains.cmake.*」这类对照说明，结构断言只看代码行
toolchain_code = "\n".join(l for l in toolchain.splitlines() if not l.strip().startswith(("*", "//")))
compiler_service = read("src/main/kotlin/com/user/clionluogu/service/CompilerService.kt")
actions = read("src/main/kotlin/com/user/clionluogu/service/LuoguActions.kt")
settings = read("src/main/kotlin/com/user/clionluogu/settings/LuoguSettings.kt")
gen = read("src/main/kotlin/com/user/clionluogu/service/ProblemFileGenService.kt")
compare = read("src/main/kotlin/com/user/clionluogu/ui/SampleComparePanel.kt")

check("CLion 的编译器读自 CMakeCache，不碰 CLion/ CMake 的平台 API",
      "CMakeCache.txt" in toolchain and "CMAKE_CXX_COMPILER" in toolchain
      and "com.jetbrains" not in toolchain_code and "com.intellij" not in toolchain_code, "")
check("这个文件只 import java.*（真不靠 CLion/平台的类）",
      all(l.split()[1].startswith("java.") for l in toolchain.splitlines() if l.startswith("import ")),
      [l for l in toolchain.splitlines() if l.startswith("import ")])
check("避开 cache 里那几个同名陷阱（-ADVANCED / _ARG1 / -NOTFOUND / 注释行）",
      all(s in toolchain for s in ["NOTFOUND", '"//"', '"#"']) and
      'substringBefore(\':\') != "CMAKE_CXX_COMPILER"' in toolchain, "")
check("多份 cache 取最近修改的那份", "maxByOrNull { it.lastModified() }" in toolchain, "")
check("只扫项目根与一层子目录、有上限（不许递归整棵树）", "MAX_CHILD_DIRS" in toolchain, "")
check("探测把 CLion 的那一个传进去",
      "ClionToolchain.compiler(base)" in actions and "detect(settings.compareCompilerPath, clion?.path)" in actions, "")
check("优先级：设置 > CLion > PATH",
      "Origin.CLION" in compiler_service and "Origin.PATH" in compiler_service and "Origin.SETTINGS" in compiler_service, "")
check("身份按 --version 判，不看文件名", "fun flavorOf(versionLine: String?)" in compiler_service, "")
check("文件名会骗人这件事写进了注释（/usr/bin/g++ 其实是 clang）",
      "/usr/bin/g++" in compiler_service and "Apple clang" in compiler_service, "")
check("提醒只在 mac 且非 GCC", "isMac && flavor != Flavor.GCC" in compiler_service, "")
check("提示里有 brew install gcc 与带版本号的名字",
      "brew install gcc" in compiler_service and "g++-16" in compiler_service and "不给裸 g++" in compiler_service, "")
check("通知按编译器路径去重（不是记日期）",
      "gccAdviceShownFor == key" in actions and "getNotificationGroup" in actions, "")
check("头文件按编译器给", "fun defaultCodeTemplate(isMac: Boolean" in settings, "")
check("clang/拿不准 → 标准头，GCC → bits",
      "if (isMac && flavor != CompilerService.Flavor.GCC) DEFAULT_CODE_TEMPLATE else BITS_CODE_TEMPLATE" in settings, "")
check("拉题走的就是这一个入口（不是写死某一份模板）",
      ".codeTemplate" in gen and "DEFAULT_CODE_TEMPLATE" not in gen and "BITS_CODE_TEMPLATE" not in gen, "")
check("内置默认原样存回不算自定义（不然锁死头文件）",
      "takeUnless { it == DEFAULT_CODE_TEMPLATE || it == BITS_CODE_TEMPLATE }" in settings, "")
check("两个面板都写出处", "CompilerLine.text(" in compare and "CompilerLine.text(" in selftest, "")
check("PATH 兜底要说原因（没有 CMakeCache）", "CMakeCache" in read("src/main/kotlin/com/user/clionluogu/ui/CompilerLine.kt"), "")

# loadState 漏镜像这一类 bug：每个 private 后备字段都必须在 loadState 里出现
# （1.8.1 的 wideScreenAdopted 就是这么丢的——写得出去、读不回来）
load_body = settings.split("override fun loadState")[-1]
backing = re.findall(r"^    private var (\w+):", settings, re.M)
for name in backing:
    check(f"持久化字段 {name} 在 loadState 里镜像了", f"state.{name}" in load_body, load_body[:120])
check("loadState 确实镜像了不止一两个字段（防止整段被删）",
      load_body.count("state.") >= len(backing), f"{load_body.count('state.')} vs {len(backing)}")

check("两个面板每秒都比的是三项签名（含 CMakeCache）",
      all("LocalRunSignature.ofProject(" in read(f) for f in [
          "src/main/kotlin/com/user/clionluogu/ui/SelfTestPanel.kt",
          "src/main/kotlin/com/user/clionluogu/ui/SampleComparePanel.kt"]), "")
check("不许留下两参数的旧写法（tick 与探测后各算一种串会每秒重探）",
      not any("LocalRunSignature.of(" in read(f) for f in [
          "src/main/kotlin/com/user/clionluogu/ui/SelfTestPanel.kt",
          "src/main/kotlin/com/user/clionluogu/ui/SampleComparePanel.kt"]), "两参数与三参数混用")
check("第一次拉题前就把编译器身份准备好（不然首次永远拿标准头）",
      "ensureCompilerFlavor(project)" in actions and
      actions.index("ensureCompilerFlavor(project)") < actions.index("invokeLater { generateFiles"), "")
check("Report 有本地编译那一节（编译器事实表要留档）", "## 44" in report and "/usr/bin/g++" in report, "")

print(f"=== 元数据探针：{PASS} 通过 / {len(FAIL)} 失败 ===")
for f in FAIL:
    print("FAIL", f)
sys.exit(1 if FAIL else 0)
