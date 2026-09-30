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

VERSION="$(awk -F= '/^version/{gsub(/[ \t\r]/,"",$2); print $2; exit}' gradle.properties)"
[ -n "${VERSION}" ] || { echo "✗ 读不到 gradle.properties 里的 version"; exit 1; }
TAG="v${VERSION}"
JAR_IN_ZIP="${DIR_NAME}/lib/${DIR_NAME}-${VERSION}.jar"
ZIP="build/distributions/${DIR_NAME}-${VERSION}.zip"
ASSET_URL="https://github.com/${REPO}/releases/download/${TAG}/${DIR_NAME}-${VERSION}.zip"

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
BUILT_VERSION="$(unzip -p "${ZIP}" "${JAR_IN_ZIP}" | python3 -c '
import sys, zipfile, io, re
z = zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()))
x = z.read("META-INF/plugin.xml").decode("utf-8")
m = re.search(r"<version>([^<]+)</version>", x)
print(m.group(1) if m else "")')"
if [ "${BUILT_VERSION}" != "${VERSION}" ]; then
  echo "✗ 产物版本 ${BUILT_VERSION} 与 ${VERSION} 不一致"; exit 1
fi
echo "✓ 产物版本校验通过: ${BUILT_VERSION}"

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
python3 - "${VERSION}" > "${NOTES_FILE}" <<'PY'
import re, sys
v = sys.argv[1]
x = open("src/main/resources/META-INF/plugin.xml", encoding="utf-8").read()
m = re.search(r"<change-notes><!\[CDATA\[(.*?)\]\]></change-notes>", x, re.S)
item = ""
for li in re.findall(r"<li>(.*?)</li>", m.group(1) if m else "", re.S):
    if re.search(r"<b>%s</b>" % re.escape(v), li):
        item = li.strip()
        break
print(item)
print()
print("---")
print()
print("下载 `ClionLuogu-%s.zip`，在 IDE 中 `Settings -> Plugins -> Install Plugin from Disk...` 选择该 zip 后重启。" % v)
print("已配置自定义插件仓库（Manage Plugin Repositories）的话，IDE 会直接提示更新。")
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

# ---- 6. 更新自定义插件仓库描述文件 ----
python3 - "${VERSION}" "${ASSET_URL}" <<'PY'
import re, sys
import xml.etree.ElementTree as ET

v, url = sys.argv[1], sys.argv[2]
p = "updatePlugins.xml"
x = open(p, encoding="utf-8").read()

# 只改写 <plugin ...> 开标签内部，绝不碰 XML 声明 <?xml version="1.0"?>。
# （踩过坑：直接对整个文件 re.sub version="..." 会改到 XML 声明，写出非法 XML。）
m = re.search(r'<plugin\b[^>]*>', x, re.S)
assert m, "updatePlugins.xml 里找不到 <plugin> 元素"
tag = m.group(0)
new_tag = re.sub(r'url="[^"]*"', 'url="%s"' % url, tag, count=1)
new_tag = re.sub(r'version="[^"]*"', 'version="%s"' % v, new_tag, count=1)
assert new_tag != tag, "updatePlugins.xml 的 <plugin> 里没找到 url / version 属性"
x = x[:m.start()] + new_tag + x[m.end():]
open(p, "w", encoding="utf-8").write(x)

# 写回后重新解析一次，确保仍是合法 XML
root = ET.parse(p).getroot()
el = root.find("plugin")
assert el is not None and el.get("version") == v, "updatePlugins.xml 校验失败"
print("✓ updatePlugins.xml 已指向 %s（XML 合法）" % v)
PY
git add updatePlugins.xml
if ! git diff --cached --quiet; then
  git commit -q -m "chore: custom plugin repository points to ${TAG}"
  git push origin main
  echo "✓ 已推送 updatePlugins.xml"
else
  echo "· updatePlugins.xml 无变化"
fi

echo "🎉 完成: ${DIR_NAME} ${VERSION}"
