#!/bin/bash
# metric-code 非容器化部署 —— 健康检查脚本（生产机）
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"
load_app_env "$ENV_FILE" || exit 1

require_vars APP_PORT DB_URL
resolve_nginx_port
# 前端目录与 nginx 配置保持同一口径：默认包内 frontend/，可用 FRONTEND_ROOT 覆盖
resolve_frontend_root

fail=0
ok()   { echo "[OK]   $*"; }
bad()  { echo "[FAIL] $*"; fail=1; }
warn() { echo "[WARN] $*"; }

echo "===== metric-code 健康检查 ====="

# 1. 后端进程
PID_FILE="$APP_HOME/logs/app.pid"
if [ -f "$PID_FILE" ] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1; then
  ok "后端进程运行中, PID: $(cat "$PID_FILE")"
else
  bad "后端进程未运行"
fi

# 2. 后端端口
if port_busy "$APP_PORT"; then
  ok "后端端口 $APP_PORT 监听中"
else
  bad "后端端口 $APP_PORT 未监听"
fi

# 3. 数据库连通（只读 SELECT）
parse_db_url "$DB_URL"
locate_mysql_client
if [ -n "$MYSQL_CLIENT" ]; then
  if MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CLIENT" -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
       "$DB_NAME" -e "SELECT 1" >/dev/null 2>&1; then
    ok "数据库连接正常 ($DB_HOST:$DB_PORT/$DB_NAME)"
  else
    bad "数据库连接失败 ($DB_HOST:$DB_PORT/$DB_NAME)"
  fi
else
  warn "本机无 mysql 客户端，跳过数据库检查"
fi

# 4. Flyway 迁移状态（不要只看 history，先看真实表结构）
if [ -n "$MYSQL_CLIENT" ]; then
  TABLES=$(MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CLIENT" -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
    "$DB_NAME" --batch --skip-column-names \
    -e "SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE();" 2>/dev/null)
  echo "       数据库表数量: ${TABLES:-未知}"
fi

# 5. 后端 API
if curl -sf "http://127.0.0.1:$APP_PORT/api/metric/statistics" >/dev/null 2>&1; then
  ok "后端 API 响应正常"
else
  bad "后端 API 无响应: http://127.0.0.1:$APP_PORT/api/metric/statistics"
fi

# 6. 前端静态资源
if [ -f "$FRONTEND_ROOT/index.html" ]; then
  ok "前端静态目录存在: $FRONTEND_ROOT"
else
  warn "前端静态目录不可用: $FRONTEND_ROOT（缺 index.html）"
fi

# 7. 统一入口 nginx（复用系统 nginx 或独立实例，都认 NGINX_PORT）
if curl -sf -o /dev/null "http://127.0.0.1:$NGINX_PORT/metric-code/"; then
  ok "nginx 前端入口可访问 ($NGINX_PORT/metric-code/)"
else
  warn "nginx 前端入口无响应 ($NGINX_PORT/metric-code/)"
fi
NGX_CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$NGINX_PORT/metric-code/api/metric/statistics" 2>/dev/null)
case "$NGX_CODE" in
  000|502|504) warn "nginx 反代无响应 ($NGINX_PORT, HTTP $NGX_CODE)" ;;
  *)           ok "nginx 反代正常 ($NGINX_PORT, HTTP $NGX_CODE)" ;;
esac

echo "===== 检查完成 ====="
exit $fail
