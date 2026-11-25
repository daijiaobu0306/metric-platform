#!/usr/bin/env bash
# 在任意工作目录调用，始终使用仓库根目录解析挂载路径和 Compose 项目名。
# 用法: bash scripts/compose.sh dev up -d --build
#       bash scripts/compose.sh staging --env-file .env.staging config --quiet
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENVIRONMENT="${1:-dev}"
if [ "$#" -gt 0 ]; then shift; fi
case "$ENVIRONMENT" in
  dev|prod|staging) COMPOSE_FILE="$PROJECT_ROOT/deploy/docker-compose.$ENVIRONMENT.yml" ;;
  default) COMPOSE_FILE="$PROJECT_ROOT/deploy/docker-compose.yml" ;;
  *) echo "环境必须为 dev、prod、staging 或 default" >&2; exit 2 ;;
esac
cd "$PROJECT_ROOT"
exec docker compose --project-directory "$PROJECT_ROOT" -f "$COMPOSE_FILE" "$@"
