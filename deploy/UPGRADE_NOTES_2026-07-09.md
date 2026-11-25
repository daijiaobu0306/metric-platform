# UPGRADE_NOTES_2026-07-09 — SSO 切到 UserManager 同源直登（修 appuser 点击图标跳 4A）

> ⚠️ **历史记录（2026-07-09 一次性发布说明）**：非现行规范，仅供追溯。现行规范见 [AGENTS.md](../AGENTS.md)。

## 1. 问题

生产 appuser.sn.cmcc 上点击 metric-code 图标后跳到 4A 登录页（`http://192.168.1.14/login/v1/`）而不是 UserManager 自动单点登录。

**根因**：旧版用 Spring Security `oauth2Login()` + `prompt=none` silent SSO，`SSO_AUTH_URI` 直指内网 `http://192.168.1.12:7200`，与 appuser.sn.cmcc **不同域**。浏览器访问 UserManager 没有同域 cookie，UserManager 视为未登录，又叠加它集成的 4A，于是跳 4A。

## 2. 修复方案（仿公司其它系统：AMDB / emotional-console）

**核心思路**：把 UserManager 接到 appuser 同源路径，浏览器始终在 appuser.sn.cmcc 域内，UserManager 的 session cookie 跨应用共享。

**和之前一版方案的关键区别**：本版把 `redirect_uri` 直接用现有的前端 `/sso/callback` 页面（与公司其它系统一致），不再新建后端 `/api/sso/callback`。UserManager 服务管理后台**不需要填"特殊回调地址"**，只要 metric-code 服务注册时的 base URL 对了就行（redirect_uri 是 `${host}/metric-code/sso/callback`，属于 app 自己的 URL）。

### 2.1 nginx 加 `/usermanager/` 反代段

`deploy/nginx/metric-code.conf` 新增：

```nginx
location /usermanager/ {
    proxy_pass http://192.168.1.12:7200/usermanager/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;

    proxy_connect_timeout 30s;
    proxy_read_timeout    30s;

    proxy_buffering off;
    proxy_cache off;
    add_header Cache-Control "no-store, no-cache, must-revalidate" always;
}
```

同时**删除**旧 Spring Security 路径段：`/oauth2/`、`/metric-code/oauth2/`、`/metric-code/login/oauth2/`、`/login/oauth2/`。

### 2.2 后端 drop Spring Security oauth2Login + 新增 /api/sso/exchange-code

**改动**：
- `src/main/java/com/metric/security/SecurityFilterChainConfig.java`：删除 `.oauth2Login(...)` 整段和 `authorizationRequestResolver` Bean；白名单新增 `/api/sso/authorize`、`/api/sso/exchange-code`
- **删除** `OidcSsoUserService.java`、`Oauth2LoginSuccessHandler.java`、`Oauth2LoginFailureHandler.java`
- `src/main/resources/application.yml`：删除 `spring.security.oauth2.client.*` 整段；在 `app.sso` 下新增 `auth-base-url`、`callback-path`（默认 `/metric-code/sso/callback`，即前端页面）、`authorize-path`、`token-path`、`userinfo-path`、`client-id`、`client-secret`、`scope`

**新增/重写**：`src/main/java/com/metric/controller/OidcSsoController.java`
- `GET /api/sso/authorize?target=...` — 生成 state + HttpSession 暂存 target+state + 302 到 `${auth-base-url}/oauth/authorize?client_id=...&redirect_uri=${host}/metric-code/sso/callback&response_type=code&state=...`
- `GET /api/sso/exchange-code?code=...&state=...` — 前端 `/sso/callback` 收到 code 后调此端点（XHR 走），校验 state → POST `/oauth/token`（Basic auth + form body）→ 解析 JWT → upsert 用户 → `SessionService.createSession` → 返回 JSON `{sessionKey, target, username, displayName, isAdmin}`
- 保留 `/api/sso/me`、`/api/sso/front-logout`、`/api/sso/access-link`、`/api/sso/post-login`、`/api/sso/login-url`

### 2.3 前端 `/sso/callback` 改造

`frontend/src/views/sso-callback/index.vue` 改造为同时支持两种登录入口：

| 入口 | URL 形参 | 行为 |
|---|---|---|
| 新 SSO 流程（UserManager 回跳） | `?code=...&state=...` | 调 `/api/sso/exchange-code` 换 sessionKey |
| 旧 access-link 流程（兼容） | `?sessionKey=...&target=...` | 直接走 `sessionApi.init` 激活 session |

### 2.4 其它前端改动

| 文件 | 改动 |
|---|---|
| `frontend/index.html` | inline script 里 `/api/sso/silent-entry` → `/api/sso/authorize` |
| `frontend/src/views/login/index.vue` | onMounted 里 `/api/sso/silent-entry` → `/api/sso/authorize` |

## 3. 环境变量变化（生产 .env）

### 3.1 新增

```bash
SSO_AUTH_BASE_URL=http://appuser.sn.cmcc/usermanager
SSO_CALLBACK_PATH=/metric-code/sso/callback   # 现有前端页面，无需 UserManager 改 admin
```

### 3.2 删除（已废弃，注释保留以便回滚对照）

```bash
# SSO_AUTH_URI
# SSO_TOKEN_URI
# SSO_JWKS_URI
# SSO_USERINFO_URI
# SSO_REDIRECT_URI
```

### 3.3 不变

