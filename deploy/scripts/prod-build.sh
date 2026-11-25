#!/usr/bin/env bash
# ============================================================
# 生产环境构建脚本 — 同时构建 backend + frontend 两个镜像
#
# 用法:
#   bash deploy/scripts/prod-build.sh              # 默认版本号: v2026.07.03
#   bash deploy/scripts/prod-build.sh v2026.07.03   # 手动指定版本号（不带 -amd64 后缀）
#
# 产物:
#   deploy/metric-backend-{VERSION}-amd64.tar.gz   （完整离线镜像，linux/amd64 架构）
#   deploy/metric-frontend-{VERSION}-amd64.tar.gz  （完整离线镜像，linux/amd64 架构）
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# 版本号：参数 > 日期格式
if [ "${1:-}" != "" ]; then
    VERSION="$1"
else
    VERSION="v$(date +%Y.%m.%d)"
fi

OUTPUT_DIR="$PROJECT_ROOT/deploy"

echo "================================================"
echo " 构建版本: $VERSION"
echo "================================================"

# ---- 1. 构建前端 ----
echo
echo "[1/4] 构建前端..."
if ! command -v node >/dev/null 2>&1; then
    echo "❌ node 未安装或不在 PATH 中"
    echo "   Mac Apple Silicon: brew install node@24 然后 export PATH=\"\$(brew --prefix node@24)/bin:\$PATH\""
    exit 1
fi
cd "$PROJECT_ROOT/frontend"
npm run build
cd "$PROJECT_ROOT"
echo "      ✅ 前端构建完成"

# ---- 2. 构建后端镜像 ----
echo
echo "[2/4] 构建后端镜像 metric-backend:$VERSION (linux/amd64) ..."
docker build \
    --platform linux/amd64 \
    -t "metric-backend:$VERSION" \
    -f "$PROJECT_ROOT/deploy/docker/Dockerfile" \
    "$PROJECT_ROOT"
echo "      ✅ 后端镜像构建完成"

# ---- 3. 构建前端镜像 ----
echo
echo "[3/4] 构建前端镜像 metric-frontend:$VERSION (linux/amd64) ..."
docker build \
    --platform linux/amd64 \
    -t "metric-frontend:$VERSION" \
    -f "$PROJECT_ROOT/deploy/docker/Dockerfile.frontend" \
    "$PROJECT_ROOT"
echo "      ✅ 前端镜像构建完成"

# ---- 3.5 架构断言：拒绝非 amd64 镜像 ----
echo
echo "[3.5/5] 验证镜像架构为 linux/amd64 ..."
BACKEND_ARCH=$(docker image inspect --format '{{.Architecture}}/{{.Os}}' "metric-backend:$VERSION")
if [ "$BACKEND_ARCH" != "amd64/linux" ]; then
    echo "❌ 后端镜像架构为 $BACKEND_ARCH，但生产是 linux/amd64"
    echo "   请确认 docker buildx 默认 target platform 设置正确，或显式加 --platform linux/amd64"
    exit 1
fi
echo "      ✅ backend architecture: $BACKEND_ARCH"

# ---- 4. 导出镜像 + 验证 + 生成 MD5 ----
echo
echo "[4/5] 导出镜像为 tar.gz ..."
mkdir -p "$OUTPUT_DIR"

echo "      导出后端镜像..."
docker save "metric-backend:$VERSION" | gzip > "$OUTPUT_DIR/metric-backend-${VERSION}-amd64.tar.gz"

echo "      导出前端镜像..."
docker save "metric-frontend:$VERSION" | gzip > "$OUTPUT_DIR/metric-frontend-${VERSION}-amd64.tar.gz"

# ---- 5. 验证文件完整性 + 生成 MD5 ----
echo
echo "[5/5] 验证文件完整性..."

ERRORS=0

