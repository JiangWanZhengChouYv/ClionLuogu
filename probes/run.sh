#!/usr/bin/env bash
# 离线跑插件逻辑的断言（仓库里没有 src/test，这套探针就是测试）。
#
# 为什么放在仓库里而不是 /tmp：2026-10-03 一次重启把 /tmp 清了，305 条断言的源码整个没了。
# 探针是资产，不是临时文件。
#
# 用法：
#   bash probes/run.sh            # 全部（需要已经 gradle buildPlugin 过一次）
#   bash probes/run.sh core       # 只跑纯逻辑（快，不用显示器）
#   bash probes/run.sh process    # 只跑真子进程（要本机有 clang++/g++）
#   bash probes/run.sh layout     # 只跑布局（要有显示器，不能 headless）
#   bash probes/run.sh meta       # 只跑元数据（python）
#   bash probes/run.sh js         # 只跑浏览器侧正文渲染（需要 node：PATH / nvm / LUOGU_NODE）
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROBE_DIR="$REPO/probes"
OUT="$REPO/build/probes"
mkdir -p "$OUT"

# JDK：优先环境变量，否则在 Gradle 下载的 JDK 里找一个（本机没有系统 JDK）
if [ -z "${PROBE_JDK:-}" ]; then
  PROBE_JDK="$(ls -d "$HOME"/.gradle/jdks/*adoptium*/Contents/Home 2>/dev/null | head -1)"
  [ -z "$PROBE_JDK" ] && PROBE_JDK="$(ls -d "$HOME"/.gradle/jdks/*/jdk-*/Contents/Home 2>/dev/null | head -1)"
fi
if [ -z "${PROBE_JDK:-}" ] || [ ! -x "$PROBE_JDK/bin/javac" ]; then
  echo "找不到 JDK。设 PROBE_JDK=<Contents/Home> 再跑（或先跑一次 ./gradlew compileKotlin 让插件下载 JDK）"
  exit 1
fi

# 平台 jar：CLion 发行包的 transforms 目录（哈希名会变，所以按目录名找）
PLAT="${PROBE_PLATFORM:-}"
if [ -z "$PLAT" ]; then
  PLAT="$(ls -d "$HOME"/.gradle/caches/*/transforms/*/transformed/CLion-* 2>/dev/null | head -1)"
fi
if [ -z "$PLAT" ] || [ ! -d "$PLAT/lib" ]; then
  echo "找不到 CLion 平台包。设 PROBE_PLATFORM=<.../transformed/CLion-2024.3-aarch64> 再跑"
  exit 1
fi

# 发布包里的 kotlin-stdlib / kotlinx 要用真版本：解开一份（幂等）
PKG="$OUT/pkg"
ZIP="$(ls -t "$REPO"/build/distributions/ClionLuogu-*.zip 2>/dev/null | head -1)"
if [ -n "$ZIP" ]; then
  rm -rf "$PKG"; mkdir -p "$PKG"
  unzip -qo "$ZIP" -d "$PKG" || true
fi

# classpath 一律绝对路径：相对路径在 cd 之后会整个失效，症状是「只有本轮新增的类找不到符号」，很骗人
CP="$REPO/build/classes/kotlin/main:$REPO/build/resources/main"
CP="$CP:$(ls "$PLAT"/lib/*.jar 2>/dev/null | tr '\n' ':')"
CP="$CP$(ls "$PKG"/*/lib/*.jar 2>/dev/null | tr '\n' ':')"
echo "$CP" > "$OUT/cp.txt"

# 平台 Swing 要 --add-opens（否则 JBScrollPane 里 InaccessibleObjectException）；
# macOS 的滚动条 UI 要 jna（否则 UnsatisfiedLinkError libjnidispatch）
OPEN="--add-opens java.desktop/javax.swing=ALL-UNNAMED --add-opens java.desktop/javax.swing.plaf.basic=ALL-UNNAMED --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"
JNA="-Djna.boot.library.path=$PLAT/lib/jna/aarch64 -Djna.nosys=true -Djna.noclasspath=true"

# 先编译：探针跑的是 build/classes/kotlin/main，忘了编译就会拿旧字节码断言新行为
# （真发生过：改了 ResourceMeter，探针还是红的，因为类根本没重编）
if [ "${PROBE_SKIP_BUILD:-0}" != "1" ]; then
  ( cd "$REPO" && JAVA_HOME="${JAVA_HOME:-/Applications/CLion.app/Contents/jbr/Contents/Home}" \
      ./gradlew compileKotlin -q 2>&1 | grep -E "^e:" | head -10 )
