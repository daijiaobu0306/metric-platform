#!/usr/bin/env bash
# ============================================================
# 生产服务器端：加载镜像 + 启动容器 + 健康检查
#
# 前置:
#   - 已 scp 两个镜像 tar.gz 到当前目录
#   - 已 scp docker-compose.yml 和 .env 到当前目录
#
# 用法:
#   bash prod-load-and-run.sh
# ============================================================

set -euo pipefail

VERSION="${VERSION:-v$(date +%Y.%m.%d)}"

echo "================================================"
echo " metric-code 生产部署"
echo "================================================"

# 0. 检查依赖
for cmd in docker; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "❌ $cmd 未安装"
        exit 1
    fi
done

if ! docker compose version >/dev/null 2>&1; then
    echo "❌ docker compose 插件未安装"
    exit 1
fi

# 1. 加载镜像（amd64 版本，先验证 MD5）
for img in metric-backend metric-frontend; do
    TARFILE="${img}-${VERSION}-amd64.tar.gz"
    if [ ! -f "$TARFILE" ]; then
        echo "❌ 未找到 $TARFILE（拒绝静默回退到其他版本，避免旧包意外被加载）"
        echo "   请先在 dev Mac 跑: bash deploy/scripts/prod-build.sh $VERSION"
        echo "   然后 scp $TARFILE(.md5) 到当前目录"
        exit 1
    fi
    # MD5 校验
    if [ -f "${TARFILE}.md5" ]; then
        echo "[1/5] 校验 $TARFILE ..."
        LOCAL_MD5=$(md5sum "$TARFILE" 2>/dev/null | awk '{print $1}' || md5 -q "$TARFILE")
        EXPECTED_MD5=$(cat "${TARFILE}.md5" | awk '{print $NF}')
        if [ "$LOCAL_MD5" = "$EXPECTED_MD5" ]; then
            echo "        ✅ MD5 校验通过"
        else
            echo "        ❌ MD5 校验失败！文件可能损坏或不完整"
            echo "        期望: $EXPECTED_MD5"
            echo "        实际: $LOCAL_MD5"
            exit 1
        fi
    else
        echo "      ⚠️  未找到 ${TARFILE}.md5，跳过 MD5 校验"
    fi
    echo "      加载镜像 $TARFILE ..."
    gunzip -c "$TARFILE" | docker load
    echo "      ✅ $img 已加载"
done

# 1.5 备份前置闸门（硬要求 — 避免发版前忘了备份）
BACKUP_DIR="${BACKUP_DIR:-./deploy}"
LATEST_BACKUP=$(ls -t "$BACKUP_DIR"/db-backup-*.sql.gz 2>/dev/null | head -1 || true)
if [ -z "$LATEST_BACKUP" ]; then
    if [ "${SKIP_BACKUP_CHECK:-0}" = "1" ]; then
        echo
        echo "      ⚠️  SKIP_BACKUP_CHECK=1，跳过备份闸门（你已接受风险）"
    else
        echo
        echo "❌ 未发现 $BACKUP_DIR/db-backup-*.sql.gz，拒绝继续"
        echo "   请先在生产 MySQL 主机执行: bash deploy/scripts/backup-db.sh"
        echo "   然后把备份文件 scp 到: $BACKUP_DIR"
        echo "   或显式跳过（不推荐）: SKIP_BACKUP_CHECK=1 $0 $VERSION"
        exit 1
    fi
else
    BACKUP_AGE_HOURS=$(( ( $(date +%s) - $(stat -c %Y "$LATEST_BACKUP" 2>/dev/null || stat -f %m "$LATEST_BACKUP") ) / 3600 ))
    if [ "$BACKUP_AGE_HOURS" -gt 24 ]; then
        if [ "${SKIP_BACKUP_CHECK:-0}" = "1" ]; then
            echo "      ⚠️  最近备份已超过 $BACKUP_AGE_HOURS 小时，但 SKIP_BACKUP_CHECK=1 显式跳过"
        else
            echo
            echo "❌ 最近备份已超过 $BACKUP_AGE_HOURS 小时 ($LATEST_BACKUP)"
            echo "   建议先重新备份。如确实继续: SKIP_BACKUP_CHECK=1 $0 $VERSION"
            exit 1
        fi
    else
        echo
        echo "      ✅ 最近备份: $LATEST_BACKUP ($BACKUP_AGE_HOURS 小时前)"
    fi
fi

# 2. 检查 .env
if [ ! -f ".env" ]; then
    echo "❌ .env 文件不存在"
    echo "   请先: cp .env.production .env  然后编辑填写实际配置"
    exit 1
fi

echo
echo "[2/5] 检查 .env 配置..."
set -a
source .env
set +a

: "${DB_URL:?❌ .env 中缺少 DB_URL}"
: "${DB_USER:?❌ .env 中缺少 DB_USER}"
: "${DB_PASSWORD:?❌ .env 中缺少 DB_PASSWORD}"
: "${HOST_FILES_PATH:?❌ .env 中缺少 HOST_FILES_PATH}"

# 创建目录
mkdir -p "$HOST_FILES_PATH"
mkdir -p "${HOST_LOGS_PATH:-./logs}/nginx"
echo "      ✅ 配置正常"

# 3. 停止旧容器
echo
echo "[3/5] 停止旧容器..."
VERSION=$VERSION docker compose down 2>/dev/null || true

# 4. 启动新容器
echo
echo "[4/5] 启动新容器..."
VERSION=$VERSION docker compose up -d
echo "      ✅ 容器已启动"

# 5. 健康检查（docker native healthcheck + 应用层双保险）
echo
echo "[5/5] 等待后端 healthcheck 通过 (最多 120 秒) ..."
for i in $(seq 1 60); do
    sleep 2
    STATUS=$(docker inspect --format '{{json .State.Health.Status}}' metric-backend 2>/dev/null || echo "null")
    if [ "$STATUS" = '"healthy"' ]; then
        echo "      ✅ backend container State.Health.Status = healthy (第 ${i}/60 次)"
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo
        echo "❌ backend 未在 120s 内进入 healthy 状态"
        echo "   容器详细状态:"
        docker inspect --format '{{json .State.Health}}' metric-backend
        echo "   最近日志:"
        docker compose logs --tail 200 backend
        exit 1
    fi
done

# 应用层双保险：直接 curl 公开健康检查端点
APP_PORT="${APP_PORT:-18080}"
if ! curl -fsS "http://localhost:${APP_PORT}/api/session/config" >/dev/null 2>&1; then
    echo "❌ /api/session/config 未返回 200（虽然容器 healthy 但应用层不可达）"
    docker compose logs --tail 100 backend
    exit 1
fi
echo "      ✅ 应用层 /api/session/config 200"

# 检查前端
FRONTEND_PORT="${FRONTEND_PORT:-28080}"
if curl -fsS "http://localhost:${FRONTEND_PORT}/metric-code/" >/dev/null 2>&1; then
    echo "      ✅ 前端健康检查通过"
else
    echo "      ⚠️  前端访问异常，请检查: docker compose logs frontend"
fi

echo
echo "================================================"
echo " ✅ 部署完成"
echo "================================================"
echo " 后端: http://localhost:18080/api/session/config"
echo " 前端: http://localhost:${FRONTEND_PORT}/metric-code/"
echo
echo " 查看日志:   docker compose logs -f"
echo " 查看状态:   docker compose ps"
echo " 停止服务:   docker compose down"
echo "================================================"

# 清理悬空旧镜像（避免 /var 盘满）
echo
echo "      清理悬空镜像 ..."
docker image prune -f