```bash
SSO_OIDC_CLIENT_ID=metric-code
SSO_OIDC_CLIENT_SECRET=<fill-from-usermanager-ops>
SSO_OIDC_SCOPE=openid profile    # 旧版为空，新版默认 openid profile
SSO_FRONTEND_BASE_URL=
SSO_FRONTEND_CALLBACK_PATH=/metric-code/sso/callback
SSO_FRONTEND_LOGIN_PATH=/metric-code/login
SSO_DEFAULT_TARGET=/metric-code/
```

## 4. UserManager 服务管理后台（**通常无需改动**）

`redirect_uri = http://appuser.sn.cmcc/metric-code/sso/callback` 是 metric-code app 自己的 URL。如果 UserManager 服务注册时已经按 base URL 配置过（很多平台会自动从 base URL 派生 callback），就不需要改。

如果 UserManager 明确要求填"回调地址"字段（独立于服务 URL），填 `http://appuser.sn.cmcc/metric-code/sso/callback` 即可（与 `.env` 的 `SSO_CALLBACK_PATH` 拼出来的 URL 字符级一致）。

> 重要：和之前一版（`/metric-code/api/sso/callback`）相比，**新 callback path 是现有的前端页面**，不是新建的后端端点。这就是仿 AMDB / emotional-console 形式的关键 — UserManager 端看不到"特殊回调"的概念，看到的就是 app 自己的 URL。

## 5. 新流程图

```
浏览器 appuser.sn.cmcc/metric-code/observation
  → inline script: 无 sessionKey + 非公开路径
  → GET /metric-code/api/sso/authorize?target=/observation     [nginx → 后端]
  → 后端: 生成 state + 存 HttpSession
  → 302 → http://appuser.sn.cmcc/usermanager/oauth/authorize   [nginx 同源 /usermanager/ → 192.168.1.12:7200]
      ?client_id=metric-code
      &redirect_uri=http://appuser.sn.cmcc/metric-code/sso/callback     ← 现有前端页面
      &response_type=code
      &state=<random>
      &scope=openid profile
  → UserManager 检测同域 cookie：
      Case A 有 session → 302 → redirect_uri?code=...&state=...
      Case B 无 session → 展示 UserManager 登录页（同域 appuser.sn.cmcc）→ 登录后回 redirect_uri
  → 浏览器命中 /metric-code/sso/callback?code=...&state=...     [nginx → 前端 SPA 路由]
  → /sso/callback 页 onMounted:
      检测到 code+state → axios GET /api/sso/exchange-code?code=...&state=...
  → 后端 /api/sso/exchange-code:
      1. 校验 state 与 HttpSession 中保存的一致
      2. POST http://appuser.sn.cmcc/usermanager/oauth/token
         Authorization: Basic base64(client_id:client_secret)
         body: grant_type=authorization_code & code & redirect_uri
      3. 拿 access_token → 解析 JWT claims（失败回退 GET /user/me）
      4. SessionService.createSession(user, "sso_oidc")
      5. 返回 JSON {sessionKey, target, username, displayName, isAdmin}
  → /sso/callback 页写 sessionStorage → router.replace(target) → 进应用
```

## 6. 验证步骤

### 6.1 Dev

```bash
docker compose -f docker-compose.dev.yml up -d --build backend nginx
```

浏览器开无痕 → `http://localhost:28080/metric-code/observation`：
1. inline script 跳 `/metric-code/api/sso/authorize?target=/observation`
2. 后端 302 → `http://localhost:7200/usermanager/oauth/authorize?redirect_uri=http%3A%2F%2Flocalhost%3A28080%2Fmetric-code%2Fsso%2Fcallback&...`
3. mock IdP 登录 → 回 `/sso/callback?code=...&state=...`
4. 前端 /sso/callback 调 /api/sso/exchange-code
5. 进入应用

### 6.2 生产

```bash
# 1. 改生产 .env：删 5 个旧 SSO 端点变量，加 SSO_AUTH_BASE_URL + SSO_CALLBACK_PATH
# 2. 升级
VERSION=v2026.07.11 bash prod-load-and-run.sh
# 3. 浏览器开无痕 → appuser.sn.cmcc → 点 metric-code 图标
#    首次：UserManager 登录页（同域 appuser.sn.cmcc）→ 登录 → 进 metric-code
#    再次：直接进 metric-code
```

## 7. 回滚

```bash
# 1. 改 .env：注释掉 SSO_AUTH_BASE_URL 和 SSO_CALLBACK_PATH
#    恢复 5 个旧 SSO 端点变量
# 2. nginx 删除 /usermanager/ 段，恢复 /oauth2/ 等 4 段
# 3. 还原 application.yml 的 spring.security.oauth2.client 段（从 git 拉旧版）
# 4. 还原 OidcSsoUserService / Oauth2Login* 三个类（从 git 拉旧版）
# 5. 重打旧版镜像 + 重启
```

或者更简单：用 git revert 整个 commit 一次性还原所有改动 + 重新打 tarball。

## 8. 引用

- plan: `/Users/developer/.claude/plans/appuser-sn-cmcc-metric-code-4a-usermana-bubbly-mist.md`
- 升级手册: `deploy/UPGRADE_v2026.07.09_GUIDE.md`（已同步更新 §2 §3.4 §Q2/Q3/Q6）
- [[dev-baoIand-userinfo-bug]] — JWT 解析失败的兜底逻辑沿用
- [[restart-backend-directly]] — SSO_* 变量是启动期绑定，改完必须重启
- [[project_env_separation_rule]] — 生产 IP（192.168.1.12:7200）只在 deploy/ 下用
- [[dev-orbstack-host-networking]] — dev mock IdP 用 LAN IP（生产路径不走这里）