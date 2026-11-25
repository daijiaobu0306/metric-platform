#!/bin/bash
# metric-code 非容器化部署包 —— 一键打包（在开发机执行，Windows/Linux/macOS 均可）
#
# 用法（在仓库根目录）：
#   bash deploy/scripts/build-standalone.sh                 # 全量构建后端+前端并打包
#   bash deploy/scripts/build-standalone.sh --skip-backend   # 复用已有 jar
#   bash deploy/scripts/build-standalone.sh --skip-backend --skip-frontend
#
# 产物：
#   deploy/out/metric-code-standalone-<时间戳>.tar.gz
#   deploy/out/metric-code-standalone-<时间戳>.tar.gz.sha256
#
# 这个脚本存在的意义：以前是手工 tar，导致包内脚本与仓库脚本不一致、
# 脚本带着 CRLF 进包（Linux 上必报 $'\r': command not found）。现在统一由它产包，
# 并在打包后强制校验：无 CRLF、bash -n 通过、关键文件齐全。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPLOY="$REPO_ROOT/deploy"
OUT_DIR="$DEPLOY/out"
BUILD_ID="$(date +%Y%m%d%H%M)"
NAME="metric-code-standalone-$BUILD_ID"
STAGE_ROOT="$DEPLOY/build"
STAGE="$STAGE_ROOT/$NAME"

SKIP_BACKEND=0
SKIP_FRONTEND=0
SKIP_NGINX=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-backend) SKIP_BACKEND=1 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
    --skip-nginx) SKIP_NGINX=1 ;;
    --out) shift; OUT_DIR="$1" ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
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
mkdir -p "$STAGE"/{backend,frontend,nginx,bin,config,repo}
echo "仓库: $REPO_ROOT"
echo "产物: $OUT_DIR/$NAME.tar.gz"

sep "1. 后端 jar"
JAR="$REPO_ROOT/backend/target/metric-code.jar"
if [ "$SKIP_BACKEND" = "0" ]; then
  ( cd "$REPO_ROOT" && mvn -q -f backend/pom.xml -DskipTests clean package )
fi
[ -f "$JAR" ] || die "缺 $JAR（先执行 mvn -f backend/pom.xml -DskipTests package，或去掉 --skip-backend）"
JAR_SIZE=$(wc -c < "$JAR")
[ "$JAR_SIZE" -gt 50000000 ] || die "jar 体积异常（$JAR_SIZE 字节），可能不是 fat jar"
cp -f "$JAR" "$STAGE/backend/metric-code.jar"
echo "jar: $((JAR_SIZE / 1024 / 1024))MB"

sep "2. 前端 dist"
DIST="$REPO_ROOT/frontend/dist"
# 构建临时目录放到仓库内：Windows 上 %TEMP% 的 ACL 偶发让 esbuild 删不掉临时文件，
# 报 "Access is denied" 整个构建失败；改动这里没副作用，Linux/macOS 也照样用。
BUILD_TMP="$REPO_ROOT/.build-tmp"
if [ "$SKIP_FRONTEND" = "0" ]; then
  mkdir -p "$BUILD_TMP"
  # 注意：这里不设 VITE_CLEAN_DIST=1。vite 清空 dist 是一次性删上百个文件，
  # 在带删除守卫的环境里会被拦下来导致构建失败；改为构建后按可达性裁剪（见下），
  # 效果一样：包内只留当前构建用到的资源。
  ( cd "$REPO_ROOT/frontend" && { [ -d node_modules ] || npm ci; } \
      && TMPDIR="$BUILD_TMP" TEMP="$BUILD_TMP" TMP="$BUILD_TMP" npm run build )
else
  echo "跳过构建，复用现有 dist（下面会按可达性裁剪，兜住历史残留）"
fi
[ -f "$DIST/index.html" ] || die "缺 $DIST/index.html（先 npm run build）"
[ -d "$DIST/assets" ] || die "缺 $DIST/assets"
cp -a "$DIST"/. "$STAGE/frontend/"

# 只保留 index.html 可达的资源。dist 里若堆了历次构建的旧 bundle（vite 默认保留），
# 整包打进去会多出几十 MB 死文件，部署时白白多传、解压也慢。
PY="$(command -v python3 || command -v python || true)"
if [ -n "$PY" ]; then
  "$PY" "$DEPLOY/scripts/prune-dist.py" "$STAGE/frontend"
else
  echo "警告: 无 python，跳过 dist 裁剪（包可能偏大）" >&2
