#!/usr/bin/env bash
#
# 一键发版（不上架 JetBrains Marketplace，走 GitHub Release + 自定义插件仓库）
#
#   ./scripts/release.sh
#
# 流程：
#   1. 从 gradle.properties 读版本号
#   2. 构建插件并校验产物里的 <version> 与版本号一致（防止忘 bump）
#   3. 提交并推送 main
#   4. 打 tag（vX.Y.Z）并推送
#   5. 发布 / 更新 GitHub Release（说明取自 plugin.xml 中该版本的 change-notes）
#   6. 改写 updatePlugins.xml 的 version 与 url 并提交推送 → IDE 即可收到更新提示
#
# 注意：所有变量都写成 ${VAR} 形式——变量名后面直接跟中文标点时，
#      部分 locale 下 bash 会把标点的字节并进变量名，导致 unbound variable。
#
# 前置：已登录 gh（gh auth status）、仓库 origin 可用
set -euo pipefail

cd "$(dirname "$0")/.."

REPO="JiangWanZhengChouYv/ClionLuogu"
DIR_NAME="ClionLuogu"
# 分发走 jsDelivr：国内可直连（GitHub 的 raw.githubusercontent.com 与 github.com 资产基本不可达）
JSDELIVR="https://cdn.jsdelivr.net/gh/${REPO}"
DIST_DIR="dist"

VERSION="$(awk -F= '/^version/{gsub(/[ \t\r]/,"",$2); print $2; exit}' gradle.properties)"
[ -n "${VERSION}" ] || { echo "✗ 读不到 gradle.properties 里的 version"; exit 1; }
TAG="v${VERSION}"
JAR_IN_ZIP="${DIR_NAME}/lib/${DIR_NAME}-${VERSION}.jar"
ZIP="build/distributions/${DIR_NAME}-${VERSION}.zip"
DIST_ZIP="${DIST_DIR}/${DIR_NAME}-${VERSION}.zip"
# 仓库描述文件里给 IDE 的下载地址：指向本 tag 下的 dist/ 产物，经 jsDelivr 分发
ASSET_URL="${JSDELIVR}@${TAG}/${DIST_ZIP}"

echo "▶ 准备发版: ${VERSION} (tag ${TAG})"

# ---- 1. 构建 ----
echo "▶ 构建中 ..."
./gradlew buildPlugin -x buildSearchableOptions -q
if [ ! -f "${ZIP}" ]; then echo "✗ 未找到产物 ${ZIP}"; exit 1; fi

# ---- 2. 产物自检：jar 名与内嵌 version 都要与 gradle.properties 一致 ----
# 注意：不要把 unzip 的输出直接管道给 grep -q —— grep -q 命中即退出会让 unzip 收到
# SIGPIPE（141），在 set -o pipefail 下整条管道会被判为失败。先取回再匹配。
ZIP_LIST="$(unzip -l "${ZIP}")"
if ! grep -qF "${JAR_IN_ZIP}" <<< "${ZIP_LIST}"; then
  echo "✗ 产物里没有 ${JAR_IN_ZIP}（版本号没对齐？）"; exit 1
fi
# 从产物的 plugin.xml 里取 id 与 version（权威值：用于校验，并同步进仓库描述文件）
read -r BUILT_ID BUILT_VERSION <<< "$(unzip -p "${ZIP}" "${JAR_IN_ZIP}" | python3 -c '
import sys, zipfile, io, re
z = zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()))
x = z.read("META-INF/plugin.xml").decode("utf-8")
gid = re.search(r"<id>([^<]+)</id>", x)
ver = re.search(r"<version>([^<]+)</version>", x)
print((gid.group(1) if gid else "") + "\t" + (ver.group(1) if ver else ""))')"
if [ -z "${BUILT_ID}" ] || [ "${BUILT_VERSION}" != "${VERSION}" ]; then
  echo "✗ 产物校验失败: id='${BUILT_ID}' version='${BUILT_VERSION}'（期望 version=${VERSION}）"; exit 1
fi
echo "✓ 产物校验通过: ${BUILT_ID} ${BUILT_VERSION}"

# ---- 2.5 把 zip 放进仓库 dist/ 并随 tag 发布，供 jsDelivr 分发 ----
mkdir -p "${DIST_DIR}"
cp -f "${ZIP}" "${DIST_ZIP}"
echo "✓ 已复制到 ${DIST_ZIP}（$(du -h "${DIST_ZIP}" | cut -f1)）"

