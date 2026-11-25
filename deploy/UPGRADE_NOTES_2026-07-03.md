# 升级操作说明 — v2026.07.03-amd64 镜像生产部署

> ⚠️ **历史记录（2026-07-03 一次性发布说明）**：非现行规范，仅供追溯。
> 现行数据库变更与部署规范见 [AGENTS.md](../AGENTS.md)。
>
> 适用范围：把 `metric-backend-v2026.07.03-amd64.tar.gz`（MD5 `c14ab72807e0018046ad29875b23626c`）+ `metric-frontend-v2026.07.03-amd64.tar.gz`（MD5 `4a70765b3acc5b56d8ceed93b0b24d7f`）推到生产环境。
> 配套通用流程见 [`PRODUCTION_DEPLOY_GUIDE.md`](PRODUCTION_DEPLOY_GUIDE.md)，本文档**只列本次相对老生产的差异点和踩坑提示**。

---

## 0. 与上一次发版相比本次的变更点（先扫一眼，避免漏掉）

| commit | 影响 | 配套动作 |
|---|---|---|
| `f62b550 feat(sso): switch from OIDC to plain OAuth2 + fix user_info_uri path` | Spring Security OAuth2 client 由 OIDC 切到 plain OAuth2，**scope 默认 `openid,profile`** | 生产 `.env` 里 `SSO_OIDC_SCOPE` 按 usermanager 实际支持改 |
| `d3070ba fix(sso): unwrap response correctly after switching to request instance` | /api/sso/login-url 改用配置 axios | 无（前端侧已合入） |
| `0dbb037 fix(sso): proxy /oauth2 and /login/oauth2 in dev reverse proxies` | dev nginx 已加 OAuth2 路径反代 | **prod nginx 也要同步加**（见 §3） |
| `d2f96b5 fix(healthcheck): point all probes at /api/session/config` | 容器 healthcheck 端点改成 `/api/session/config` | 无，验证用 |
| `78eb67d fix(deploy): revert VERSION format to vYYYY.MM.DD` | tarball 命名回归 `vYYYY.MM.DD` | prod-load-and-run.sh 参数传 `v2026.07.03` |
| `74c5ddb infra(ci): pin production image builds to linux/amd64` | 镜像架构固定 amd64 | 已合入 prod-build.sh 断言，不需要额外操作 |

---

## 1. 操作步骤（4 步，每步独立一台机器跑）

### 步骤 1：备份生产 DB（在生产 MySQL 主机上）

```bash
ssh <prod-db-host>
bash deploy/scripts/backup-db.sh -h 127.0.0.1 -P 3306 -u <produser> -p<prodpwd>
ls -la deploy/db-backup-*.sql.gz | tail -1
gunzip -t deploy/db-backup-*.sql.gz
scp deploy/db-backup-*.sql.gz <prod-app-host>:/home/deploy/metric-code/deploy/
```

### 步骤 2：scp 文件到生产 app 主机（dev Mac 上）

```bash
PROD_APP=<prod-app-host>

# 4 个镜像文件
scp /Users/developer/metric_code/deploy/metric-backend-v2026.07.03-amd64.tar.gz \
    /Users/developer/metric_code/deploy/metric-backend-v2026.07.03-amd64.tar.gz.md5 \
    /Users/developer/metric_code/deploy/metric-frontend-v2026.07.03-amd64.tar.gz \
    /Users/developer/metric_code/deploy/metric-frontend-v2026.07.03-amd64.tar.gz.md5 \
    ${PROD_APP}:/home/deploy/metric-code/deploy/

# 新脚本 + compose
scp /Users/developer/metric_code/deploy/scripts/prod-load-and-run.sh \
    ${PROD_APP}:/home/deploy/metric-code/deploy/scripts/
scp /Users/developer/metric_code/docker-compose.prod.yml \
    ${PROD_APP}:/home/deploy/metric-code/docker-compose.yml

# ⚠️ 必改项：prod nginx 配置（见 §3）
scp /Users/developer/metric_code/deploy/nginx/metric-code.conf \
    ${PROD_APP}:/path/to/your/nginx/conf.d/metric-code.conf
```

### 步骤 3：在生产 app 主机上部署

