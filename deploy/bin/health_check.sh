#!/bin/bash
cd $(dirname $0)/..
source config/env.conf

echo "===== 健康检查 ====="

# 检查后端进程
if [ -f backend/logs/app.pid ]; then
  PID=$(cat backend/logs/app.pid)
  if ps -p $PID > /dev/null 2>&1; then
    echo "[OK] 后端进程运行中, PID: $PID"
  else
    echo "[FAIL] 后端进程未运行, PID文件存在但进程已停止"
  fi
else
  echo "[FAIL] 后端进程未运行, 无PID文件"
fi

# 检查后端端口
if netstat -tuln 2>/dev/null | grep -q ":$APP_PORT " || ss -tuln 2>/dev/null | grep -q ":$APP_PORT "; then
  echo "[OK] 后端端口 $APP_PORT 监听中"
else
  echo "[FAIL] 后端端口 $APP_PORT 未监听"
fi

# 检查 Nginx 前端端口
if netstat -tuln 2>/dev/null | grep -q ":$FRONTEND_PORT " || ss -tuln 2>/dev/null | grep -q ":$FRONTEND_PORT "; then
  echo "[OK] Nginx 端口 $FRONTEND_PORT 监听中"
else
  echo "[FAIL] Nginx 端口 $FRONTEND_PORT 未监听"
fi

# 检查 Nginx 服务状态
if systemctl is-active nginx >/dev/null 2>&1; then
  echo "[OK] Nginx 服务运行中"
else
  echo "[WARN] Nginx 服务状态未知或未运行"
fi

# 检查数据库连接
if mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASSWORD -e "SELECT 1" >/dev/null 2>&1; then
  echo "[OK] 数据库连接正常 ($DB_HOST:$DB_PORT/$DB_NAME)"
else
  echo "[FAIL] 数据库连接失败"
fi

# 通过 Nginx 验证反向代理
if curl -sf "http://127.0.0.1:$FRONTEND_PORT/metric-code/api/system/list" -o /dev/null; then
  echo "[OK] 反向代理 /metric-code/api/ → :$APP_PORT 正常"
else
  echo "[WARN] 反向代理验证失败（可能后端未就绪或接口路径变化）"
fi

echo "===== 检查完成 ====="
