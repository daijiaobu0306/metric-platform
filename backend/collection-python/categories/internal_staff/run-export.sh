#!/bin/bash
# 取数入口：读内网数据 → 生成 CSV → 投递到指标体系平台的扫描目录。
#
# 用法：
#   ./run-export.sh /home/appuser/metric-code/raw_data/import/internal_staff
#   OUTPUT_DIR=/home/appuser/metric-code/raw_data/import/internal_staff ./run-export.sh
#
# 额外参数会透传给 source_export.py（如 running_efficiency 支持 --sub-category 系统性能）。
set -euo pipefail

# ============================================================================
# 【现场可改】下面的路径按本机实际情况改；写死的值优先，也可以用同名环境变量覆盖
#   JAVA_HOME     JDK 17 根目录（JPype 起 JVM 用；cron 下不继承登录环境，必须写死）
#   JAVA_BIN      JDK 17 的 java 可执行文件
#   PYTHON        Python 解释器
#   CONNECTIONS   连接配置路径（留空 = 本目录 connections.json）
#   OUTPUT_DIR    平台扫描目录（也可把目录作为第一个参数传给脚本）
# ============================================================================
JAVA_HOME="${JAVA_HOME:-/home/appuser/besinsight/component/jdk17}"
JAVA_BIN="${JAVA_BIN:-/home/appuser/besinsight/component/jdk17/bin/java}"
PYTHON="${PYTHON:-python3}"
CONNECTIONS="${CONNECTIONS:-}"
OUTPUT_DIR="${OUTPUT_DIR:-}"
# ============================================================================

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-${OUTPUT_DIR:-}}"
[ $# -gt 0 ] && shift

if [ -z "$OUT" ]; then
  echo "用法: $0 <平台扫描目录>   （或设置脚本顶部的 OUTPUT_DIR）" >&2
  exit 2
fi

CONN="${CONNECTIONS:-$HERE/connections.json}"
PY="$PYTHON"

# ---- JDK 定位：JPype 起 JVM 依赖 JDK，cron 下必须在这里写死 ----
if [ -n "$JAVA_BIN" ] && [ ! -x "$JAVA_BIN" ]; then
  echo "警告: JAVA_BIN 不可执行: $JAVA_BIN（已忽略该路径）" >&2
  JAVA_BIN=""
fi
if [ -n "$JAVA_HOME" ] && [ ! -d "$JAVA_HOME" ]; then
  echo "警告: JAVA_HOME 目录不存在: $JAVA_HOME（已忽略该路径）" >&2
  JAVA_HOME=""
fi
if [ -z "$JAVA_HOME" ] && [ -n "$JAVA_BIN" ]; then
  JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
fi
if [ -z "$JAVA_BIN" ] && [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi
if [ -n "$JAVA_HOME" ]; then
  export JAVA_HOME
fi
if [ -z "$JAVA_BIN" ]; then
  JAVA_BIN="$(command -v java || true)"
fi
if [ -z "$JAVA_BIN" ]; then
  echo "找不到 java：请修改脚本顶部的 JAVA_HOME / JAVA_BIN（JPype 起 JVM 需要 JDK）" >&2
  exit 2
fi
export JAVA_BIN

[ -f "$CONN" ] || { echo "缺少连接配置: $CONN（参考 connections.example.json）" >&2; exit 2; }
[ -f "$HERE/sources.json" ] || { echo "缺少 sources.json" >&2; exit 2; }
mkdir -p "$OUT"

# Run from cron at 00:10 Beijing time; platform collection is configured separately.
export TZ=Asia/Shanghai
command -v flock >/dev/null || { echo "flock is required" >&2; exit 2; }
mkdir -p "$HERE/state"
exec 9>"$HERE/state/export.lock"
flock -n 9 || exit 0
DAY=$(date -d yesterday +%F)
MARKER="$HERE/state/internal_staff-$DAY.done"
[ -f "$MARKER" ] && { echo "Already exported $DAY"; exit 0; }
STAGING=$(mktemp -d "$OUT/.daily-export.XXXXXXXX")
trap 'rmdir "$STAGING" 2>/dev/null || true' EXIT
"$PY" -X utf8 "$HERE/source_export.py" --source-config "$HERE/sources.json" --connections "$CONN" --output-dir "$STAGING" "$@"
for csv in "$STAGING"/*.csv; do
  [ -f "$csv" ] || { echo "No CSV produced for $DAY" >&2; exit 1; }
  mv -- "$csv" "$OUT/"
done
printf '%s\n' "$DAY" > "$MARKER"
echo "Exported $DAY; CSV ready for next morning collection"
