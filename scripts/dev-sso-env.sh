#!/usr/bin/env bash
# ============================================================
# 本机 dev 后端 SSO 环境变量加载脚本（宿主机 mvn spring-boot:run 模式）
#
# 用法：
#   # 方式 A：source 进当前 shell，再 mvn spring-boot:run
#   source scripts/dev-sso-env.sh
#   mvn -f backend/pom.xml spring-boot:run
#
#   # 方式 B：一次性 inline（不污染当前 shell，但子进程能拿到）
#   source <(scripts/dev-sso-env.sh --print)
#   mvn -f backend/pom.xml spring-boot:run
#
#   # 方式 C：debug，看一下当前值
#   scripts/dev-sso-env.sh --print
#
# 为什么 token_uri / jwks_uri / userinfo_uri 用 localhost:7200 而非 LAN IP：
#   - 本脚本专给"宿主机 mvn 进程"用，不是给容器后端用
#   - 宿主机进程通过 OrbStack 暴露的 localhost:7200 直达 usermanager 容器（OrbStack
#     把容器 published port 绑在 host 的 127.0.0.1 上，宿主机进程 localhost 可达）
#   - 写 LAN IP（192.168.1.200）反而错——除非 usermanager 真的跑在 .200 这台机器上，
#     否则宿主机→.200:7200 connect timeout（实测 8s），Spring Security 报
#     "502 Bad Gateway: [no body]" / invalid_token_response
#   - 容器 compose 模式下应该走 LAN IP（绕开 host.docker.internal），见
#     deploy/docker-compose.dev.yml:42-58
#
# 换网络/换机器/换 SSO 平台时改这里；如果改了 client_id/secret，
# 务必同步改 deploy/docker-compose.dev.yml（容器 compose 读那套）。
# ============================================================
set -euo pipefail

# --- dev UserManager SSO 配置 ---
export SSO_OIDC_CLIENT_ID="${SSO_OIDC_CLIENT_ID:-metric-code}"
export SSO_OIDC_CLIENT_SECRET="${SSO_OIDC_CLIENT_SECRET:-metric-code}"
export SSO_OIDC_SCOPE="${SSO_OIDC_SCOPE:-}"

# 浏览器侧 authorize URL —— 宿主机浏览器访问 usermanager 的授权端点
export SSO_AUTH_URI="${SSO_AUTH_URI:-http://localhost:7200/usermanager/oauth/authorize}"

# 后端→usermanager 的 token / jwks / userinfo 端点 —— 宿主机 mvn 进程通过
# localhost:7200 走 OrbStack 转发到 usermanager 容器。**不要**写 LAN IP，
# 除非你确认 usermanager 跑在同网段另一台机器上。
export SSO_TOKEN_URI="${SSO_TOKEN_URI:-http://localhost:7200/usermanager/oauth/token}"
export SSO_JWKS_URI="${SSO_JWKS_URI:-http://localhost:7200/usermanager/.well-known/jwks.json}"
export SSO_USERINFO_URI="${SSO_USERINFO_URI:-http://localhost:7200/usermanager/user/me}"

# --- 行为分发 ---
if [[ "${1:-}" == "--print" ]]; then
  cat <<EOF
SSO_OIDC_CLIENT_ID='${SSO_OIDC_CLIENT_ID}'
SSO_OIDC_CLIENT_SECRET='${SSO_OIDC_CLIENT_SECRET}'
SSO_OIDC_SCOPE='${SSO_OIDC_SCOPE}'
SSO_AUTH_URI='${SSO_AUTH_URI}'
SSO_TOKEN_URI='${SSO_TOKEN_URI}'
SSO_JWKS_URI='${SSO_JWKS_URI}'
SSO_USERINFO_URI='${SSO_USERINFO_URI}'
EOF
elif [[ "${1:-}" == "--check" ]]; then
  echo "=== 当前 shell SSO env（宿主机 mvn 模式；容器 compose 见 deploy/docker-compose.dev.yml）==="
  env | grep -E '^SSO_' | sort
else
  # 默认 source 模式：什么都不打印，避免污染 terminal
  : # 所有 export 在文件头部已完成
fi