```bash
ssh ${PROD_APP}
cd /home/deploy/metric-code

# 确认备份文件在了
ls -la deploy/db-backup-*.sql.gz | tail -1

# 首次跑之前编辑 .env，追加 §2 中的 SSO_* 环境变量
vi .env

# 跑部署脚本（会自动检查备份、加载镜像、起容器、等健康检查）
bash deploy/scripts/prod-load-and-run.sh v2026.07.03
# 注意：不带 -amd64 后缀！脚本会自动拼成 metric-backend-v2026.07.03-amd64.tar.gz
```

### 步骤 4：验证（生产 app 主机）

```bash
docker ps --filter name=metric- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
curl -fsS http://localhost:18080/api/session/config
# 进 backend 容器看 Flyway 是否跑完 13 个增量
docker exec metric-backend sh -c "tail -200 /app/logs/*.log 2>/dev/null | grep -iE 'migrat|flyway|schema_history'" || true
```

---

## 2. .env 中本次必须确认 / 新增的项

### 2.1 上次已有、继续保留（核对一遍）

```ini
VERSION=v2026.07.03
FRONTEND_PORT=80
DB_URL=jdbc:mysql://<生产 MySQL IP>:3306/system_operations_metrics?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USER=metric_app
DB_PASSWORD=<生产密码>
HOST_FILES_PATH=/home/deploy/metric-code/files
HOST_LOGS_PATH=/home/deploy/metric-code/logs
JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC
APPUSER_AGENT_HOME=/opt/appuser-agent
APPUSER_AGENT_NAME=mop
HOST_APPUSER_AGENT_PATH=/home/deploy/metric-code/appuser-agent-runtime
```

### 2.2 本次新增：6+ 个 OAuth2 / usermanager 集成变量

> ⚠️ **Spring Security OAuth2 client 仅启动期绑定。** 这些 env 通过 Spring 占位符注入，**改完必须重启 backend**。V42 在 `system_config` 表里写入的同名 key（如 `sso.client_id`）**不会自动生效** — 不要只改 system_config。

```ini
# ---- OAuth2 客户端（向 usermanager 运维获取）----
SSO_OIDC_CLIENT_ID=metric-code
SSO_OIDC_CLIENT_SECRET=<从 usermanager 控制台拿到的密钥>
SSO_OIDC_SCOPE=profile
# ⚠️ 默认值是 openid,profile；dev mock IdP 对 OIDC scope 处理有 bug，
#    6.29 切到 plain OAuth2 后生产对接仍可能踩到，按 usermanager 实际支持范围调整

# ---- usermanager 4 个端点（替换 host/path）----
SSO_AUTH_URI=https://usermanager.<贵司域名>/oauth/authorize
SSO_TOKEN_URI=https://usermanager.<贵司域名>/oauth/token
SSO_JWKS_URI=https://usermanager.<贵司域名>/.well-known/jwks.json
SSO_USERINFO_URI=https://usermanager.<贵司域名>/usermanager/user/me

# ---- 前端跳转拼接（留空 = 走 nginx 同源反代）----
SSO_FRONTEND_BASE_URL=
SSO_FRONTEND_CALLBACK_PATH=/metric-code/sso/callback
SSO_FRONTEND_LOGIN_PATH=/metric-code/login
SSO_DEFAULT_TARGET=/metric-code/
```

### 2.3 usermanager 控制台要建的应用配置

去 usermanager 应用管理新建一项 OAuth2.0 应用：

| 字段 | 值 |
|---|---|
| 应用名称 | metric-code（按运维约定） |
| 应用类型 | OAuth2.0 Web 应用 |
| **回调地址 (redirect_uri)** | `https://<生产域名>/metric-code/login/oauth2/code/sso` |
| Scope | `profile`（或按 §2.2 中 SSO_OIDC_SCOPE 的设定） |

⚠️ **回调 URL 必须与 nginx 反代路径一致** — 同时确保 §3 的 nginx 改动已经做完，否则浏览器会直连 backend:18080 容器外部不可达。

### 2.4 用户首次登录自动建账号

- V42 在 `system_config` 表写入 `sso.auto_create_user=true`（默认开启）
- 关闭：把 `system_config.config_value='false'` 改一下即可（**这条 key 是服务运行时读的**，不用重启 backend）
- 关闭后未命中本地账号会抛 `OAuth2AuthenticationException` 走登录页错误提示

