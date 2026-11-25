#!/usr/bin/env bash
# ============================================================
# Mac/开发机本地构建 Docker 镜像 + 打包为离线 tar.gz
#
# 用法:
#   bash deploy/scripts/docker-build.sh           # 用 git commit hash 作为版本号
#   bash deploy/scripts/docker-build.sh v1.2.0    # 用指定版本号
#
# 产物:
#   deploy/metric-code-{VERSION}.tar.gz   离线镜像包，scp 到生产服务器
# ============================================================

set -euo pipefail

# 项目根目录（脚本所在目录的上两级）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# 版本号：参数 > git short hash > 时间戳
if [ "${1:-}" != "" ]; then
    VERSION="$1"
elif git rev-parse --short HEAD >/dev/null 2>&1; then
    VERSION="$(git rev-parse --short HEAD)-$(date +%Y%m%d)"
else
    VERSION="$(date +%Y%m%d_%H%M%S)"
fi

IMAGE_NAME="metric-code"
IMAGE_TAG="${IMAGE_NAME}:${VERSION}"
LATEST_TAG="${IMAGE_NAME}:latest"
OUTPUT_DIR="$PROJECT_ROOT/deploy"
OUTPUT_FILE="$OUTPUT_DIR/${IMAGE_NAME}-${VERSION}.tar.gz"

echo "================================================"
echo " 构建镜像: $IMAGE_TAG"
echo " 输出路径: $OUTPUT_FILE"
echo "================================================"

# 1. 构建镜像（锁死 linux/amd64，避免 Apple Silicon 默产 arm64）
docker build \
    --platform linux/amd64 \
    -t "$IMAGE_TAG" \
    -f "$PROJECT_ROOT/deploy/docker/Dockerfile" \
    "$PROJECT_ROOT"

# 2. 导出为压缩 tar 包
mkdir -p "$OUTPUT_DIR"
echo "正在导出镜像为 tar.gz（体积较大，请稍候）..."
docker save "$IMAGE_TAG" | gzip > "$OUTPUT_FILE"

# 3. 显示结果
SIZE=$(du -h "$OUTPUT_FILE" | awk '{print $1}')
echo
echo "================================================"
echo " ✅ 构建完成"
echo "================================================"
echo " 镜像 tag:  $IMAGE_TAG"
echo " 输出文件:  $OUTPUT_FILE"
echo " 文件大小:  $SIZE"
echo
echo " 下一步（在 Mac 上执行）:"
echo "   scp $OUTPUT_FILE user@prod-server:/home/appuser/metric-code2/"
echo "   scp deploy/docker-compose.yml user@prod-server:/home/appuser/metric-code2/"
echo "   scp .env             user@prod-server:/home/appuser/metric-code2/   # 仅首次或有变更"
echo
echo " 然后在生产服务器执行:"
echo "   cd /home/appuser/metric-code2"
echo "   VERSION=$VERSION bash deploy/scripts/docker-load-and-run.sh metric-code-${VERSION}.tar.gz"
echo "================================================"
