#!/bin/bash
# metric-code 非容器化部署 —— 后端停止脚本（生产机）
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$APP_HOME/logs/app.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "未找到 PID 文件，未在运行"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if ! ps -p "$PID" >/dev/null 2>&1; then
  echo "进程 $PID 已不存在，清理 PID 文件"
  rm -f "$PID_FILE"
  exit 0
fi

kill "$PID"
for i in $(seq 1 30); do
  ps -p "$PID" >/dev/null 2>&1 || break
  sleep 1
done
if ps -p "$PID" >/dev/null 2>&1; then
  echo "优雅停止超时，强制结束 $PID"
  kill -9 "$PID"
fi
rm -f "$PID_FILE"
echo "已停止"