---

## 3. nginx 必须改的两处反代

`deploy/nginx/metric-code.conf` 当前只反代了 `/metric-code/api/*`。OAuth2 authorization_code 流还需要：

```
GET /oauth2/authorization/sso        （前端拿 /api/sso/login-url 后跳这条）
GET /login/oauth2/code/sso           （usermanager 回调）
```

把这俩路径也加反代：

```nginx
location /oauth2/ {
    proxy_pass http://backend:18080/oauth2/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_http_version 1.1;
    proxy_connect_timeout 60s;
    proxy_send_timeout    300s;
    proxy_read_timeout    300s;
}

location /login/oauth2/ {
    proxy_pass http://backend:18080/login/oauth2/;
    # 同上 headers / timeouts
}
```

> **为什么不写在 `/metric-code/` 前缀下？** application.yml 里 `redirect-uri: "{baseUrl}/login/oauth2/code/sso"`，`{baseUrl}` 由 Spring Security 算 = `scheme + host + port + context-path`。生产没设 server.servlet.context-path，所以 `baseUrl` = `https://<生产域名>`，回调路径是不带 `/metric-code/` 前缀的绝对路径。

**重启 nginx：** `nginx -t && systemctl reload nginx`（或 `nginx -s reload`）

---

## 4. 数据库升级：不需要手动跑 SQL

### 4.1 机制

- `application.yml` `flyway.baseline-version=33` + `baseline-on-migrate=true`
- 容器首次启动时 Flyway 检测 `flyway_schema_history`：
  - 不存在 → 把当前 schema baseline 到 V33 → 跳 V1-V32 → 顺序跑 V34-V46
  - 存在 → 从上次执行版本续跑
- **应用启动时自动执行，不需要 DBA 介入**

### 4.2 本次镜像会跑的 13 个增量 SQL

| 版本 | 内容 | 备注 |
|---|---|---|
| V34 | 5 张 health_snapshot 健康度预聚合表 | 建表 |
| V35 | user_session 会话表 | 建表 |
| V36 | 清理 alarm/gongdan 内容字段数字尾随零 | UPDATE，秒~分钟 |
| V37 | alarm.category_name → VARCHAR(200) | ALTER |
| V38 | 修正指标值告警/工单的 category_name | 数据修正 |
| V39 | sys_user 表 + 会话空闲超时配置 | 配合 V40 用 |
| V40 | sys_role / sys_menu / sys_role_menu 三张权限表 | 配合 V39 用 |
| **V41** | sso_auto_create_user 开关写入 system_config | 见 §2.4 |
| **V42** | sys_user 扩 OIDC 字段（external_id/source/email/last_login_time）+ sys_role 同步 + idp_sync_log + 12 条 sso.* 默认值 | 见 §2.2 |
| V43 | 补 alarm.access_count 列 | ALTER |
| **V44** | **一次性修复 baseline=33 漂移：metric_item/issue/gongdan 表缺列补齐** | 幂等（information_schema 判定） |
| **V45** | **一次性修复 alarm 表 baseline 漂移：15 列补齐** | 幂等 |
| V46 | notification_event 通知事件表 | 配合 commit `4b38016` 全局 Toast |

### 4.3 监控 Flyway 执行

```bash
# 容器视角
docker logs metric-backend 2>&1 | grep -iE 'migrating|migrated|baseline|schema_history'

# 库内视角
mysql -u<user> -p<pwd> system_operations_metrics \
  -e "SELECT installed_rank, version, description, success, execution_time FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 15;"
```

预期看到 V34-V46 共 13 行全是 `success=1`。

### 4.4 V44/V45 为什么是必须的（旧生产的隐患）

旧生产基线 V33 时，V1-V32 被标记为"已应用"但 ALTER 实际未执行 → 生产 DB 缺 `metric_item.sub_category_*`、`issue.sub_category_name`、`alarm.metric_name`、`alarm.related_issue_id` 等十几列。V44/V45 用 `information_schema` 预编译语句做 `IF NOT EXISTS` 判断，dev/prod 都能用，prod 已有列则 `DO 0` 跳过，**不会误删已有数据**。

