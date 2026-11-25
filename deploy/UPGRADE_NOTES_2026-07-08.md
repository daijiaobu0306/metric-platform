# 升级操作说明 — v2026.07.08-amd64 镜像生产部署

> ⚠️ **历史记录（2026-07-08 一次性发布说明）**：非现行规范，仅供追溯。现行规范见 [AGENTS.md](../AGENTS.md)。
>
> 适用范围：把 `metric-backend-v2026.07.08-amd64.tar.gz`（MD5 `4f6d5796b6e04a988c6816b12784d08f`）+ `metric-frontend-v2026.07.08-amd64.tar.gz`（MD5 `e9d47525cf32e7d5c17b114ab6dfd75d`）推到生产环境。
> **本次只改构建/部署配置，不改代码、不改 DB schema。** 通用流程继续参考 [`UPGRADE_NOTES_2026-07-03.md`](UPGRADE_NOTES_2026-07-03.md)，本文档只列本次相对 7月3日的差异点和踩坑提示。

---

## 0. 本次相对 7月3日版本的差异

**代码与数据库完全一致**（git log 到 7月3日之间无新提交，本镜像只是把 7月3日的同一份 jar 用新 Dockerfile 重新打包）。所以这次升级：

- ✅ **不需要再跑数据库迁移**
- ✅ **Flyway 不会变更 schema history**
- ⚠️ **必须重启 backend 容器**（不再是 `restart`，见 §2.3）
- ⚠️ **必须改 `.env`**（不能仅改 `.env.production`）

