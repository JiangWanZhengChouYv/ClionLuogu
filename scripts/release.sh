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
# 前置：已登录 gh（gh auth status）、仓库 origin 可用
set -euo pipefail

cd "$(dirname "$0")/.."

REPO="JiangWanZhengChouYv/ClionLuogu"
PLUGIN_DIR_NAME="ClionLuogu"

VERSION="$(sed -n 's/^version[[:space:]]*=[[:space:]]*//p' gradle.properties | head -1 | tr -d '[:space:]')"
[ -n "$VERSION" ] || { echo "✗ 读不到 gradle.properties 里的 version"; exit 1; }
TAG="v$VERSION"
JAR_PATH_IN_ZIP="$PLUGIN_DIR_NAME/lib/$PLUGIN_DIR_NAME-$VERSION.jar"
ZIP="build/distributions/$PLUGIN_DIR_NAME-$VERSION.zip"
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$PLUGIN_DIR_NAME-$VERSION.zip"

echo "▶ 准备发版：$VERSION（tag $TAG）"

# ---- 1. 构建 ----
echo "▶ 构建 …"
./gradlew buildPlugin -x buildSearchableOptions -q
[ -f "$ZIP" ] || { echo "✗ 未找到产物 $ZIP"; exit 1; }

# ---- 2. 产物自检：jar 名与内嵌 <version> 都要与 gradle.properties 一致 ----
if ! unzip -l "$ZIP" | grep -q "$JAR_PATH_IN_ZIP"; then
  echo "✗ 产物里没有 $JAR_PATH_IN_ZIP（版本号没对齐？）"; exit 1
fi
BUILT_VERSION="$(unzip -p "$ZIP" "$JAR_PATH_IN_ZIP" | python3 -c '
import sys, zipfile, io, re
z = zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()))
x = z.read("META-INF/plugin.xml").decode("utf-8")
m = re.search(r"<version>([^<]+)</version>", x)
print(m.group(1) if m else "")')"
[ "$BUILT_VERSION" = "$VERSION" ] || { echo "✗ 产物版本 $BUILT_VERSION ≠ $VERSION"; exit 1; }
echo "✓ 产物版本校验通过：$BUILT_VERSION"

# ---- 3. 提交并推送 main ----
if [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -q -m "release: $PLUGIN_DIR_NAME $VERSION"
  echo "✓ 已提交"
else
  echo "· 工作区干净，跳过提交"
fi
git push origin main
echo "✓ 已推送 main"

# ---- 4. tag ----
git tag -f "$TAG" >/dev/null
git push -f origin "$TAG" >/dev/null 2>&1
echo "✓ 已推送 tag $TAG"

# ---- 5. Release（说明取自 plugin.xml 的 change-notes，保持两处一致）----
NOTES_FILE="$(mktemp)"
python3 - "$VERSION" > "$NOTES_FILE" <<'PY'
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
print("下载 `ClionLuogu-%s.zip`，在 IDE 中 `Settings → Plugins → ⚙️ → Install Plugin from Disk…` 选择该 zip 后重启。" % v)
print("已配置自定义插件仓库的话，IDE 会直接提示更新。")
PY

if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$ZIP" --clobber
  gh release edit "$TAG" --title "$PLUGIN_DIR_NAME $VERSION" --notes-file "$NOTES_FILE" --latest
  echo "✓ 已更新 Release $TAG"
else
  gh release create "$TAG" "$ZIP" --title "$PLUGIN_DIR_NAME $VERSION" --notes-file "$NOTES_FILE" --latest
  echo "✓ 已发布 Release $TAG"
fi
rm -f "$NOTES_FILE"

# ---- 6. 更新自定义插件仓库描述文件 ----
python3 - "$VERSION" "$ASSET_URL" <<'PY'
import re, sys
v, url = sys.argv[1], sys.argv[2]
p = "updatePlugins.xml"
x = open(p, encoding="utf-8").read()
x, n1 = re.subn(r'url="[^"]*"', 'url="%s"' % url, x, count=1)
x, n2 = re.subn(r'version="[^"]*"', 'version="%s"' % v, x, count=1)
assert n1 == 1 and n2 == 1, "updatePlugins.xml 结构不符合预期"
open(p, "w", encoding="utf-8").write(x)
print("✓ updatePlugins.xml → %s" % v)
PY
git add updatePlugins.xml
if ! git diff --cached --quiet; then
  git commit -q -m "chore: 自定义插件仓库指向 $TAG"
  git push origin main
  echo "✓ 已推送 updatePlugins.xml"
else
  echo "· updatePlugins.xml 无变化"
fi

echo "🎉 完成：$PLUGIN_DIR_NAME $VERSION"