### 4.5 是否有停服窗口需要

- V36 = UPDATE（清理 alarm/gongdan content 数字尾随零），数据量大可能数秒到 1 分钟
- V44/V45 = ADD COLUMN（MySQL 8.0+ InnoDB Online DDL，可 INSTANT）
- 业务低峰启动更稳，但**不需要"停服→手跑 SQL→起服"**，让 Flyway 自动跑就行

---

## 5. 部署后冒烟测试

```bash
# 1) 容器健康
docker ps --filter name=metric- --format 'table {{.Names}}\t{{.Status}}'

# 2) 后端健康端点
curl -fsS http://localhost:18080/api/session/config
# 期望：HTTP 200 + JSON（包含 session 配置）

# 3) Flyway 已落地
mysql -u<user> -p<pwd> system_operations_metrics \
  -e "SELECT MAX(installed_rank) AS latest_version FROM flyway_schema_history WHERE success=1"
# 期望：至少含 V46（最高版本号）

# 4) 前端入口
curl -fsSI http://localhost/metric-code/ | head -1
# 期望：HTTP 200

# 5) OAuth2 路径
curl -fsS -o /dev/null -w '%{http_code}\n' http://localhost/oauth2/authorization/sso
# 期望：302（Spring Security 跳 usermanager authorize 端点）
#  ⚠️  若返 404 → nginx 没加 §3 的反代

# 6) 浏览器验证 SSO 全流程
#    → 打开 https://<生产域名>/metric-code/
#    → 点"统一认证登录"
#    → 跳 usermanager → 输账号 → 回跳 → 进首页
#    → 看 sys_user 是否自动建好（V42 字段 external_id/source 应该有值）

# 7) appuser-agent（如启用）
docker logs metric-backend 2>&1 | grep -i 'appuser\|agentInfo'
ls /home/deploy/metric-code/appuser-agent-runtime/agentInfo/mop/
```

---

## 6. 回滚预案（万一冒烟失败）

```bash
# 切回老版本（假设上次是 v2026.06.29-amd64）
ssh ${PROD_APP}
cd /home/deploy/metric-code
VERSION=v2026.06.29 bash deploy/scripts/prod-load-and-run.sh v2026.06.29

# 数据库不需要回滚：Flyway 写过的版本都记在 flyway_schema_history，
# 再跑老版本镜像会报 Validate failed (V44-V46 > 老镜像认知的 V43)。
# 应急可：UPDATE flyway_schema_history SET type='BASELINE' WHERE version IN ('44','45','46');
#         或老镜像里临时把 baseline-version 调到 46。
# 如出错先回滚代码，不要回滚数据库！
```

⚠️ **原则：升级失败只回滚镜像版本，DB schema 一律不降级**。如果实在需要 DB 回滚，先备份再说：

```bash
mysqldump -u<user> -p<pwd> system_operations_metrics \
  --routines --triggers --single-transaction \
  --set-gtid-purged=OFF --databases system_operations_metrics \
  | gzip > /tmp/db-backup-before-rollback-$(date +%Y%m%d_%H%M%S).sql.gz
```

---

## 7. 涉及文件清单

本次升级新增 / 修改的源文件（已合入 v2026.07.03 镜像）：

```
metric_code/
├── Dockerfile.frontend                              # 新增
├── docker-compose.prod.yml                          # 新增
├── .env.production                                  # 新增（不要提交）
├── .env.example                                     # 新增（不要提交）
├── deploy/
│   ├── PRODUCTION_DEPLOY_GUIDE.md                   # 新增（通用流程）
│   ├── UPGRADE_NOTES_2026-07-03.md                  # ← 本文件
│   ├── nginx/metric-code.conf                       # ⚠️ 本次需手动加 /oauth2/ 反代
│   ├── scripts/prod-build.sh                        # 新增（含 amd64 断言）
│   └── scripts/prod-load-and-run.sh                 # 新增（含备份闸门+MD5 校验）
├── src/main/resources/db/migration/V44__fix_baseline_schema_drift.sql     # 新增
├── src/main/resources/db/migration/V45__fix_alarm_baseline_schema_drift.sql # 新增
└── src/main/resources/db/migration/V46__add_notification_event.sql        # 新增
```
