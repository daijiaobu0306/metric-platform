#!/bin/bash
# metric-code 升级包 —— 一键打包（在开发机执行，Windows/Linux/macOS 均可）
#
# 用途：内网服务器已部署平台后，之后的每次交付都用升级包，不再用全量安装包。
#
# 用法（在仓库根目录）：
#   bash deploy/scripts/build-upgrade.sh                  # 全量构建后端+前端并打包
#   bash deploy/scripts/build-upgrade.sh --skip-backend   # 复用已有 jar
#   bash deploy/scripts/build-upgrade.sh --skip-frontend   # 复用已有 frontend/dist
#
# 产物：
#   deploy/out/metric-code-upgrade-<时间戳>.tar.gz
#   deploy/out/metric-code-upgrade-<时间戳>.tar.gz.sha256
#
# 包内只含"程序文件 + 安装脚本"：
#   backend/metric-code.jar   后端与全部 Flyway 迁移（启动时自动补齐）
#   frontend/                 前端静态资源
#   worker/                   采集执行器（脚本 + 分类 reader）
#   bin/apply-upgrade.sh      升级脚本（会先备份数据库再替换文件）
# 不含 config/app.env、不含 files/ 采集目录——升级不会覆盖现场配置与已采集数据。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPLOY="$REPO_ROOT/deploy"
OUT_DIR="$DEPLOY/out"
BUILD_ID="$(date +%Y%m%d%H%M)"
NAME="metric-code-upgrade-$BUILD_ID"
STAGE_ROOT="$DEPLOY/build"
STAGE="$STAGE_ROOT/$NAME"

SKIP_BACKEND=0
SKIP_FRONTEND=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-backend) SKIP_BACKEND=1 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
    --out) shift; OUT_DIR="$1" ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
  shift
done

sep() { echo; echo "==== $* ===="; }
die() { echo "错误: $*" >&2; exit 1; }

sep "0. 环境"
command -v tar >/dev/null 2>&1 || die "缺 tar"
mkdir -p "$OUT_DIR" "$STAGE_ROOT"
rm -rf "$STAGE"
mkdir -p "$STAGE"/{backend,frontend,worker,bin}
echo "仓库: $REPO_ROOT"echo "产物: $OUT_DIR/$NAME.tar.gz"

sep "1. 后端 jar"
if [ "$SKIP_BACKEND" = "0" ]; then
  ( cd "$REPO_ROOT" && mvn -B -q -f backend/pom.xml -DskipTests package ) || die "后端构建失败"
fi
JAR="$REPO_ROOT/backend/target/metric-code.jar"
[ -f "$JAR" ] || die "缺 $JAR，请先构建后端"
cp -f "$JAR" "$STAGE/backend/metric-code.jar"
echo "jar: $(( $(wc -c < "$STAGE/backend/metric-code.jar") / 1024 / 1024 )) MB"

sep "2. 前端静态资源"
if [ "$SKIP_FRONTEND" = "0" ]; then
  # Windows 上 esbuild 在系统临时目录可能被安全策略拒绝（Access is denied），
  # 统一把临时目录指到仓库内，避免打包被环境问题打断。
  BUILD_TMP="$REPO_ROOT/.build-tmp"
  mkdir -p "$BUILD_TMP"
  ( cd "$REPO_ROOT/frontend" && TMPDIR="$BUILD_TMP" TEMP="$(cygpath -w "$BUILD_TMP" 2>/dev/null || echo "$BUILD_TMP")" \
      TMP="$(cygpath -w "$BUILD_TMP" 2>/dev/null || echo "$BUILD_TMP")" npm run build ) || die "前端构建失败"
fi
DIST="$REPO_ROOT/frontend/dist"
[ -f "$DIST/index.html" ] || die "缺 $DIST/index.html，请先构建前端"
cp -a "$DIST"/. "$STAGE/frontend/"
ENTRY=$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' "$STAGE/frontend/index.html" | head -1)
[ -n "$ENTRY" ] && [ -f "$STAGE/frontend/$ENTRY" ] || die "前端入口缺失: ${ENTRY:-未在 index.html 找到 assets/index-*.js}"
echo "前端文件数: $(find "$STAGE/frontend" -type f | wc -l)"

