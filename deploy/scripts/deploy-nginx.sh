#!/bin/bash
# metric-code 统一入口 nginx —— 启停/状态
#
# 用法：
#   bash bin/deploy-nginx.sh status          # 端口监听 + 首页 + API 三项探活
#   bash bin/deploy-nginx.sh reload          # 复用模式：重载系统 nginx（配置变更后）
#   bash bin/deploy-nginx.sh start|stop      # 独立模式：启停独立实例
#   bash bin/deploy-nginx.sh uninstall       # 复用模式：摘掉 conf.d/metric-code.conf
#
# 自动区分两种部署形态：
#   - 复用模式：系统 nginx + /etc/nginx/conf.d/metric-code.conf
#   - 独立模式：$APP_HOME/lib/nginx（install-nginx.sh --compile 装出来的，可用 NGINX_PREFIX 覆盖）
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"
PREFIX=""      # 由 resolve_nginx_prefix 决定，与 install-nginx.sh 保持同一口径
NGINX_TARGET="/etc/nginx/conf.d/metric-code.conf"

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"
load_app_env "$ENV_FILE" || exit 1
resolve_nginx_port
resolve_nginx_prefix
PREFIX="$NGINX_PREFIX"
NGINX="$PREFIX/sbin/nginx"
CONF="$PREFIX/conf/nginx.conf"

SYS_NGINX=""
if command -v nginx >/dev/null 2>&1; then
  SYS_NGINX="$(command -v nginx)"
elif [ -x /usr/sbin/nginx ]; then
  SYS_NGINX="/usr/sbin/nginx"
fi

if [ -x "$NGINX" ] && [ ! -f "$NGINX_TARGET" ]; then
  MODE="standalone"
elif [ -f "$NGINX_TARGET" ]; then
  MODE="shared"
else
  MODE="none"
fi

sudo_run() {
  if [ "$(id -u)" = "0" ]; then "$@"; else sudo "$@"; fi
}

probe() {
  local base="http://127.0.0.1:${NGINX_PORT}"
  if ! command -v curl >/dev/null 2>&1; then
    echo "[WARN] 无 curl，跳过探活"
    return 0
  fi
  if curl -sf -o /dev/null "$base/metric-code/"; then
    echo "[OK] 前端首页可访问 ($NGINX_PORT/metric-code/)"
  else
    echo "[FAIL] 前端首页无响应 ($NGINX_PORT/metric-code/)"
  fi
  # API 探活允许 401/404：能返回 HTTP 状态就说明反代链路通了
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "$base/metric-code/api/metric/category/list" 2>/dev/null)
  case "$code" in
    000) echo "[FAIL] 后端反代无响应（nginx 有动静但 18090 没起来？）" ;;
    502|504) echo "[FAIL] 反代到后端失败: HTTP $code（后端 $APP_PORT 是否在跑）" ;;
    *) echo "[OK] 后端反代链路通 ($code)" ;;
  esac
}

case "${1:-status}" in
  status)
    echo "部署形态: $MODE (端口 $NGINX_PORT)"
    case "$MODE" in
      standalone)
        if [ -f "$PREFIX/run/nginx.pid" ] && kill -0 "$(cat "$PREFIX/run/nginx.pid")" 2>/dev/null; then
          echo "[OK] 独立实例运行中, PID: $(cat "$PREFIX/run/nginx.pid")"
        else
          echo "[FAIL] 独立实例未运行"
        fi
        ;;
      shared)
        if pgrep -x nginx >/dev/null 2>&1; then
          echo "[OK] 系统 nginx 运行中, 已加载 $NGINX_TARGET"
        else
          echo "[FAIL] 系统 nginx 未运行"
        fi
        ;;
      *)
        echo "[FAIL] 未安装：先执行 bash bin/install-nginx.sh"
        exit 1
        ;;
    esac
    probe
    ;;
  reload)
    [ "$MODE" = "shared" ] || { echo "当前不是复用模式，用 start/restart" >&2; exit 1; }
    sudo_run "$SYS_NGINX" -t && sudo_run "$SYS_NGINX" -s reload && echo "已 reload 系统 nginx"
    ;;
  start)
    if [ "$MODE" = "shared" ]; then
      if pgrep -x nginx >/dev/null 2>&1; then
        sudo_run "$SYS_NGINX" -s reload && echo "系统 nginx 已在运行，已 reload 使配置生效"
      else
        sudo_run "$SYS_NGINX" && echo "系统 nginx 已启动"
      fi
      exit 0
    fi
    [ -x "$NGINX" ] || { echo "未找到 nginx: $NGINX，请先执行 bash bin/install-nginx.sh" >&2; exit 1; }
    if [ -f "$PREFIX/run/nginx.pid" ] && kill -0 "$(cat "$PREFIX/run/nginx.pid")" 2>/dev/null; then
      echo "nginx 已在运行, PID: $(cat "$PREFIX/run/nginx.pid")"
      exit 0
    fi
    "$NGINX" -c "$CONF" -p "$PREFIX"
    sleep 1
    if [ -f "$PREFIX/run/nginx.pid" ] && kill -0 "$(cat "$PREFIX/run/nginx.pid")" 2>/dev/null; then
      echo "nginx 已启动, PID: $(cat "$PREFIX/run/nginx.pid")"
      echo "入口: http://<本机IP>:${NGINX_PORT}/metric-code/"
    else
      echo "nginx 启动失败，请查看 $PREFIX/logs/error.log" >&2
      tail -20 "$PREFIX/logs/error.log" >&2
      exit 1
    fi
    ;;
  stop)
    if [ "$MODE" = "shared" ]; then
      echo "复用模式下不停止系统 nginx（会影响机器上其他站点）。" >&2
      echo "只想下线本应用：bash bin/deploy-nginx.sh uninstall" >&2
      exit 1
    fi
    if [ -f "$PREFIX/run/nginx.pid" ]; then
      "$NGINX" -c "$CONF" -p "$PREFIX" -s quit 2>/dev/null \
        || kill "$(cat "$PREFIX/run/nginx.pid")" 2>/dev/null
      sleep 1
      echo "nginx 已停止"
    else
      echo "nginx 未在运行"
    fi
    ;;
  uninstall)
    [ "$MODE" = "shared" ] || { echo "当前不是复用模式，无需 uninstall" >&2; exit 1; }
    BAK="$NGINX_TARGET.removed.$(date +%Y%m%d%H%M%S)"
    sudo_run mv -f "$NGINX_TARGET" "$BAK"
    sudo_run "$SYS_NGINX" -t && sudo_run "$SYS_NGINX" -s reload
    echo "已下线本应用入口，原配置留存: $BAK"
    ;;
  *)
    echo "用法: bash bin/deploy-nginx.sh status|start|stop|reload|uninstall" >&2
    exit 1
    ;;
esac