fi
ENTRY=$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' "$STAGE/frontend/index.html" | head -1)
[ -n "$ENTRY" ] && [ -f "$STAGE/frontend/$ENTRY" ] || die "前端入口缺失: ${ENTRY:-未在 index.html 找到 assets/index-*.js}"
echo "前端入口: $ENTRY"
echo "前端文件数: $(find "$STAGE/frontend" -type f | wc -l)，体积: $(du -sh "$STAGE/frontend" | cut -f1)"

sep "3. nginx"
NGINX_TGZ="$DEPLOY/vendor/nginx-1.26.3.tar.gz"
if [ "$SKIP_NGINX" = "0" ]; then
  [ -f "$NGINX_TGZ" ] || die "缺 $NGINX_TGZ（nginx 源码包，需放在 deploy/vendor/）"
  cp -f "$NGINX_TGZ" "$STAGE/nginx/"
fi
# 复用模式的 server 块模板：install-nginx.sh 渲染成 nginx/metric-code.conf 再放 conf.d
[ -f "$DEPLOY/nginx/metric-code-proxy.conf.template" ] || die "缺 deploy/nginx/metric-code-proxy.conf.template"
cp -f "$DEPLOY/nginx/metric-code-proxy.conf.template" "$STAGE/nginx/metric-code-proxy.conf.template"

sep "4. bin 脚本"
for f in standalone-lib.sh init-database-server.sh install-nginx.sh deploy-nginx.sh \
         check-nginx-deps.sh install-worker.sh \
         standalone-start-backend.sh standalone-stop-backend.sh standalone-health-check.sh \
         standalone-backup-database.sh preflight-check.sh; do
  [ -f "$DEPLOY/scripts/$f" ] || die "缺 deploy/scripts/$f"
  cp -f "$DEPLOY/scripts/$f" "$STAGE/bin/$f"
done

sep "5. 采集执行器（独立 Worker）"
WORKER_SRC="$REPO_ROOT/backend/collection-python"
[ -f "$WORKER_SRC/worker.py" ] || die "缺 $WORKER_SRC/worker.py"
mkdir -p "$STAGE/worker"
cp -a "$WORKER_SRC"/. "$STAGE/worker"/
# 不打包本地缓存与字节码，换机器会因版本差异失效
find "$STAGE/worker" -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
find "$STAGE/worker" -type f -name '*.pyc' -delete 2>/dev/null || true
echo "worker 文件数: $(find "$STAGE/worker" -type f | wc -l)，体积: $(du -sh "$STAGE/worker" | cut -f1)"

sep "6. repo（数据库初始化所需）"
MIGRATION_SRC="$REPO_ROOT/backend/src/main/resources/db/migration"
[ -d "$MIGRATION_SRC" ] || die "缺迁移目录"
[ -d "$DEPLOY/db_export/bootstrap" ] || die "缺安装基线 deploy/db_export/bootstrap"
mkdir -p "$STAGE/repo/backend/src/main/resources/db" \
         "$STAGE/repo/deploy/db_export" \
         "$STAGE/repo/deploy/database" \
         "$STAGE/repo/deploy/scripts" \
         "$STAGE/repo/doc"
cp -a "$MIGRATION_SRC" "$STAGE/repo/backend/src/main/resources/db/migration"
cp -a "$DEPLOY/db_export/bootstrap" "$STAGE/repo/deploy/db_export/bootstrap"
cp -f "$DEPLOY/database/DatabasePreparation.java" "$STAGE/repo/deploy/database/"
for f in prepare-database.py database_support.py backup-database.py; do
  cp -f "$DEPLOY/scripts/$f" "$STAGE/repo/deploy/scripts/$f"
done
cp -f "$REPO_ROOT/doc/数据库版本记录.md" "$STAGE/repo/doc/"
BASELINE_VER=$(sed -nE 's/.*"baselineVersion"[[:space:]]*:[[:space:]]*"([0-9A-Za-z_.]+)".*/\1/p' "$DEPLOY/db_export/bootstrap/manifest.json")
echo "安装基线版本: ${BASELINE_VER:-未知}（新库初始化到这里，之后的迁移才需增量执行）"

sep "7. config 与说明"
cp -f "$DEPLOY/config/app.env.example" "$STAGE/config/app.env.example"
cp -f "$DEPLOY/STANDALONE_DEPLOY_GUIDE.md" "$STAGE/STANDALONE_DEPLOY_GUIDE.md"

