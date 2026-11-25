#!/usr/bin/env bash
# ============================================================
# 云 staging 轮询部署（替代 Gitee Webhook）
#
# 背景（2026-09-08）：
#   Gitee Webhook UI「测试」按钮无反应、push 不触发（怀疑 Gitee 状态机卡死），
#   改用轮询：每 1 分钟 fetch origin/main，HEAD 不一致就触发部署。
#
# 用法（systemd timer）：
#   /etc/systemd/system/staging-poll.service    ← oneshot
#   /etc/systemd/system/staging-poll.timer     ← OnUnitInactiveSec=60s
#
# 手动触发：
#   bash /opt/metric-staging/deploy/scripts/staging-poll-deploy.sh
# ============================================================

set -euo pipefail

REPO_DIR="${REPO_DIR:-/opt/metric-staging}"
BRANCH="${BRANCH:-main}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/docker-compose.staging.yml}"
ENV_FILE="${ENV_FILE:-.env.staging}"
LOG_FILE="${LOG_FILE:-/opt/metric-staging/logs/poll-deploy.log}"

mkdir -p "$(dirname "$LOG_FILE")"

ts() { date '+%Y-%m-%dT%H:%M:%S%z'; }
log() { echo "[$(ts)] $*" | tee -a "$LOG_FILE"; }

cd "$REPO_DIR"

# 1) fetch 最新
git fetch origin "$BRANCH" --quiet 2>>"$LOG_FILE" || { log "ERROR: git fetch failed"; exit 1; }

LOCAL=$(git rev-parse "HEAD")
REMOTE=$(git rev-parse "origin/$BRANCH")

# 记录"上次成功部署的 SHA"（如果从未记录过则视为需要部署）
LAST_DEPLOYED_FILE="$REPO_DIR/.last-deployed-sha"
LAST_DEPLOYED=$(cat "$LAST_DEPLOYED_FILE" 2>/dev/null || echo "")

log "local=$LOCAL remote=$REMOTE last_deployed=$LAST_DEPLOYED"

# 跳过条件：远端==本地==上次部署（即没有未部署的代码）
if [ "$LOCAL" = "$REMOTE" ] && [ "$LOCAL" = "$LAST_DEPLOYED" ]; then
    log "up to date, skip"
    exit 0
fi

if [ "$LOCAL" != "$REMOTE" ]; then
    log "new commit detected, deploying..."
else
    log "local != last_deployed (unfinished deploy), resuming..."
fi

# 2) pull（--ff-only 拒绝合并；如有本地修改（如临时调试 nginx），自动 stash + pop）
PREV_SHA="$LOCAL"          # pull 之前的 HEAD，用于判断哪些目录有改动
STASH_NAME="poll-deploy-$(date +%s)"
if ! git diff --quiet 2>/dev/null || ! git diff --cached --quiet 2>/dev/null; then
    log "local changes detected, stashing as $STASH_NAME"
    git stash push -m "$STASH_NAME" >>"$LOG_FILE" 2>&1 || true
fi
git pull --ff-only --quiet origin "$BRANCH" 2>>"$LOG_FILE" || { log "ERROR: git pull failed"; exit 1; }
# 还原之前 stash 的本地修改（不影响部署本身）
if git stash list | grep -q "$STASH_NAME"; then
    log "restoring stashed local changes"
    git stash pop >>"$LOG_FILE" 2>&1 || log "WARN: stash pop failed, manual merge needed"
fi

# 3) 判断本次改动涉及哪些部分
#
#    背景（2026-09-10）：此前 compose 的 backend 只有 image: 没有 build:，
#    `up -d --build` 对后端是 no-op —— 后端镜像停在 09-09，Java 改动和
#    Flyway migration 都不会生效（当时靠手动上库跑 SQL 兜底）。
#    现在改为：后端相关路径有改动就用deploy/docker/Dockerfile 重新 build 镜像，
#    容器重启时 Flyway 会自动把新 migration 跑掉。
#
#    同时把前端 build 也改成按需 —— 抵消掉 maven 构建增加的耗时。
NEW_SHA=$(git rev-parse HEAD)

