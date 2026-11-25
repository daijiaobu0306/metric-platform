#!/usr/bin/env bash
# ==============================================================
# 兼容旧调用：实际逻辑在 upgrade.sh，这里只透传 VERSION=v2026.07.12
# 推荐改用: VERSION=v2026.07.12 bash deploy/scripts/upgrade.sh
#           或:   bash deploy/scripts/upgrade.sh v2026.07.12
# ==============================================================
exec bash "$(dirname "$0")/upgrade.sh" "v2026.07.12" "$@"
