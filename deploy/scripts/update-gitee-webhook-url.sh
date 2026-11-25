#!/usr/bin/env bash
# ============================================================
# 修复 Gitee webhook URL：28082 → 28081（走 nginx 反代）
#
# 背景（2026-09-08）：
#   webhook 在 Gitee 配置时 URL 写成了 28082，但阿里云安全组
#   只开了 28081/18081，没开 28082。Gitee 推到 28082 被 SG 拦截。
#   nginx 在 28081 提供了 /gitee-webhook 反代到 host:28082，所以
#   最简单是改 webhook URL 走 28081（不开 SG 28082，少一个攻击面）。
#
# 用法：
#   export GITEE_TOKEN='your-token'
#   bash update-gitee-webhook-url.sh
# ============================================================

set -euo pipefail

REPO="mixinan/metric_code"
WEBHOOK_ID="2123907"  # 当前 webhook ID（Gitee API 返回的）
NEW_URL="http://8.148.195.209:28081/gitee-webhook"

# token 必传
if [ -z "${GITEE_TOKEN:-}" ]; then
  echo "ERROR: GITEE_TOKEN env required"
  exit 1
fi

# PATCH /api/v5/repos/{owner}/{repo}/hooks/{id}
curl -sS -X PATCH \
  -H "Authorization: token $GITEE_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"url\": \"$NEW_URL\"}" \
  "https://gitee.com/api/v5/repos/$REPO/hooks/$WEBHOOK_ID" \
  | python3 -m json.tool 2>&1 | head -20

echo ""
echo "=== 验证修改 ==="
curl -sS -H "Authorization: token $GITEE_TOKEN" \
  "https://gitee.com/api/v5/repos/$REPO/hooks" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('hooks:', [h['url']+' (id='+str(h['id'])+')' for h in d])"