# 验证后端镜像
echo "      验证 metric-backend-${VERSION}-amd64.tar.gz ..."
if gzip -t "$OUTPUT_DIR/metric-backend-${VERSION}-amd64.tar.gz" 2>/dev/null; then
    echo "        ✅ 压缩包完整"
else
    echo "        ❌ 压缩包损坏！"
    ERRORS=$((ERRORS + 1))
fi

# 验证前端镜像
echo "      验证 metric-frontend-${VERSION}-amd64.tar.gz ..."
if gzip -t "$OUTPUT_DIR/metric-frontend-${VERSION}-amd64.tar.gz" 2>/dev/null; then
    echo "        ✅ 压缩包完整"
else
    echo "        ❌ 压缩包损坏！"
    ERRORS=$((ERRORS + 1))
fi

# 生成 MD5 校验文件
echo
echo "      生成 MD5 校验文件..."
cd "$OUTPUT_DIR"
md5 "metric-backend-${VERSION}-amd64.tar.gz" > "metric-backend-${VERSION}-amd64.tar.gz.md5"
md5 "metric-frontend-${VERSION}-amd64.tar.gz" > "metric-frontend-${VERSION}-amd64.tar.gz.md5"
cd "$PROJECT_ROOT"

BACKEND_SIZE=$(du -h "$OUTPUT_DIR/metric-backend-${VERSION}-amd64.tar.gz" | awk '{print $1}')
FRONTEND_SIZE=$(du -h "$OUTPUT_DIR/metric-frontend-${VERSION}-amd64.tar.gz" | awk '{print $1}')
BACKEND_MD5=$(cat "$OUTPUT_DIR/metric-backend-${VERSION}-amd64.tar.gz.md5")
FRONTEND_MD5=$(cat "$OUTPUT_DIR/metric-frontend-${VERSION}-amd64.tar.gz.md5")

if [ $ERRORS -gt 0 ]; then
    echo
    echo "❌ 有 $ERRORS 个文件验证失败，请检查！"
    exit 1
fi

echo
echo "================================================"
echo " ✅ 构建完成"
echo "================================================"
echo " 后端镜像: metric-backend:$VERSION ($BACKEND_SIZE)"
echo "   MD5: $BACKEND_MD5"
echo " 前端镜像: metric-frontend:$VERSION ($FRONTEND_SIZE)"
echo "   MD5: $FRONTEND_MD5"
echo
echo " 产物路径:"
echo "   $OUTPUT_DIR/metric-backend-${VERSION}-amd64.tar.gz"
echo "   $OUTPUT_DIR/metric-frontend-${VERSION}-amd64.tar.gz"
echo "   $OUTPUT_DIR/metric-backend-${VERSION}-amd64.tar.gz.md5"
echo "   $OUTPUT_DIR/metric-frontend-${VERSION}-amd64.tar.gz.md5"
echo
echo " 上传到服务器后，验证 MD5:"
echo "   md5sum metric-backend-${VERSION}-amd64.tar.gz   # 对比上方 MD5 值"
echo "   md5sum metric-frontend-${VERSION}-amd64.tar.gz  # 对比上方 MD5 值"
echo
echo " 下一步 — 传输到生产服务器:"
echo "   scp $OUTPUT_DIR/metric-backend-${VERSION}-amd64.tar.gz*  user@prod:/home/deploy/metric-code/"
echo "   scp $OUTPUT_DIR/metric-frontend-${VERSION}-amd64.tar.gz* user@prod:/home/deploy/metric-code/"
echo "   scp deploy/docker-compose.prod.yml  user@prod:/home/deploy/metric-code/docker-compose.yml"
echo "   scp deploy/config/.env.production          user@prod:/home/deploy/metric-code/.env"
echo "   scp deploy/scripts/prod-load-and-run.sh user@prod:/home/deploy/metric-code/"
echo
echo " 然后在生产服务器执行:"
echo "   cd /home/deploy/metric-code"
echo "   bash prod-load-and-run.sh"
echo "================================================"
