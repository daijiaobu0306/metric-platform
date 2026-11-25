#!/usr/bin/env bash
# ============================================================
# metric-code 前端静态文件部署脚本（独立 nginx 主机）
#
# 用法:
#   bash deploy-frontend.sh --backend 10.0.0.1
#   bash deploy-frontend.sh --backend 10.0.0.1 --port 8080
# ============================================================

set -euo pipefail

# 默认配置
BACKEND_IP=""
FRONTEND_PORT="28080"
DEPLOY_DIR="/usr/share/nginx/html/metric-code"
NGINX_CONF_DIR="/etc/nginx/conf.d"

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --backend) BACKEND_IP="$2"; shift 2 ;;
        --port) FRONTEND_PORT="$2"; shift 2 ;;
        --dir) DEPLOY_DIR="$2"; shift 2 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

if [ -z "$BACKEND_IP" ]; then
    echo "❌ 必须指定后端地址"
    echo "用法: bash deploy-frontend.sh --backend <后端IP>"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "================================================"
echo " metric-code 前端部署（独立 nginx）"
echo "================================================"
echo " 后端地址: $BACKEND_IP:18080"
echo " 前端端口: $FRONTEND_PORT"
echo " 部署目录: $DEPLOY_DIR"
echo "================================================"

# 1. 创建目录
echo "[1/4] 创建部署目录..."
mkdir -p "$DEPLOY_DIR"
echo "      ✅ 目录已就绪"

# 2. 复制静态文件
echo "[2/4] 部署静态文件..."
cp -r "$SCRIPT_DIR"/* "$DEPLOY_DIR/"
# 清理非静态文件
rm -f "$DEPLOY_DIR/deploy-frontend.sh"
rm -f "$DEPLOY_DIR/nginx-metric-code.conf"
echo "      ✅ 文件已部署"

# 3. 配置 nginx
echo "[3/4] 配置 nginx..."
NGINX_CONF="$NGINX_CONF_DIR/metric-code.conf"
# 备份原配置
if [ -f "$NGINX_CONF" ]; then
    cp "$NGINX_CONF" "${NGINX_CONF}.bak.$(date +%Y%m%d%H%M%S)"
    echo "      已备份原配置"
fi

# 生成配置（替换后端地址和端口）
sed "s|BACKEND_IP|${BACKEND_IP}|g; s|listen 28080|listen ${FRONTEND_PORT}|g" \
    "$SCRIPT_DIR/nginx-metric-code.conf" > "$NGINX_CONF"
echo "      ✅ nginx 配置已更新"

# 4. 测试并重载 nginx
echo "[4/4] 重载 nginx..."
if nginx -t 2>&1; then
    if command -v systemctl >/dev/null 2>&1; then
        systemctl reload nginx
    else
        nginx -s reload
    fi
    echo "      ✅ nginx 已重载"
else
    echo "      ❌ nginx 配置测试失败，请检查 $NGINX_CONF"
    exit 1
fi

echo
echo "================================================"
echo " ✅ 部署完成"
echo "================================================"
echo " 访问地址: http://localhost:${FRONTEND_PORT}/metric-code/"
echo
echo " 如需修改后端地址:"
echo "   sed -i 's/${BACKEND_IP}/新地址/g' $NGINX_CONF"
echo "   nginx -t && systemctl reload nginx"
echo "================================================"
