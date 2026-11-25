# 云 Staging 自动部署（2026-09-08 落地）

## 链路
- Gitee main push → staging `staging-poll.timer` 每 60s 轮询 → 检测 origin/main 变化 → git pull + docker compose up → 健康检查 → 部署完成
- 容器入口自动完成已有库升级：检查迁移 → 有缺失时完整备份并记录台账 → 补齐历史及新增迁移 → 启动后端。工具已打入镜像，服务器无需安装 JDK/Maven 或手工执行 SQL。备份或迁移失败不启动应用，同一失败版本不会反复迁移。详见 [自动升级说明](../doc/数据采集改造1008/34-Docker自动增量升级.md)。

## 关键文件
- `deploy/scripts/staging-poll-deploy.sh` — 轮询脚本
- `deploy/scripts/staging-poll.service` / `.timer` — systemd unit
- `/etc/systemd/system/staging-poll.{service,timer}` — 装在 staging 上（已 enable）

## 关键修复（2026-09-08）
1. `/api/v1/oauth2/login-url` → `/api/sso/login-url`（backend healthcheck，原路径永远 401）
2. `http://localhost/healthz` → `http://127.0.0.1/healthz`（nginx healthcheck，alpine nginx listen IPv4，localhost 走 ::1 Connection refused）

## Gitee Webhook 状态
- 已确认卡死（UI「测试」按钮无反应，push 事件不触发，2026-09-08 15:32 最后一次请求空响应）
- 改走 polling 兜底；如未来 Gitee 恢复，可重新启用 webhook-deploy（`/opt/metric-staging/deploy/scripts/webhook-deploy.mjs`），但目前未跑

## 端口
- Backend: 18081（host）→ 18080（容器）
- Nginx:   28081（host）→ 80（容器）
- Webhook-deploy: 28082（systemd，未启用）

## SSH 访问
- 免密 key：`~/.ssh/staging_ed25519`（Mac 本地）
- staging：`root@8.148.195.209`