fi

SUITES=("${@:-all}")
[ "${SUITES[0]}" = "all" ] && SUITES=(core process layout meta js)

# 没跑的套件要记下来、最后喊一遍：1.8.0 两次「假绿」都是因为跳过不吭声
SKIPPED=""

javac_failed=0
for f in CoreProbe ProcessProbe LayoutProbe; do
  [ -f "$PROBE_DIR/$f.java" ] || continue
  if ! "$PROBE_JDK/bin/javac" -nowarn -cp "$CP" -d "$OUT" "$PROBE_DIR/$f.java" 2>"$OUT/$f.javac.log"; then
    echo "!! $f.java 编译失败（接下来跑的是上一次留下的 class，别把这份结果当数）："
    head -12 "$OUT/$f.javac.log"
    javac_failed=1
  fi
done
[ "$javac_failed" = 1 ] && exit 1

total_pass=0
total_fail=0
run_java() {
  local main="$1" headless="$2" wait_slogan="$3" budget="$4"
  local out="$OUT/$main.out"
  # 系统自带 bash 3.2 在 set -u 下展开空数组会直接报错，所以用普通字符串开关
  local hl=""
  [ "$headless" = "headless" ] && hl="-Djava.awt.headless=true"
  # JVM 不会自己退出（平台共享的 I/O pool 是非 daemon 线程），所以取 pid 再 kill
  "$PROBE_JDK/bin/java" $JNA $OPEN $hl -cp "$OUT:$CP" "$main" > "$out" 2>&1 &
  local pid=$! i=0
  while [ "$i" -lt "$budget" ]; do
    grep -qE "$wait_slogan" "$out" 2>/dev/null && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1; i=$((i+1))
  done
  kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  echo "--- $main: $(grep -E '通过 /|passed|结论' "$out" | tail -1)"
  grep "^FAIL" "$out" | head -12
  total_pass=$((total_pass + $(grep -c '^PASS' "$out" || true)))
  total_fail=$((total_fail + $(grep -c '^FAIL' "$out" || true)))
}

for s in "${SUITES[@]}"; do
  case "$s" in
    core)    run_java CoreProbe headless "=== 探针合计" 90 ;;
    process) run_java ProcessProbe headless "=== 探针合计" 240 ;;
    layout)  run_java LayoutProbe gui "布局探针" 120 ;;
    meta)
      echo "--- meta.py:"
      python3 "$PROBE_DIR/meta.py" | tail -3
      ;;
    js)
      # 他的 node 在 ~/.nvm/versions/node/v24.11.1/bin（PATH 第 3 项，由 ~/.zshrc 的 nvm 初始化加进去），
      # 交互 shell 里 `command -v node` 就有；非交互 shell（cron、我的沙箱）没有，所以再兜一次 nvm 目录。
      NODE_BIN="${LUOGU_NODE:-$(command -v node 2>/dev/null || true)}"
      [ -z "$NODE_BIN" ] && NODE_BIN="$(ls -d "$HOME"/.nvm/versions/node/*/bin/node 2>/dev/null | sort -V | tail -1)"
      [ -z "$NODE_BIN" ] && NODE_BIN="/opt/homebrew/bin/node"   # 他装过 brew node 的话
      if [ -n "$NODE_BIN" ] && [ -x "$NODE_BIN" ]; then
        jout="$OUT/js.out"
        "$NODE_BIN" "$PROBE_DIR/js-render-check.mjs" > "$jout" 2>&1
        echo "--- js-render-check: $(grep '检查 ' "$jout" | tail -1)"
        grep "^FAIL" "$jout" | head -12
        grep -q "失败 0 条" "$jout" || total_fail=$((total_fail + 1))
      else
        SKIPPED="$SKIPPED js-render-check.mjs"
        echo "!! 没跑 js-render-check.mjs：PATH 里找不到 node（设 LUOGU_NODE=<node 路径> 再跑）"
      fi
      ;;
    *) echo "未知套件：$s（core / process / layout / meta / js）" ;;
  esac
done

[ -n "$SKIPPED" ] && echo "!! 以下套件本轮没跑：$SKIPPED —— 那部分等于没验证"

echo "=== Java 断言合计：$total_pass 通过 / $total_fail 失败（元数据与布局不计入） ==="
[ "$total_fail" -eq 0 ] || exit 1
