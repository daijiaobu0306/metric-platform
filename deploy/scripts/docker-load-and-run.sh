#!/usr/bin/env bash
# ============================================================
# 生产服务器端：加载离线镜像 + 启动容器 + 健康检查
#
# 前置:
#   - 已 scp 镜像 tar.gz 到当前目录
#   - 已 scp docker-compose.yml 到当前目录
#   - 已配置 .env（首次需 cp .env.example .env 然后编辑）
#
# 用法:
#   VERSION=abc123-20260615 bash docker-load-and-run.sh metric-code-abc123-20260615.tar.gz
#
# 也可不指定 VERSION，脚本会从文件名解析
# ============================================================

set -euo pipefail

# 0. 参数与依赖检查
if [ $# -lt 1 ]; then
    echo "❌ 用法: $0 <镜像tar.gz文件名>"
    echo "   例: $0 metric-code-abc123-20260615.tar.gz"
    exit 1
fi

TARBALL="$1"

if ! command -v docker >/dev/null 2>&1; then
    echo "❌ docker 未安装"
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "❌ docker compose 插件未安装（需要 Docker 20.10+）"
    exit 1
fi

if [ ! -f "$TARBALL" ]; then
    echo "❌ 镜像文件不存在: $TARBALL"
    exit 1
fi

if [ ! -f ".env" ]; then
    echo "❌ .env 文件不存在"
    echo "   请先: cp .env.example .env  然后编辑填写实际配置"
    exit 1
fi

if [ ! -f "docker-compose.yml" ]; then
    echo "❌ docker-compose.yml 不存在于当前目录"
    exit 1
fi

# 1. 解析版本号（参数 VERSION 优先，否则从文件名解析）
if [ -z "${VERSION:-}" ]; then
    # 从 metric-code-{VERSION}.tar.gz 提取
    VERSION=$(echo "$TARBALL" | sed -E 's/^metric-code-(.+)\.tar\.gz$/\1/')
    if [ "$VERSION" = "$TARBALL" ]; then
        echo "⚠️  无法从文件名解析版本号，请显式指定: VERSION=xxx $0 $TARBALL"
        exit 1
    fi
fi

echo "================================================"
echo " 加载镜像版本: $VERSION"
echo " 镜像文件:    $TARBALL"
echo "================================================"

# 2. 加载镜像
echo "[1/5] 正在加载镜像（请稍候）..."
gunzip -c "$TARBALL" | docker load
echo "      ✅ 镜像已加载"

# 3. 验证 .env 关键变量
echo "[2/5] 检查 .env 配置..."
set -a
source .env
set +a

: "${DB_URL:?❌ .env 中缺少 DB_URL}"
: "${DB_USER:?❌ .env 中缺少 DB_USER}"
: "${DB_PASSWORD:?❌ .env 中缺少 DB_PASSWORD}"
: "${HOST_FILES_PATH:?❌ .env 中缺少 HOST_FILES_PATH}"

# 验证宿主机挂载目录存在
if [ ! -d "$HOST_FILES_PATH" ]; then
    echo "      ⚠️  HOST_FILES_PATH 目录不存在，自动创建: $HOST_FILES_PATH"
    mkdir -p "$HOST_FILES_PATH"
fi
mkdir -p "${HOST_LOGS_PATH:-./logs}"
echo "      ✅ .env 配置正常"

# 4. 停止旧容器 + 启动新版本
echo "[3/5] 停止旧容器..."
VERSION=$VERSION docker compose down 2>/dev/null || true

echo "[4/5] 启动新版本容器..."
VERSION=$VERSION docker compose up -d
echo "      ✅ 容器已启动"

# 5. 等待健康检查通过
echo "[5/5] 等待备份、迁移及应用启动（最多 20 分钟）..."
APP_PORT="${APP_PORT:-18080}"
for i in $(seq 1 240); do
    sleep 5
    if curl -fsS "http://localhost:${APP_PORT}/api/session/config" >/dev/null 2>&1; then
        echo "      ✅ 应用健康检查通过"
        break
    fi
    echo "      ... 第 ${i}/240 次探测，未就绪，继续等待"
    if [ $i -eq 240 ]; then
        echo
        echo "❌ 健康检查超时，查看日志:"
        docker compose logs --tail 100 backend
        exit 1
    fi
done

echo
echo "================================================"
echo " ✅ 部署完成"
echo "================================================"
echo " 镜像版本: $VERSION"
echo " 监听端口: $APP_PORT"
echo " 数据目录: $HOST_FILES_PATH"
echo " 日志目录: ${HOST_LOGS_PATH:-./logs}"
echo
echo " 查看实时日志: docker compose logs -f backend"
echo " 查看容器状态: docker compose ps"
echo "================================================"
