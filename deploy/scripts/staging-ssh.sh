#!/usr/bin/env bash
# ============================================================
# SSH 到 staging 服务器的封装脚本（密码走 ~/.staging_pw，不入 git）
#
# 用法：
#   bash deploy/scripts/staging-ssh.sh '<command>'
#
# 密码文件 ~/.staging_pw 需先创建（chmod 600）：
#   echo -n 'your-password' > ~/.staging_pw && chmod 600 ~/.staging_pw
# ============================================================

set -euo pipefail

PW_FILE="$HOME/.staging_pw"
HOST="root@8.148.195.209"

if [ ! -f "$PW_FILE" ]; then
  echo "ERROR: $PW_FILE 不存在，请先创建并 chmod 600" >&2
  exit 1
fi

if [ $# -eq 0 ]; then
  # 无参数 → 交互式登录
  sshpass -f "$PW_FILE" ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "$HOST"
else
  # 有参数 → 执行命令
  sshpass -f "$PW_FILE" ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "$HOST" "$@"
fi
