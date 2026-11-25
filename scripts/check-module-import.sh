#!/usr/bin/env bash
# 默认检查相对 HEAD 新增的依赖；--staged 检查暂存内容，--all 做全量审计。
# 需要 Python 3。可从任意仓库子目录调用，也可复制本入口到 .git/hooks/pre-commit。
set -euo pipefail
PROJECT_ROOT="$(git rev-parse --show-toplevel)"
exec "${PYTHON:-python3}" "$PROJECT_ROOT/scripts/check_module_import.py" "$@"
