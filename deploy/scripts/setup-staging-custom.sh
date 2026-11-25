#!/usr/bin/env bash
# ============================================================
# 云 staging 服务器定制版初始化（针对 8.148.195.209 / Ubuntu 24.04 已装环境）
#
# 与通用 setup-staging.sh 的差异：
#   - 跳过 Docker 安装（已装 v29.8.0）
#   - 跳过 MySQL 安装（已在 3306 跑着，含 infra_usermanager 库）
#   - 跳过 MySQL root 密码设置（root 用 auth_socket，不动）
#   - 新建 metric_staging@'%' 用户走 TCP（backend 容器用 host.docker.internal:3306）
#   - 不动 usermanager 容器 / 7200 端口 / 已有库
#   - 加 4G swap（0 swap 跑 maven build 会 OOM）
#
# 用法（在云服务器上以 root 执行）：
#   export STAGING_DB_PASS='<strong-password>'
#   export GITEE_WEBHOOK_SECRET='<strong-secret>'
#   export GITEE_REPO_URL='git@gitee.com:mixinan/metric_code.git'
#   bash setup-staging-custom.sh
# ============================================================

set -euo pipefail

REPO_DIR="/opt/metric-staging"
DB_NAME="system_operations_metrics_staging"

# 所有 secrets 必须从环境变量传入（runtime 注入，不在脚本里硬编码）
# 见 doc/staging-deploy-guide.md §2.2 / §6
DB_USER="${STAGING_DB_USER:-um_app}"
DB_PASS="${STAGING_DB_PASS:-}"
WEBHOOK_SECRET="${GITEE_WEBHOOK_SECRET:-}"
GITEE_TOKEN="${GITEE_TOKEN:-}"
GITEE_REPO_URL="${GITEE_REPO_URL:-https://oauth2:\${GITEE_TOKEN}@gitee.com/mixinan/metric_code.git}"
PUBLIC_HOST="8.148.195.209"

if [[ -z "$DB_PASS" ]]; then
    echo "ERROR: 必须 export STAGING_DB_PASS=<strong-password>"
    exit 1
fi
if [[ -z "$WEBHOOK_SECRET" ]]; then
    echo "ERROR: 必须 export GITEE_WEBHOOK_SECRET=<strong-secret>"
    exit 1
fi
if [[ -z "$GITEE_TOKEN" ]]; then
    echo "ERROR: 必须 export GITEE_TOKEN=<gitee-personal-token>"
    exit 1
fi
echo "Secrets 注入 OK：DB_USER=${DB_USER} GiteeToken=${GITEE_TOKEN:0:8}... WebhookSecret=${WEBHOOK_SECRET:0:8}..."

echo "=== [1/9] 检查已装环境（不动） ==="
docker --version
docker compose version
mysql --version
echo "✓ docker/mysql 已装，跳过安装步骤"

echo "=== [2/9] 安装 Node.js 20（webhook-deploy.mjs 需要） ==="
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | cut -d. -f1 | tr -d 'v')" -lt 18 ]]; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y -qq nodejs
fi
node -v
npm -v

echo "=== [3/9] 加 4G swap（防 maven build OOM） ==="
if [[ ! -f /swapfile ]]; then
    fallocate -l 4G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo "✓ 4G swap 已加"
else
    echo "✓ /swapfile 已存在"
fi

echo "=== [4/9] 创建 MySQL DB + 授权 um_app（不动 root 密码，不动已有库） ==="
mysql -uroot <<EOF
CREATE DATABASE IF NOT EXISTS ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- um_app 已存在（user 提供），仅追加新 DB 权限
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'%';
FLUSH PRIVILEGES;
EOF
echo "✓ ${DB_NAME} 已建，授权给 ${DB_USER}@'%'"

# 验证 TCP 连接
mysql -h 127.0.0.1 -P 3306 -u"${DB_USER}" -p"${DB_PASS}" -e "SELECT 'OK' AS status" "${DB_NAME}" 2>&1 | tail -3

echo "=== [5/9] 创建工作目录 ==="
mkdir -p ${REPO_DIR}/{files,logs/nginx,appuser-agent-runtime,frontend/dist,backups}
echo "✓ ${REPO_DIR}/ 子目录就绪"

