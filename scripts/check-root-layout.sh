#!/usr/bin/env bash
# 根目录仅容纳明确约定的模块和工具入口，其他文件请放入对应子目录。
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
result=0
shopt -s dotglob nullglob
for entry in "$PROJECT_ROOT"/*; do
  name="${entry##*/}"
  case "$name" in
    backend|frontend|deploy|doc|scripts|files|README.md|AGENTS.md|CLAUDE.md|.git|.gitignore|.gitattributes|.dockerignore|.github|.claude|.codex|.agents|.idea|.vscode|.superpowers|.DS_Store) ;;
    # 已有服务器的环境文件和持久化目录兼容；新文件应按目录规范存放。
    .env|.env.staging|.last-deployed-sha|logs|logs-dev|files-dev|appuser-agent-runtime|webhooks|backups) ;;
    *) echo "根目录出现未归类条目: ${name}（请参阅 doc/目录规范.md）" >&2; result=1 ;;
  esac
done
exit "$result"
