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
check("版本是 1.8.1", version == "1.8.1", version)

xml = read("src/main/resources/META-INF/plugin.xml")
notes = re.search(r"<change-notes><!\[CDATA\[(.*?)\]\]></change-notes>", xml, re.S)
check("change-notes 存在", notes is not None)
notes_text = notes.group(1) if notes else ""

# release.sh 的同款切法：第一条 <li>，非贪婪
first = re.search(r"<li>(.*?)</li>", notes_text, re.S).group(1)
check("最新一条是 1.8.1", "<b>1.8.1</b>" in first, first[:80])
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
        with zipfile.ZipFile(outer.open(inner[0])) as jar:
            names = jar.namelist()
            for cls in ["ProcessRunner", "SelfTestService", "SelfTestPanel", "SelfTestInputService",
                        "LocalRunGates", "SubmissionTracker", "LuoguRunToolWindowFactory"]:
                hit = [n for n in names if n.endswith(cls + ".class")]
                check(f"打包了 {cls}", bool(hit), "")
            check("没有 compat/（bits 兼容头必须已经删干净）",
                  not any(n.startswith("compat/") for n in names), "")
            check("打包了 preview.css", any(n == "css/preview.css" for n in names), "")
            meta = jar.read("META-INF/plugin.xml").decode("utf-8")
            check("插件 jar 里的 version 与 gradle.properties 一致",
                  f"<version>{version}</version>" in meta,
                  re.search(r"<version>([^<]*)</version>", meta).group(1) if re.search(r"<version>([^<]*)</version>", meta) else "无")
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
wide = read("src/main/kotlin/com/user/clionluogu/ui/WideLayout.kt")
check("宽屏布局只打开一次（有「动过」标记才不再动）", "wideScreenLayoutAdopted" in wide, "")
check("通知里有撤销", "撤销" in wide and "revert()" in wide, "")
check("运行窗口创建时才应用", "WideLayout.applyOnce()" in read(
    "src/main/kotlin/com/user/clionluogu/ui/LuoguRunToolWindowFactory.kt"), "")
check("Report 有第二轮排版一节", "## 39" in report)

print(f"=== 元数据探针：{PASS} 通过 / {len(FAIL)} 失败 ===")
for f in FAIL:
    print("FAIL", f)
sys.exit(1 if FAIL else 0)
