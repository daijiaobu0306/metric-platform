#!/usr/bin/env bash
# ============================================================
# 云 staging 服务器首次初始化脚本（在云服务器上以 root 执行）
#
# 用法（SSH 到云服务器后）：
#   1. 上传 deploy/scripts/setup-staging.sh 到 /tmp/
#   2. bash /tmp/setup-staging.sh
#
# 该脚本会做：
#   - 安装 docker + docker compose v2
#   - 安装 Node.js 20（webhook-deploy.mjs 需要）
#   - 安装 MySQL 8 + 建库建用户
#   - 创建 /opt/metric-staging 工作目录
#   - clone Gitee 仓库（需要 GITEE_REPO_URL 环境变量）
#   - 安装 systemd 服务（webhook-deploy.service）
#   - 创建 .env.staging（用户需手动编辑）
#   - 添加 4G swap（4C8G 跑 docker build 不够内存）
#
# 重要：本脚本需要用 root 跑
# ============================================================

set -euo pipefail

REPO_DIR="/opt/metric-staging"
GITEE_REPO_URL="${GITEE_REPO_URL:-https://gitee.com/<your-org>/metric_code.git}"
DB_NAME="${DB_NAME:-system_operations_metrics_staging}"
DB_USER="${DB_USER:-metric_staging}"
DB_PASS="${DB_PASS:-}"

if [[ -z "$DB_PASS" ]]; then
    echo "ERROR: 必须设置 DB_PASS 环境变量（staging 数据库密码）"
    exit 1
fi

echo "=== 1. 系统包更新 + 必要依赖 ==="
if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq curl wget git ca-certificates gnupg lsb-release
elif command -v yum >/dev/null 2>&1; then
    yum install -y -q curl wget git
fi

echo "=== 2. Docker + compose v2 ==="
if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | bash -s -- --mirror Aliyun
    systemctl enable --now docker
fi
# docker compose v2 已在 docker 20.10+ 内置（`docker compose` 子命令）
docker compose version >/dev/null

echo "=== 3. Node.js 20 ==="
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | cut -d. -f1 | tr -d 'v')" -lt 18 ]]; then
    if command -v apt-get >/dev/null 2>&1; then
        curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
        apt-get install -y -qq nodejs
    elif command -v yum >/dev/null 2>&1; then
        curl -fsSL https://rpm.nodesource.com/setup_20.x | bash -
        yum install -y -q nodejs
    fi
fi
node -v

echo "=== 4. MySQL 8 ==="
if ! command -v mysql >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
        apt-get install -y -qq mysql-server
    elif command -v yum >/dev/null 2>&1; then
        yum install -y -q mysql-server
    fi
    systemctl enable --now mysql
    # Ubuntu 默认无密码，Debian/CentOS 设置临时 root 密码
    mysql --user=root <<EOF
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '${DB_PASS}';
FLUSH PRIVILEGES;
EOF
fi
mysql --user=root --password="${DB_PASS}" <<EOF
CREATE DATABASE IF NOT EXISTS ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF

echo "=== 5. 创建工作目录 ==="
mkdir -p ${REPO_DIR}/{files,logs/nginx,appuser-agent-runtime,frontend/dist,webhooks}

echo "=== 6. clone Gitee 仓库 ==="
if [[ ! -d ${REPO_DIR}/.git ]]; then
    git clone "${GITEE_REPO_URL}" ${REPO_DIR}
    cd ${REPO_DIR}
    git config --global --add safe.directory ${REPO_DIR}
fi

echo "=== 7. 创建 .env.staging（用户需手动编辑密码/密钥） ==="
if [[ ! -f ${REPO_DIR}/.env.staging ]]; then
    cp ${REPO_DIR}/deploy/config/.env.staging.example ${REPO_DIR}/.env.staging
    # 自动生成 JWT secret
    JWT_SECRET=$(openssl rand -base64 48)
    sed -i "s|<openssl rand -base64 48 生成的字符串>|${JWT_SECRET}|" ${REPO_DIR}/.env.staging
    sed -i "s|<replace-with-strong-password>|${DB_PASS}|" ${REPO_DIR}/.env.staging
    echo "WARN: 已生成 .env.staging，请人工补全 SSO 配置 + Gitee Webhook Secret"
fi

echo "=== 8. 安装 webhook-deploy systemd 服务 ==="
cp ${REPO_DIR}/deploy/systemd/webhook-deploy.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable webhook-deploy
# 先不启动，等用户填完 .env.staging 再启动

echo "=== 9. 加 4G swap（4C8G 跑 docker build 不够内存） ==="
if [[ ! -f /swapfile ]]; then
    fallocate -l 4G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "=== 10. 配置防火墙（如果用 firewalld/ufw） ==="
if command -v ufw >/dev/null 2>&1; then
    ufw allow 22/tcp
    ufw allow 18081/tcp   # backend
    ufw allow 28081/tcp   # nginx
    ufw allow 28082/tcp   # webhook
    ufw --force enable
elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port=22/tcp
    firewall-cmd --permanent --add-port=18081/tcp
    firewall-cmd --permanent --add-port=28081/tcp
    firewall-cmd --permanent --add-port=28082/tcp
    firewall-cmd --reload
fi

echo ""
echo "=========================================="
echo "  ✓ staging 环境初始化完成"
echo "=========================================="
echo ""
echo "下一步："
echo "  1. 编辑 ${REPO_DIR}/.env.staging，补全 SSO 配置 + GITEE_WEBHOOK_SECRET"
echo "  2. cd ${REPO_DIR} && docker compose --project-directory . -f deploy/docker-compose.staging.yml --env-file .env.staging up -d --build"
echo "  3. 验证：curl -fsS http://localhost:28081/metric-code/"
echo "  4. 启动 webhook：systemctl enable --now webhook-deploy"
echo "  5. 在 Gitee 仓库 → Webhooks 添加 URL: http://<host>:28082/gitee-webhook"
echo ""
echo "故障排查："
echo "  - 部署日志：tail -f ${REPO_DIR}/logs/webhook.log"
echo "  - 容器状态：docker ps -a"
echo "  - 后端日志：docker logs -f metric-backend-staging"
echo "  - nginx 日志：tail -f ${REPO_DIR}/logs/nginx/metric-code-staging-error.log"