echo "=== [6/9] clone Gitee 仓库 ==="
if [[ ! -d ${REPO_DIR}/.git ]]; then
    apt-get install -y -qq git  # 万一没装
    git clone "${GITEE_REPO_URL}" ${REPO_DIR}
    cd ${REPO_DIR}
    git config --global --add safe.directory ${REPO_DIR}
    echo "✓ 已 clone ${GITEE_REPO_URL}"
else
    echo "✓ ${REPO_DIR} 已是 git 仓库，跳过 clone"
    cd ${REPO_DIR} && git pull origin main 2>&1 | tail -3
fi

echo "=== [7/9] 生成 .env.staging ==="
if [[ ! -f ${REPO_DIR}/.env.staging ]]; then
    cp ${REPO_DIR}/deploy/config/.env.staging.example ${REPO_DIR}/.env.staging
    JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
    sed -i "s|<replace-with-strong-password>|${DB_PASS}|"           ${REPO_DIR}/.env.staging
    sed -i "s|<replace-or-blank>|${WEBHOOK_SECRET}|"                  ${REPO_DIR}/.env.staging
    sed -i "s|<openssl rand -base64 48 生成的字符串>|${JWT_SECRET}|"  ${REPO_DIR}/.env.staging
    sed -i "s|<replace-with-strong-secret>|${WEBHOOK_SECRET}|"        ${REPO_DIR}/.env.staging
    chmod 600 ${REPO_DIR}/.env.staging
    echo "✓ .env.staging 已生成（权限 600）"
else
    echo "✓ .env.staging 已存在，跳过"
fi

echo "=== [8/9] 安装 webhook-deploy systemd 服务（不启用，等启动 docker 后再 enable） ==="
cp ${REPO_DIR}/deploy/systemd/webhook-deploy.service /etc/systemd/system/
systemctl daemon-reload
echo "✓ webhook-deploy.service 已安装"
echo "  启动命令（第一次手动启动 staging 后再执行）："
echo "    systemctl enable --now webhook-deploy"

echo "=== [9/9] 防火墙放行 18081 / 28081 / 28082 ==="
if command -v ufw >/dev/null 2>&1; then
    ufw allow 18081/tcp
    ufw allow 28081/tcp
    ufw allow 28082/tcp
elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port=18081/tcp
    firewall-cmd --permanent --add-port=28081/tcp
    firewall-cmd --permanent --add-port=28082/tcp
    firewall-cmd --reload
fi
# Ubuntu 24.04 默认 iptables-nft，没装 ufw 时用 iptables 兜底
if ! command -v ufw >/dev/null && ! command -v firewall-cmd >/dev/null; then
    iptables -I INPUT -p tcp --dport 18081 -j ACCEPT
    iptables -I INPUT -p tcp --dport 28081 -j ACCEPT
    iptables -I INPUT -p tcp --dport 28082 -j ACCEPT
    # 持久化（不同发行版工具不一样，简化处理）
    apt-get install -y -qq iptables-persistent 2>/dev/null && netfilter-persistent save
fi

echo ""
echo "=========================================="
echo "  ✓ staging 环境初始化完成"
echo "=========================================="
echo ""
echo "关键状态："
echo "  - 工作目录：${REPO_DIR}"
echo "  - 数据库：${DB_NAME}（${DB_USER}@'%'）"
echo "  - 镜像源：docker.1ms.run（已写入 nginx 服务）"
echo "  - 端口：18081（backend）28081（nginx）28082（webhook）"
echo "  - swap：4G 已加"
echo "  - usermanager 容器：未触碰，仍在 7200"
echo "  - 系统 MySQL：未触碰，仍有 infra_usermanager"
echo ""
echo "下一步（手动执行）："
echo "  1. cd ${REPO_DIR}"
echo "  2. 构建后端镜像（首次）：docker build -t metric-backend:staging-latest -f deploy/docker/Dockerfile ."
echo "     或：本机构建 + scp + docker load（参见 doc/staging-deploy-guide.md）"
echo "  3. 构建前端：cd frontend && npm ci --no-audit --no-fund && npm run build && cd .."
echo "  4. 启动：docker compose --project-directory . -f deploy/docker-compose.staging.yml --env-file .env.staging up -d"
echo "  5. 验证：curl -fsS http://${PUBLIC_HOST}:28081/metric-code/"
echo "  6. 启动 webhook：systemctl enable --now webhook-deploy"
echo "  7. Gitee 仓库 → Webhooks → URL: http://${PUBLIC_HOST}:28082/gitee-webhook"
echo ""
