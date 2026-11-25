#!/bin/bash
# metric-code 采集执行器（独立 Worker）—— 安装为 systemd 服务
#
# 用法：
#   bash bin/install-worker.sh            # 生成 token（如缺）+ 写 systemd 单元 + 启动
#   bash bin/install-worker.sh --dry-run  # 只打印将要做的事，不改任何东西
#
# 为什么需要它：采集任务到期后由这个独立进程领取并执行脚本。
# 没有它，页面上的任务会一直显示「等待执行器」。
#
# 它会做四件事：
#   1. 校验 python3 版本与 worker 代码是否就位
#   2. 确保 app.env 里有 COLLECTION_WORKER_TOKEN（<32 字符视为无效，自动重新生成）
#   3. 生成 systemd 单元并 enable --now
#   4. 探活：能拿到 401/正常响应说明后端那条链路是通的
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"
WORKER_DIR="$APP_HOME/worker"
UNIT_NAME="metric-collection-worker.service"
UNIT_PATH="/etc/systemd/system/$UNIT_NAME"

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg" >&2; exit 1 ;;
  esac
done

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"
sep() { echo; echo "==== $* ===="; }

sep "1. 配置与代码检查"
[ -f "$WORKER_DIR/worker.py" ] || { echo "缺 $WORKER_DIR/worker.py（包不完整，或未打包 worker）" >&2; exit 1; }
load_app_env "$ENV_FILE" || exit 1
require_vars APP_PORT
require_numeric APP_PORT

FILE_ROOT="${UPLOAD_BASE_PATH:-${SCRIPT_STORAGE_PATH:-$APP_HOME/files}}"
mkdir -p "$FILE_ROOT" 2>/dev/null
[ -w "$FILE_ROOT" ] || { echo "文件根目录不可写: $FILE_ROOT（检查 UPLOAD_BASE_PATH）" >&2; exit 1; }
echo "文件根目录: $FILE_ROOT"
echo "后端地址:   http://127.0.0.1:$APP_PORT"

PY="$(command -v python3 || true)"
[ -n "$PY" ] || { echo "缺 python3（Worker 需要 3.9+）" >&2; exit 1; }
PY_VER="$("$PY" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null)"
case "$PY_VER" in
  3.9|3.1[0-9]|4.*) echo "python3: $PY ($PY_VER)" ;;
  *) echo "python3 版本过低: $PY_VER（需要 3.9+）" >&2; exit 1 ;;
esac

sep "2. Worker 令牌"
TOKEN="${COLLECTION_WORKER_TOKEN:-}"
if [ "${#TOKEN}" -lt 32 ]; then
  NEW_TOKEN="$(head -c 48 /dev/urandom 2>/dev/null | base64 | tr -d '/+=' | cut -c1-40)"
  echo "app.env 里的 COLLECTION_WORKER_TOKEN 缺失或过短（<32 字符，后端会一律 401）"
  echo "已生成新令牌: $NEW_TOKEN"
  if [ "$DRY_RUN" = "0" ]; then
    if grep -q '^COLLECTION_WORKER_TOKEN=' "$ENV_FILE"; then
      sed -i "s#^COLLECTION_WORKER_TOKEN=.*#COLLECTION_WORKER_TOKEN=$NEW_TOKEN#" "$ENV_FILE"
    else
      printf '\n# 采集执行器身份令牌（后端与 Worker 必须一致，≥32 字符）\nCOLLECTION_WORKER_TOKEN=%s\n' "$NEW_TOKEN" >> "$ENV_FILE"
    fi
    TOKEN="$NEW_TOKEN"
    echo "已写入 $ENV_FILE"
    NEED_BACKEND_RESTART=1
  else
    TOKEN="$NEW_TOKEN"
    NEED_BACKEND_RESTART=1
  fi
else
  echo "已配置令牌（${#TOKEN} 字符），复用"
  NEED_BACKEND_RESTART=0
fi

sep "3. systemd 单元"
UNIT_CONTENT="[Unit]
Description=metric-code collection worker
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$(id -un)
WorkingDirectory=$APP_HOME
Environment=COLLECTION_WORKER_TOKEN=$TOKEN
Environment=COLLECTION_WORKER_API=http://127.0.0.1:$APP_PORT
Environment=COLLECTION_WORKER_ID=$(hostname)-worker
Environment=PYTHONUNBUFFERED=1
$(
  for v in COLLECTION_EXTERNAL_FILES_ROOT COLLECTION_EXTERNAL_SOURCE_CODES; do
    eval "val=\"\${$v:-}\""
    [ -n "$val" ] && echo "Environment=$v=$val"
  done
)
ExecStart=$PY $WORKER_DIR/worker.py --files-root $FILE_ROOT
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target"

if [ "$DRY_RUN" = "1" ]; then
  echo "---- 将写入 $UNIT_PATH ----"
  echo "$UNIT_CONTENT"
  echo "-------------------------"
  echo "--dry-run：未做任何改动"
  exit 0
fi

SUDO=""
if [ "$(id -u)" != "0" ]; then
  if sudo -n true 2>/dev/null; then SUDO="sudo"; else
    echo "当前用户无 sudo 免密权限，已把单元写到 $APP_HOME/config/$UNIT_NAME" >&2
    printf '%s\n' "$UNIT_CONTENT" > "$APP_HOME/config/$UNIT_NAME"
    echo "请手工执行：" >&2
    echo "  sudo cp $APP_HOME/config/$UNIT_NAME $UNIT_PATH" >&2
    echo "  sudo systemctl daemon-reload && sudo systemctl enable --now $UNIT_NAME" >&2
    exit 1
  fi
fi

printf '%s\n' "$UNIT_CONTENT" > "$APP_HOME/config/$UNIT_NAME" 2>/dev/null || true
$SUDO cp -f "$APP_HOME/config/$UNIT_NAME" "$UNIT_PATH"
$SUDO systemctl daemon-reload
$SUDO systemctl enable "$UNIT_NAME" >/dev/null 2>&1
$SUDO systemctl restart "$UNIT_NAME"

sep "4. 启动结果"
sleep 2
$SUDO systemctl status "$UNIT_NAME" --no-pager 2>&1 | head -12

if [ "$NEED_BACKEND_RESTART" = "1" ]; then
  echo
  echo "注意：令牌是新生成的，后端必须重启才能识别：" >&2
  echo "  bash bin/standalone-stop-backend.sh && bash bin/standalone-start-backend.sh" >&2
fi

echo
echo "看 Worker 日志: journalctl -u $UNIT_NAME -f"
echo "人工验证: 在页面点一次采集，状态应从「等待执行器」变为「执行中」。"