# ---- 3. 提交并推送 main ----
if [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -q -m "release: ${DIR_NAME} ${VERSION}"
  echo "✓ 已提交"
else
  echo "· 工作区干净，跳过提交"
fi
git push origin main
echo "✓ 已推送 main"

# ---- 4. tag ----
git tag -f "${TAG}" >/dev/null
git push -f origin "${TAG}" >/dev/null 2>&1
echo "✓ 已推送 tag ${TAG}"

# ---- 5. Release（说明取自 plugin.xml 的 change-notes，保持两处一致）----
NOTES_FILE="$(mktemp)"
python3 - "${VERSION}" "${ASSET_URL}" "${BUILT_ID}" > "${NOTES_FILE}" <<'PY'
import re, sys
v, asset, pid = sys.argv[1], sys.argv[2], sys.argv[3]
x = open("src/main/resources/META-INF/plugin.xml", encoding="utf-8").read()
m = re.search(r"<change-notes><!\[CDATA\[(.*?)\]\]></change-notes>", x, re.S)
item = ""
for li in re.findall(r"<li>(.*?)</li>", m.group(1) if m else "", re.S):
    if re.search(r"<b>%s</b>" % re.escape(v), li):
        item = li.strip()
        break

print("## ClionLuogu %s" % v)
print()
print("在 CLion 里一站式刷洛谷：拉题、预览、对拍、提交、看评测。")
print()
print("### 本次更新")
print()
print(item if item else "（本版无单独说明）")
print()
print("### 功能一览")
print()
print("- 侧边栏 8 个页签：评测 / 拉取 / 搜索 / 预览 / 提交 / 对拍 / 题目 / 登录（登录后为「账号」）")
print("- 评测详情按子任务展示逐测试点彩色方块（悬停看耗时 / 内存），并给出各子任务得分合计（拿不到分数时不显示）")
print("- 编译错误一键跳到出错行：对拍页与评测页的诊断旁都有按钮，只认提交文件那种行号，指向标准库的不跳")
print("- 本地对拍：现场编译 Pxxx.cpp 再逐组跑落盘样例，指出首个差异行；失败组可一键存反例到 Pxxx_cases/；只看失败过滤；macOS 没有 bits/stdc++.h，默认模板直接用真实标准头")
print("- 评测判为非 AC 终态时发通知，点「查看」跳到该条记录")
print("- 提交记录按项目持久化（含源码与编译错误详情），重启可恢复、可查看 / 复制当次代码；没跑完的记录重启后会自动补轮询")
print("- 题目预览：搜索页双击即拉题面，内置 MathJax 渲染 LaTeX 公式与图片")
print("- 题解：预览页可切到「题解」，分页拉取该题洛谷题解，正文按 Markdown 渲染（内置 marked.js，公式同样走 MathJax）")
print("- 题目页签：按题号列出本地文件与最近一次评测结果，选中一键删除本题的 .cpp / .md / 样例目录 / 反例目录（弹一次确认列清待删项，提交记录保留）")
print("- AC 后清理：某题评测变 AC 时弹窗询问是否删除本题的 .cpp / .md / 样例目录 / 反例目录（可在设置里关掉）")
print("- 外观：详情区分节显示（诊断/代码是等宽可滚动可复制的独立文本块）、判定配色分明暗两套、列表分段着色 + 空态提示 + 敲题号定位；题面/题解样式跟随 IDE 主题")
print("- 登录后可查看账号数据（头像 / 咕值 / 排名 / 关注 / 粉丝 / CCF 等级 / 通过题目数）")
print()
print("### 安装 / 更新")
print()
print("**方式一（推荐，可自动更新）** —— 在 IDE 里配置自定义插件仓库后，新版本会自动出现在更新列表：")
print()
print("`Settings | Plugins | ⚙ | Manage Plugin Repositories` 添加：")
print("`https://cdn.jsdelivr.net/gh/JiangWanZhengChouYv/ClionLuogu@main/updatePlugins.xml`")
print()
print("**方式二（手动）** —— 下载 zip 后 `Settings | Plugins | ⚙ | Install Plugin from Disk...`，选该 zip 并重启 IDE：")
print()
print("%s" % asset)
print()
print("### 环境要求")
print()
print("- CLion 2024.3+（`since-build 243`，未设 `until-build` 上限）")
print("- 需启用内置插件 Web Browser (JCEF)（CLion 默认启用）")
print("- 查看题解需先在插件的「登录」页填好登录态：洛谷的题解接口未登录一律返回 401")
print()
print("---")
print()
print("> 非官方工具：仅使用你自己的登录态，不会自动登录、不会绕过验证码。")
print("> 插件 ID：`%s`" % pid)
PY

if gh release view "${TAG}" >/dev/null 2>&1; then
  gh release upload "${TAG}" "${ZIP}" --clobber
  gh release edit "${TAG}" --title "${DIR_NAME} ${VERSION}" --notes-file "${NOTES_FILE}" --latest
  echo "✓ 已更新 Release ${TAG}"
else
  gh release create "${TAG}" "${ZIP}" --title "${DIR_NAME} ${VERSION}" --notes-file "${NOTES_FILE}" --latest
  echo "✓ 已发布 Release ${TAG}"
fi
rm -f "${NOTES_FILE}"

# ---- 6. 更新自定义插件仓库描述文件（含描述 / 变更说明，仓库列表可见）----
python3 - "${ZIP}" "${JAR_IN_ZIP}" "${ASSET_URL}" <<'PY'
import io, re, sys, zipfile
import xml.etree.ElementTree as ET

zip_path, jar_in_zip, url = sys.argv[1], sys.argv[2], sys.argv[3]

# 从构建产物里取权威元数据（id / version / name / idea-version / 描述 / 本版变更说明）
with zipfile.ZipFile(zip_path) as z:
    jar_bytes = z.read(jar_in_zip)
with zipfile.ZipFile(io.BytesIO(jar_bytes)) as z:
    px = z.read("META-INF/plugin.xml").decode("utf-8")

pid = re.search(r"<id>([^<]+)</id>", px).group(1)
ver = re.search(r"<version>([^<]+)</version>", px).group(1)
name = re.search(r"<name>([^<]+)</name>", px).group(1)
idea = re.search(r"<idea-version[^>]*?/>", px)
idea_tag = idea.group(0) if idea else '<idea-version since-build="243"/>'

dm = re.search(r"<description><!\[CDATA\[(.*?)\]\]></description>", px, re.S)
desc = dm.group(1).strip() if dm else ""

cm = re.search(r"<change-notes><!\[CDATA\[(.*?)\]\]></change-notes>", px, re.S)
notes = ""
for li in re.findall(r"<li>(.*?)</li>", cm.group(1) if cm else "", re.S):
    if re.search(r"<b>%s</b>" % re.escape(ver), li):
        notes = li.strip()
        break

# 重建整个 <plugin> 元素（幂等；注意用 <plugin\s 避免匹配到注释里提到的 <plugin> 字样）
new_plugin = (
    '  <plugin\n'
    '          id="%s"\n'
    '          url="%s"\n'
    '          version="%s">\n'
    '    %s\n'
    '    <name>%s</name>\n'
    '    <description><![CDATA[%s]]></description>\n'
    '    <change-notes><![CDATA[%s]]></change-notes>\n'
    '  </plugin>' % (pid, url, ver, idea_tag, name, desc, notes)
)

p = "updatePlugins.xml"
x = open(p, encoding="utf-8").read()
x, n = re.subn(r"<plugin\s.*?</plugin>", new_plugin, x, count=1, flags=re.S)
assert n == 1, "updatePlugins.xml 里没找到 plugin 元素块"
open(p, "w", encoding="utf-8").write(x)

# 写回后重新解析，确保仍是合法 XML 且关键内容都在
root = ET.parse(p).getroot()
el = root.find("plugin")
assert el is not None, "解析后找不到 plugin 元素"
assert el.get("id") == pid and el.get("version") == ver, "id / version 不匹配"
assert el.find("description") is not None and el.find("change-notes") is not None, "缺少描述或变更说明"
print("✓ updatePlugins.xml 已更新: %s %s（含描述与变更说明，XML 合法）" % (pid, ver))
PY
git add updatePlugins.xml
if ! git diff --cached --quiet; then
  git commit -q -m "chore: custom plugin repository points to ${TAG}"
  git push origin main
  echo "✓ 已推送 updatePlugins.xml"
else
  echo "· updatePlugins.xml 无变化"
fi

# ---- 7. 刷新 jsDelivr 缓存（@main 引用默认会被缓存，最长可能 ~12 小时才看到新 XML）----
echo "▶ 刷新 jsDelivr 缓存 ..."
PURGE_RESULT="$(curl -s --max-time 20 "https://purge.jsdelivr.net/gh/${REPO}@main/updatePlugins.xml" || true)"
echo "  ${PURGE_RESULT:0:300}"

echo "🎉 完成: ${DIR_NAME} ${VERSION}"