# 镜像 tag 要和 compose 实际用的一致：compose 从 ENV_FILE 读 VERSION，
# 但脚本自身的 shell 环境读不到，这里显式取一次，避免 build 出来的 tag 和 compose 拉的 tag 对不上。
IMAGE_TAG=$(grep -E '^VERSION=' "$REPO_DIR/$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '"'"'"' ')
IMAGE_TAG="${IMAGE_TAG:-staging-latest}"
BACKEND_IMAGE="metric-backend:${IMAGE_TAG}"
log "backend image tag = $BACKEND_IMAGE"

# 首次部署 / 断点续传（HEAD 没变但上次没部署完）时，无法用 diff 判断，一律全量构建
FORCE_ALL=0
if [ -z "$LAST_DEPLOYED" ] || [ "$PREV_SHA" = "$NEW_SHA" ]; then
    FORCE_ALL=1
    log "cannot diff (first deploy or resume), building everything"
fi

# 某组路径在 PREV_SHA..NEW_SHA 之间是否有改动
changed() {
    [ "$FORCE_ALL" = "1" ] && return 0
    git diff --name-only "$PREV_SHA" "$NEW_SHA" -- "$@" 2>/dev/null | grep -q .
}

BACKEND_CHANGED=0
FRONTEND_CHANGED=0
changed backend deploy/docker/Dockerfile .dockerignore deploy/maven-settings.xml deploy/appuser-agent \
    deploy/database deploy/scripts/database_support.py deploy/scripts/backup-database.py \
    deploy/scripts/prepare-database.py deploy/scripts/container-entrypoint.py \
    deploy/db_export/bootstrap doc/数据库版本记录.md && BACKEND_CHANGED=1
changed frontend                                                       && FRONTEND_CHANGED=1

# 镜像不存在时（新机器/被清理过）必须 build，否则 compose up 会失败
if ! docker image inspect "$BACKEND_IMAGE" >/dev/null 2>&1; then
    log "backend image missing, forcing build"
    BACKEND_CHANGED=1
fi

log "backend_changed=$BACKEND_CHANGED frontend_changed=$FRONTEND_CHANGED"

# 4) 前端 build（产出 dist/，由 nginx 容器 bind mount）
if [ "$FRONTEND_CHANGED" = "1" ] && [ -f "$REPO_DIR/frontend/package.json" ]; then
    log "building frontend..."
    ( cd "$REPO_DIR/frontend" \
      && npm ci --no-audit --no-fund --silent >>"$LOG_FILE" 2>&1 \
      && npm run build >>"$LOG_FILE" 2>&1 ) \
      || { log "ERROR: frontend build failed"; exit 1; }
    # 重新加载 nginx 让 bind mount 的新 dist 生效（容器本身用 ro，但文件 mtime 变化浏览器可能要硬刷）
    docker compose --project-directory "$REPO_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" restart nginx >>"$LOG_FILE" 2>&1 \
      || log "WARN: nginx restart failed"
else
    log "frontend unchanged, skip build"
fi

# 5) 后端镜像 build（多阶段 Dockerfile：maven 编译 → JRE 运行）
#    耗时约 3-6 分钟（依赖有 maven layer cache）。构建到临时 tag，成功后才顶替正式 tag，
#    避免构建失败把可用镜像冲掉。
if [ "$BACKEND_CHANGED" = "1" ]; then
    log "building backend image (this takes a few minutes)..."
    BUILD_TAG="metric-backend:staging-building"
    if docker build -t "$BUILD_TAG" -f "$REPO_DIR/deploy/docker/Dockerfile" "$REPO_DIR" >>"$LOG_FILE" 2>&1; then
        docker tag "$BUILD_TAG" "$BACKEND_IMAGE"
        docker rmi "$BUILD_TAG" >/dev/null 2>&1 || true
        log "backend image built"
    else
        log "ERROR: backend image build failed, keeping previous image; see $LOG_FILE"
        exit 1
    fi
else
    log "backend unchanged, skip image build"
fi

# 6) docker compose up
#    容器入口先检查迁移；存在缺失时自动完整备份、补齐历史及新增迁移，成功才启动 Spring。
docker compose --project-directory "$REPO_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d 2>>"$LOG_FILE" \
    || { docker logs --tail 160 metric-backend-staging >>"$LOG_FILE" 2>&1 || true;
         log "ERROR: docker compose up failed; backend startup/upgrade logs appended above"; exit 1; }

# 7) 等健康（给完整备份和迁移预留时间；超过时保留失败状态，不标记成功）
log "waiting for backend healthy..."
for i in $(seq 1 240); do
    sleep 5
    S=$(docker inspect --format='{{.State.Health.Status}}' metric-backend-staging 2>/dev/null || echo "none")
    log "  check $i: $S"
    [ "$S" = "healthy" ] && break
done

if [ "$S" != "healthy" ]; then
    docker logs --tail 160 metric-backend-staging >>"$LOG_FILE" 2>&1 || true
    log "WARN: backend not healthy after 1200s, check docker logs metric-backend-staging"
    exit 1
fi

log "deploy done: $REMOTE"

# 记录本次成功部署的 SHA（供下次比对）
echo "$REMOTE" > "$LAST_DEPLOYED_FILE"