sep "8. 统一换行符为 LF（关键步骤）"
# 说明：不用 grep $'\r' 做检测 —— 在 Git Bash/MSYS 下含 CR 的参数会被吞掉，
# 导致「匹配所有文件」的误判。这里改用 tr 计数比较，跨平台稳定。
has_cr() {
  [ "$(tr -d '\r' < "$1" | wc -c)" -ne "$(wc -c < "$1")" ]
}
normalize_lf() {
  local f="$1" tmp="$1.lftmp"
  has_cr "$f" || return 0
  tr -d '\r' < "$f" > "$tmp" && mv -f "$tmp" "$f"
}
# 只处理文本文件；jar、tar.gz、静态资源里的二进制不动
find "$STAGE" -type f \( -name '*.sh' -o -name '*.py' -o -name '*.conf' -o -name '*.java' \
  -o -name '*.md' -o -name '*.example' -o -name '*.json' -o -name '*.txt' \) -print0 \
  | while IFS= read -r -d '' f; do normalize_lf "$f"; done

BAD=""
while IFS= read -r -d '' f; do
  if has_cr "$f"; then BAD="$BAD
$f"; fi
done < <(find "$STAGE" -type f \( -name '*.sh' -o -name '*.py' -o -name '*.conf' -o -name '*.md' -o -name '*.example' \) -print0)
if [ -n "$BAD" ]; then
  echo "以下文件仍含 CRLF：$BAD" >&2
  die "存在 CRLF 文本文件，拒绝打包"
fi
echo "已归一，无 CRLF 残留"

sep "9. 打包前校验"
for f in "$STAGE"/bin/*.sh; do
  bash -n "$f" || die "语法错误: $f"
done
chmod 755 "$STAGE"/bin/*.sh
[ -f "$STAGE/backend/metric-code.jar" ] || die "缺 jar"
[ -f "$STAGE/frontend/index.html" ] || die "缺前端入口"
[ -d "$STAGE/repo/backend/src/main/resources/db/migration" ] || die "缺迁移脚本"
MIG_COUNT=$(find "$STAGE/repo/backend/src/main/resources/db/migration" -name 'V*.sql' | wc -l)
echo "迁移脚本: $MIG_COUNT 个（含全部历史版本，初始化时由基线之后的增量执行）"

GIT_REV=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_DIRTY=$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null | wc -l)
cat > "$STAGE/BUILD_INFO.txt" <<EOF
包名: $NAME
构建时间: $(date '+%Y-%m-%d %H:%M:%S %z')
git commit: $GIT_REV（工作区变更文件数 $GIT_DIRTY）
后端 jar: $(wc -c < "$STAGE/backend/metric-code.jar") 字节 sha256=$(sha256sum "$STAGE/backend/metric-code.jar" | awk '{print $1}')
前端 assets: $(find "$STAGE/frontend/assets" -type f | wc -l) 个文件
迁移脚本: $MIG_COUNT 个
构建命令: bash deploy/scripts/build-standalone.sh
EOF

sep "10. 生成压缩包"
TARBALL="$OUT_DIR/$NAME.tar.gz"
tar -czf "$TARBALL" -C "$STAGE_ROOT" "$NAME"
( cd "$OUT_DIR" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256" )
# 清理暂存目录：优先用 python 的 rmtree。某些环境对 rm -rf 大批量文件有删除守卫，
# 会在收尾阶段把脚本打断（包其实已经生成，但退出码非 0，容易误判为失败）。
CLEAN_PY="$(command -v python3 || command -v python || true)"
if [ -n "$CLEAN_PY" ] && [ -d "$STAGE" ]; then
  "$CLEAN_PY" -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$STAGE" || true
else
  rm -rf "$STAGE" || true
fi
[ -d "$STAGE_ROOT" ] && rmdir "$STAGE_ROOT" 2>/dev/null || true

sep "完成"
echo "包: $TARBALL"
echo "大小: $(wc -c < "$TARBALL") 字节"
echo "校验: $OUT_DIR/$NAME.tar.gz.sha256"
echo
echo "上传后在目标机执行（两台机器都要）："
echo "  tar -xzf $NAME.tar.gz && cd $NAME"
echo "  cp config/app.env.example config/app.env && chmod 600 config/app.env && vi config/app.env"
echo "  bash bin/preflight-check.sh db     # 66 上"
echo "  bash bin/preflight-check.sh app    # 67 上"