sep "3. 采集执行器"
WORKER_SRC="$REPO_ROOT/backend/collection-python"
[ -f "$WORKER_SRC/worker.py" ] || die "缺 $WORKER_SRC/worker.py"
cp -a "$WORKER_SRC"/. "$STAGE/worker"/
find "$STAGE/worker" -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
find "$STAGE/worker" -type f -name '*.pyc' -delete 2>/dev/null || true
echo "worker 文件数: $(find "$STAGE/worker" -type f | wc -l)"

sep "4. bin 脚本与说明"
# standalone-backup-database.sh 必须随包更新：升级前备份要用它，
# 新版本才支持 METRIC_DB_PASSWORD 覆盖密码（现场密码与 app.env 不一致时用）。
for f in standalone-lib.sh standalone-backup-database.sh apply-upgrade.sh; do
  [ -f "$DEPLOY/scripts/$f" ] || die "缺 deploy/scripts/$f"
  cp -f "$DEPLOY/scripts/$f" "$STAGE/bin/$f"
done
[ -f "$DEPLOY/STANDALONE_DEPLOY_GUIDE.md" ] && cp -f "$DEPLOY/STANDALONE_DEPLOY_GUIDE.md" "$STAGE/STANDALONE_DEPLOY_GUIDE.md"

sep "4.5 采集脚本（外置，不进 jar）"
# 取数/采集脚本作为独立文件随包分发，由 apply-upgrade.sh 安装到
# $UPLOAD_BASE_PATH/collection-scripts/releases/<版本>/，后端启动时直接读磁盘，
# 因此改脚本不需要重新打包 jar。
# 默认打包平台当前绑定的全部取数脚本版本（2026-11-21 核对 collection_source.config_json.worker.version）。
# 历史默认只含两个版本，会导致升级包缺脚本、现场 Worker 找不到版本；新增/调整绑定后必须同步这里。
SCRIPT_VERSIONS="${SCRIPT_VERSIONS:-\
compliance-20261123-001 \
compliance-detail-20261113-001 \
data_quality-20261027-001 \
external_customer-20261027-001 \
internal_staff-production-20261118-001 \
resource_input-20261027-001 \
running_efficiency-production-20261117-001 \
satisfaction_report-20261109-001}"
mkdir -p "$STAGE/collection-scripts/releases"
for v in $SCRIPT_VERSIONS; do
  src_dir="$REPO_ROOT/backend/src/main/resources/collection-scripts/releases/$v"
  [ -d "$src_dir" ] || src_dir="$REPO_ROOT/files/collection-scripts/releases/$v"
  [ -d "$src_dir" ] || die "缺已发布版本目录: $src_dir（先执行 publish-collection-scripts.py）"
  [ -f "$src_dir/manifest.json" ] || die "缺版本清单: $src_dir/manifest.json"
  mkdir -p "$STAGE/collection-scripts/releases/$v"
  cp -a "$src_dir/." "$STAGE/collection-scripts/releases/$v/"
  echo "  打包脚本版本: $v（$(find "$STAGE/collection-scripts/releases/$v" -type f | wc -l) 个文件）"
done

sep "5. 统一换行符为 LF"
has_cr() { [ "$(tr -d '\r' < "$1" | wc -c)" -ne "$(wc -c < "$1")" ]; }
normalize_lf() {
  local f="$1" tmp="$1.lftmp"
  has_cr "$f" || return 0
  tr -d '\r' < "$f" > "$tmp" && mv -f "$tmp" "$f"
}
find "$STAGE" -path "$STAGE/collection-scripts" -prune -o -type f \( -name '*.sh' -o -name '*.py' -o -name '*.json' -o -name '*.txt' \) -print0 \
  | while IFS= read -r -d '' f; do normalize_lf "$f"; done
BAD=""
while IFS= read -r -d '' f; do
  if has_cr "$f"; then BAD="$BAD