| 项 | 7月3日镜像 | 7月8日镜像 |
|---|---|---|
| 后端 jar | `metric-code.jar` (build #07-03) | `metric-code.jar` (build #07-03，未变更) |
| 前端 dist | `vite build` (07-03) | `vite build` (07-08，refresh 了一下) |
| 后端 Dockerfile apt 装包 | python3 / tzdata / curl / gcc / g++ / libffi / libssl | **+ vim / unzip / less** |
| 前端 Dockerfile apk 装包 | tzdata | **+ vim** |
| `docker-compose.prod.yml` | 没有 SSO_\* 注入 | **+ 15 条 SSO_\* / SSO_SYNC_\* `environment:` 条目** |
| `.env.production` | 没有 SSO 段 | **+ SSO_* 模板段（注释形式，运维填空）** |
| 数据库 schema | 已 baseline V33 + 13 增量 | 不变 |

---

## 0.1 SSO 重定向 URL — usermanager 控制台填什么

> §0.5 之前是企业 SSO 接入最容易踩坑的一项。直接给答案，再解释原因：

### 答案

**usermanager 控制台新建 OAuth2 应用时，"重定向 URL / callback" 一栏填：**

```
https://<生产域名>/login/oauth2/code/sso
```

**关键点：不带 `/metric-code/` 前缀。**

⚠️ **不要写成** `https://<生产域名>/metric-code/login/oauth2/code/sso` — 那样会报 `redirect_uri_mismatch` 错。

### 为什么

- `application.yml:72` 配置：`redirect-uri: "{baseUrl}/login/oauth2/code/sso"`
- `{baseUrl}` 由 Spring Security 在请求进来时算 = scheme + host + port，**没有 path 部分**（除非后端设了 `server.servlet.context-path`）。
- 本项目 `server.servlet.context-path` 留空，**所以 `{baseUrl}` 只到 host:port 为止** — 回调地址自然不带 `/metric-code/`。

### 配套 nginx 反代

Spring 的回调路径必须能从外网到 backend。所以 nginx 必须把这两段写到 **root**（**不写到 `/metric-code/` 下**）：

```nginx
# Spring OAuth2 触发点（前端拿 /api/sso/login-url 后跳到这里）
location ~ ^/oauth2/authorization/[^/]+$ {
    proxy_pass http://backend:18080;
    # 同现有 /metric-code/api/ 的 headers / timeouts
}

# usermanager 回调（必须与 usermanager 控制台注册的回调地址一致）
location ~ ^/login/oauth2/code/[^/]+$ {
    proxy_pass http://backend:18080;
    # 同上
}
```

> 同 7月3日 notes §3 — 这两条 nginx 反代一直没改（被 SSO 整体挂着）。7月8日这次也没改 nginx 配置。如要完整跑通 SSO 端到端，**去生产 nginx 配置里加上这两段**。

### usermanager 侧配置完整项

去 usermanager 应用管理 → 新建一项 OAuth2.0 Web 应用：

| 字段 | 值 |
|---|---|
| 应用名称 | metric-code（或按运维约定） |
| 应用类型 | OAuth2.0 Web 应用 |
| **回调地址 (redirect_uri)** | `https://<生产域名>/login/oauth2/code/sso` |
| Scope（授权范围） | `profile`（或按 §2.2 中 `SSO_OIDC_SCOPE` 的实际设定） |

> ⚠️ 应用类型选 OAuth2.0 还是 OIDC 视平台而定。dev 阶段由 `commit f62b550` 把对接从 OIDC 切到 plain OAuth2（dev usermanager 对 OIDC scope 处理有 bug）。**生产对接时确认 usermanager 是否支持 OAuth2 userInfo；如不支持需要回退到 OIDC 并相应调 scope。**

---

## 1. 操作步骤（4 步）

### 步骤 1：scp 4 个镜像文件 + 2 个配置到生产 app 主机（dev Mac 上）

```bash
PROD_APP=<prod-app-host>

# 4 个镜像文件（每对 tar.gz + md5）
scp /Users/developer/metric_code/deploy/metric-backend-v2026.07.08-amd64.tar.gz \
    /Users/developer/metric_code/deploy/metric-backend-v2026.07.08-amd64.tar.gz.md5 \
    /Users/developer/metric_code/deploy/metric-frontend-v2026.07.08-amd64.tar.gz \
    /Users/developer/metric_code/deploy/metric-frontend-v2026.07.08-amd64.tar.gz.md5 \
    ${PROD_APP}:/home/deploy/metric-code/deploy/

# 这两个是有变动的配置文件（不是新文件，是覆盖更新）
scp /Users/developer/metric_code/docker-compose.prod.yml \
    ${PROD_APP}:/home/deploy/metric-code/docker-compose.yml
scp /Users/developer/metric_code/.env.production \
    ${PROD_APP}:/home/deploy/metric-code/.env.production
```

### 步骤 2：app 主机上 — 加载新镜像 + 编辑 .env

```bash
ssh ${PROD_APP}
cd /home/deploy/metric-code

# 2.1 （可选）保留现有 .env，跟新模板 diff 看改了啥
diff .env .env.production | head -30
# 期望看到 SSO_* / SSO_SYNC_* 一段被加了进来

# 2.2 跟新 .env.production，**再 vi 编辑填真实值**
cp .env.production .env
vi .env
# 必改项见 §3

# 2.3 （如果生产 .env 之前没有 SSO_* 段，可以不复制，直接在现有 .env 末尾追加 §3 那段）
```

### 步骤 3：app 主机上 — 加载镜像 + 跑部署脚本

```bash
cd /home/deploy/metric-code
bash deploy/scripts/prod-load-and-run.sh v2026.07.08
# （脚本参数不写 -amd64 后缀，会自动拼成 tar.gz 文件名）
```

脚本会按顺序：MD5 校验 → 检查 24h 内备份存在 → 加载镜像 → 停旧容器 → **`docker compose up -d` 启新容器** → 等 healthcheck 通过。

### 步骤 4：验证

```bash
# 4.1 容器状态
docker ps --filter name=metric- --format 'table {{.Names}}\t{{.Status}}'

# 4.2 后端健康
curl -fsS http://localhost:18080/api/session/config

# 4.3 vim 真的进了镜像
docker exec --entrypoint sh metric-backend -c "command -v vim && vim --version | head -1"
docker exec metric-frontend sh -c "command -v vim && vim --version | head -1"

# 4.4 SSO_* env 真的进了容器（关键，前面 90% 答案在这里）
docker exec metric-backend env | grep ^SSO_ | sort
```

---

## 2. ⚠️ 这次最容易踩的两个坑

### 坑 1：`docker compose restart` 不会重新读 `.env`（最常见）

```bash
# 错：
docker compose restart backend        # ❌ 容器没换，env 是旧的

# 对：
docker compose up -d --force-recreate backend    # ✅ 重建容器，吃新 .env
```

`prod-load-and-run.sh` 内部用的是 `docker compose up -d`（已经做对了），但如果后台操作时手抖跑了 `restart`，前端的"使用统一认证登录"还会跳到 `127.0.0.1:7200`，让人误以为 SSO_* 没生效。

### 坑 2：vim 在镜像 ≠ vim 真的生效

vim 是装在镜像里的，每次 `docker compose up -d` 拉的是新镜像层，**自动有 vim**。但如果用 `docker compose restart`（上面坑 1），容器没换，**用的还是老镜像的层**（镜像在 docker daemon 的 image cache 里没动过）。

所以坑 1 + 坑 2 会一前一后串起来：restart 看不到新 vim → 看不到新 SSO env。

---

## 3. `.env` 中本次必须新增 / 填值的项

### 3.1 文件里 6 个本次已加进去的字段（建议保留，不要从 .env 删掉）

`.env.production` 里已经写好了，但**值大多还是 `<fill-from-...>` 占位**，必须改成真实值：

| 变量 | 说明 | 示例 |
|---|---|---|
| `SSO_OIDC_CLIENT_ID` | usermanager 控制台分发的应用 ID | `metric-code` |
| `SSO_OIDC_CLIENT_SECRET` | usermanager 控制台分发的密钥 | `<从 usermanager 运维拿>` |
| `SSO_OIDC_SCOPE` | OAuth2 scope，默认 `openid,profile` | `profile`（按 usermanager 实际支持范围） |
| `SSO_AUTH_URI` | 授权端点 | `http://appuser.sn.cmcc/usermanager/oauth/authorize` |
| `SSO_TOKEN_URI` | token 端点 | `http://appuser.sn.cmcc/usermanager/oauth/token` |
| `SSO_JWKS_URI` | 公钥端点 | `http://appuser.sn.cmcc/usermanager/.well-known/jwks.json` |
| `SSO_USERINFO_URI` | userinfo 端点 | `http://appuser.sn.cmcc/usermanager/usermanager/user/me` |
| `SSO_FRONTEND_BASE_URL` | OAuth2 完成后前端跳转 base URL | 留空走 nginx 同源 |
| `SSO_FRONTEND_CALLBACK_PATH` | OAuth2 完成后前端目标路径 | `/metric-code/sso/callback` |
| `SSO_FRONTEND_LOGIN_PATH` | 失败跳转登录页路径 | `/metric-code/login` |
| `SSO_DEFAULT_TARGET` | SSO 成功默认目标 | `/metric-code/` |

⚠️ **4 个 URI 是按 `application.yml` 里 mock IdP 默认值风格拼的**，你们 usermanager 实际路径未必一致。如果路径不对，浏览器点登录会被 4xx 拒绝；按 7月3日 notes 的 §3 探针脚本扫一下真实路径再回来改。

### 3.2 4 个可选的同步变量（V42 默认全 false/空）

```ini
SSO_SYNC_ENABLED=false                # 启用定时从 IdP 拉用户/角色
SSO_SYNC_DELAY_MS=3600000             # 同步间隔，默认 1 小时
SSO_SYNC_API_BASE_URL=                # IdP 查询 API 的 base URL
SSO_SYNC_API_TOKEN=                   # IdP 服务账号 token
```

按当前线上进度，先**全部保留 false/空即可**，等 §SSO 真正跑通再决定是否开同步。

### 3.3 写 .env 的安全注意事项

```ini
# 永远建议：值里有空格 / # / $ 时整行加双引号
SSO_OIDC_CLIENT_SECRET="AbC/dEf+gH=ijk"

# 不要写这种：用 # 注释掉一行不会生效
# SSO_AUTH_URI=http://appuser.sn.cmcc/...     # ← 整行被注释，compose 读不到

# 不要写这种：前后空格会让变量名变成 " SSO_AUTH_URI"（带空格），Spring 读不到
SSO_AUTH_URI= http://appuser.sn.cmcc/...    # ← 等号后空格，shell 解析可能错

# 验证：编辑完后
grep ^SSO_ .env | nl
# 期望：8-15 行非注释、非空、首尾干净
```

---

## 4. SSO_* 真的进容器了吗 — 一行诊断

```bash
docker exec metric-backend env | grep ^SSO_ | sort
```

期望看到 11 行 `SSO_OIDC_*` / `SSO_AUTH_*` / `SSO_TOKEN_*` 等，**且值为 appuser.sn.cmcc 开头、没有 127.0.0.1**。

如果还是 `127.0.0.1:7200` 看一眼：

```bash
# 1) .env 里到底写了啥
grep ^SSO_ .env

# 2) docker compose 自己展开的（看是不是 compose 读到了）
docker compose config 2>/dev/null | grep -iE "sso"

# 3) 容器是何时启动的（可能是 restart 而不是 up，重启的容器 env 还是老的）
docker inspect --format '{{.State.StartedAt}}' metric-backend
```

---

## 5. 部署后冒烟测试

```bash
# 1) 容器 + 健康（每行单独一项，可贴出来给我看）
docker ps --filter name=metric- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
curl -fsS http://localhost:18080/api/session/config

# 2) vim 进得去（vim 没装就会 command not found，**这才是真"vim 已安装"**的信号）
docker exec --entrypoint sh metric-backend -c "command -v vim && vim --version | head -1"
docker exec metric-frontend sh -c "command -v vim && vim --version | head -1"

# 3) SSO env 进了容器
docker exec metric-backend env | grep ^SSO_ | sort

# 4) 浏览器验证 SSO 跳转（最直观）
#    → 进 https://<生产域名>/metric-code/login
#    → 点"使用统一认证登录"
#    → 期望跳转到 appuser.sn.cmcc/usermanager/...（不再是 127.0.0.1:7200）

# 5) 配置管理页面验证
#    → admin / <新密码> 登录
#    → 配置管理 → 展开"统一认证客户端参数"
#    → 8 个 disabled 字段应该显示新值（client_id、authorization-uri 等）
#    → 这步是 SSO env 已被 Spring 绑定到 OAuth2 client 的可视化证据
```

---

## 6. （仍然要做，但不在本次镜像范围）nginx 反代

本次没动 nginx，但 7月3日 notes §3 的两条 `/oauth2/` 和 `/login/oauth2/` 反代 **仍未配置**（7月3日升级时被 SSO 整个挂着没改）。如果这次您想**完整跑通 SSO 端到端**，顺手把 nginx 改了：

```nginx
location ~ ^/oauth2/authorization/[^/]+$ {
    proxy_pass http://backend:18080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 60s;
    proxy_send_timeout 300s;
    proxy_read_timeout 300s;
}

location ~ ^/login/oauth2/code/[^/]+$ {
    proxy_pass http://backend:18080;
    # 同上 headers / timeouts
}
```

只跑通本地登录的话**不用动 nginx**，跳过本节。

---

## 7. 回滚预案（万一 SSO 全断）

```bash
# 切回 7月3日镜像（您生产上当前跑的版本）
cd /home/deploy/metric-code
docker compose down
docker load < deploy/metric-backend-v2026.07.03-amd64.tar.gz
docker load < deploy/metric-frontend-v2026.07.03-amd64.tar.gz
VERSION=v2026.07.03 docker compose up -d

# .env 可以保留（不带走 SSO_* 也行；7月3日镜像里 SSO_* 配置是 placeholder）
# DB 不用回滚（本次升级 0 schema 变更）
```

最坏情况：回滚后 admin/admin123 仍然能本地登录（7月3日与本次代码、schema 一致）。

---

## 8. 涉及文件清单（本次升级）

```
metric_code/
├── Dockerfile                                         # 已改：apt 加 vim unzip less
├── Dockerfile.frontend                                # 已改：apk 加 vim
├── docker-compose.prod.yml                            # 已改：environment: 加 15 条 SSO_*
├── .env.production                                    # 已改：加 SSO_* 模板段（注释示例 + 占位值）
└── deploy/
    └── UPGRADE_NOTES_2026-07-08.md                    # ← 本文件
```

不在本次范围但仍需补做的（沿用 7月3日 notes）：
- nginx `/oauth2/` 和 `/login/oauth2/` 反代（见 §6）
- usermanager 真实 4 端点 URL 探测（见 7月3日 notes §Q1 探针脚本）
- admin 默认密码 `admin123` 的首次登录后改密（沿用 7月3日 notes §5）
