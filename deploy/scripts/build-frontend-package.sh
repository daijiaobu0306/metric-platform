#!/usr/bin/env bash
# ============================================================
# 前端静态文件打包脚本 — 用于独立 nginx 主机部署
#
# 用法:
#   bash deploy/scripts/build-frontend-package.sh
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

VERSION="v$(date +%Y.%m.%d)"
PKG_NAME="metric-frontend-static-${VERSION}"
OUTPUT_DIR="$PROJECT_ROOT/deploy"
PKG_DIR="$OUTPUT_DIR/${PKG_NAME}"

echo "================================================"
echo " 前端静态文件打包"
echo " 版本: $VERSION"
echo "================================================"

# 1. 构建前端
echo
echo "[1/4] 构建前端..."
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
cd "$PROJECT_ROOT/frontend"
npm run build
cd "$PROJECT_ROOT"
echo "      ✅ 前端构建完成"

# 2. 创建打包目录
echo
echo "[2/4] 准备打包目录..."
rm -rf "$PKG_DIR"
mkdir -p "$PKG_DIR"

# 复制静态文件
cp -r frontend/dist/* "$PKG_DIR/"

# 复制 nginx 配置
cp deploy/nginx/metric-code-standalone.conf "$PKG_DIR/nginx-metric-code.conf"

# 复制部署脚本
cp deploy/scripts/deploy-frontend.sh "$PKG_DIR/"
chmod +x "$PKG_DIR/deploy-frontend.sh"

echo "      ✅ 打包目录已就绪"

# 3. 打包 + 验证 + MD5
echo
echo "[3/4] 打包..."
cd "$OUTPUT_DIR"
tar czf "${PKG_NAME}.tar.gz" "$PKG_NAME/"

echo "      验证压缩包..."
if gzip -t "${PKG_NAME}.tar.gz"; then
    echo "      ✅ 压缩包完整"
else
    echo "      ❌ 压缩包损坏"
    exit 1
fi

echo "      生成 MD5..."
md5 "${PKG_NAME}.tar.gz" > "${PKG_NAME}.tar.gz.md5"

# 4. 输出结果
PKG_SIZE=$(du -h "${PKG_NAME}.tar.gz" | awk '{print $1}')
PKG_MD5=$(cat "${PKG_NAME}.tar.gz.md5")

echo
echo "================================================"
echo " ✅ 打包完成"
echo "================================================"
echo " 文件: ${OUTPUT_DIR}/${PKG_NAME}.tar.gz"
echo " 大小: $PKG_SIZE"
echo " MD5:  $PKG_MD5"
echo
echo " 包内内容:"
ls -lh "$PKG_DIR/"
echo
echo " 部署方法:"
echo "   1. 传输到 nginx 主机:"
echo "      scp ${PKG_NAME}.tar.gz user@nginx-host:/tmp/"
echo
echo "   2. 在 nginx 主机执行:"
echo "      cd /tmp && tar xzf ${PKG_NAME}.tar.gz"
echo "      cd ${PKG_NAME}"
echo "      bash deploy-frontend.sh --backend <后端IP>"
echo "================================================"