$f"; fi
done < <(find "$STAGE" -path "$STAGE/collection-scripts" -prune -o -type f \( -name '*.sh' -o -name '*.py' \) -print0)
[ -z "$BAD" ] || die "存在 CRLF 文本文件: $BAD"

sep "6. 打包前校验"
for f in "$STAGE"/bin/*.sh; do bash -n "$f" || die "语法错误: $f"; done
chmod 755 "$STAGE"/bin/*.sh
[ -f "$STAGE/backend/metric-code.jar" ] || die "缺 jar"
[ -f "$STAGE/frontend/index.html" ] || die "缺前端入口"
[ -f "$STAGE/worker/worker.py" ] || die "缺 worker.py"
# 外置脚本必须与各自 manifest 的 sha256 完全一致（现场据此校验，改一个字节就装不进去）
if [ -d "$STAGE/collection-scripts/releases" ]; then
  for vdir in "$STAGE"/collection-scripts/releases/*/; do
    [ -d "$vdir" ] || continue
    "$(command -v python3 || command -v python)" - "$vdir" <<'PY' || die "脚本清单校验失败: $(basename "$vdir")"
import hashlib,json,sys,pathlib
d=pathlib.Path(sys.argv[1]); m=json.loads((d/"manifest.json").read_text(encoding="utf-8"))
bad=[f for f,h in m["files"].items() if hashlib.sha256((d/f).read_bytes()).hexdigest()!=h]
if bad:
    print("  哈希不符:",bad); sys.exit(1)
print("  清单校验通过:",m.get("version"),len(m["files"]),"个文件")
PY
  done
fi
# 迁移随 jar 交付，这里只做数量提示，便于核对本次是否带上了新迁移
MIG_COUNT=$(find "$REPO_ROOT/backend/src/main/resources/db/migration" -name 'V*.sql' | wc -l)
echo "jar 内迁移脚本: $MIG_COUNT 个（启动时自动补齐缺失版本）"

GIT_REV=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_DIRTY=$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null | wc -l)
cat > "$STAGE/BUILD_INFO.txt" <<EOF
包名: $NAME
包类型: 升级包（不覆盖 config/app.env 与 files/ 采集目录）
BUILD_ID: $BUILD_ID
构建时间: $(date '+%Y-%m-%d %H:%M:%S %z')
git commit: $GIT_REV（工作区变更文件数 $GIT_DIRTY）
后端 jar: $(wc -c < "$STAGE/backend/metric-code.jar") 字节 sha256=$(sha256sum "$STAGE/backend/metric-code.jar" | awk '{print $1}')
前端 assets: $(find "$STAGE/frontend/assets" -type f | wc -l) 个文件
jar 内迁移脚本: $MIG_COUNT 个
构建命令: bash deploy/scripts/build-upgrade.sh
EOF

sep "7. 生成压缩包"
TARBALL="$OUT_DIR/$NAME.tar.gz"
tar -czf "$TARBALL" -C "$STAGE_ROOT" "$NAME"
( cd "$OUT_DIR" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256" )
CLEAN_PY="$(command -v python3 || command -v python || true)"
if [ -n "$CLEAN_PY" ] && [ -d "$STAGE" ]; then
  "$CLEAN_PY" -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$STAGE" || true
else
  rm -rf "$STAGE" || true
fi
[ -d "$STAGE_ROOT" ] && rmdir "$STAGE_ROOT" 2>/dev/null || true

sep "完成"
echo "包:   $TARBALL"
echo "大小: $(( $(wc -c < "$TARBALL") / 1024 / 1024 )) MB"
echo "校验: $OUT_DIR/$NAME.tar.gz.sha256"
echo
echo "目标机执行："
echo "  sha256sum -c $NAME.tar.gz.sha256"
echo "  tar -xzf $NAME.tar.gz && cd $NAME"
echo "  bash bin/apply-upgrade.sh --dry-run     # 先看要做什么"
echo "  bash bin/apply-upgrade.sh               # 正式升级（含数据库备份）"
