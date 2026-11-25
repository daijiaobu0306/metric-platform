# Docker 容器化部署手册

> 本手册说明使用 Docker 部署 **metric-code 后端** 的完整流程。
> 前端继续以静态文件 + nginx 方式部署（参考 [DEPLOY_GUIDE.md](DEPLOY_GUIDE.md)），不涉及容器化。

---

## 1. 前置要求

### 1.1 生产服务器

| 组件 | 版本要求 | 说明 |
|---|---|---|
| 操作系统 | Linux x86_64（CentOS 7+/Ubuntu 20.04+） | |
| Docker | 20.10+ | `docker --version` 验证 |
| Docker Compose | v2(plugin 形式) | `docker compose version` 验证 |
| MySQL | 8.0+ | 宿主机/外部独立服务器,**不**用容器内 MySQL |
| 内存 | 建议 ≥4GB | JVM 默认 -Xmx2g + Python 全家桶占用 |
| 磁盘 | 镜像 ≈1GB + 数据视使用量 | |

### 1.2 开发机器（Mac）

| 组件 | 版本 |
|---|---|
| Docker Desktop for Mac | 4.x+ |
| Git | 任意现代版本 |

---

## 2. 首次部署完整流程

### 2.1 在 Mac 上构建镜像

```bash
cd ~/path/to/metric_code
bash deploy/scripts/docker-build.sh
```

**输出**:
- 在 `deploy/` 下生成 `metric-code-{git_hash}-{日期}.tar.gz`
- 文件大小约 600-800MB(压缩后)

**首次构建会很慢**(下载基础镜像 + Python 库),后续构建有缓存会快很多。

### 2.2 传输到生产服务器

```bash
# 假设生产服务器路径 /home/appuser/metric-code2/
SERVER=user@your-prod-server
TARGET=/home/appuser/metric-code2

scp deploy/metric-code-*.tar.gz $SERVER:$TARGET/
scp deploy/docker-compose.yml         $SERVER:$TARGET/
scp deploy/config/.env.example               $SERVER:$TARGET/
scp deploy/scripts/docker-load-and-run.sh $SERVER:$TARGET/
```

### 2.3 配置生产环境变量

```bash
ssh $SERVER
cd /home/appuser/metric-code2
cp .env.example .env
vi .env
```

**必须修改的字段**:

```bash
VERSION=abc123-20260615        # 与上传的 tar.gz 文件名对应

# 数据库连接
DB_URL=jdbc:mysql://host.docker.internal:3306/system_operations_metrics?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USER=root
DB_PASSWORD=实际密码

# 宿主机文件目录
HOST_FILES_PATH=/home/appuser/metric-code2/files
HOST_LOGS_PATH=/home/appuser/metric-code2/logs
```

**关于 `DB_URL` 的 host 部分**:
- MySQL 装在**同一台宿主机**上 → 用 `host.docker.internal`(已配置 extra_hosts 桥接)
- MySQL 装在**别的服务器**上 → 直接写 IP 或域名,如 `mysql.internal.com`

### 2.4 数据库准备(重要)

#### 2.4.1 如果是全新数据库

```sql
CREATE DATABASE system_operations_metrics
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Flyway 会从 V1 开始执行所有迁移。

#### 2.4.2 如果是已有数据库（生产现状）

**关键**: 生产数据库当前已经手工执行到等同 V33 的状态。Flyway baseline 配置为 V33,首次启动时会:
1. 检测到没有 `flyway_schema_history` 表
2. 自动创建该表,写入 V33 作为 baseline
3. **不会**重复执行 V1-V33 任何 SQL

**部署前必须备份**:

```bash
mysqldump -u root -p \
    --single-transaction --routines --triggers \
    system_operations_metrics > backup_before_docker_$(date +%Y%m%d).sql
```

> **关于 V1-V33 这批 SQL 文件**:历史上不少表是 JPA `ddl-auto: update` 自动建的,这些 SQL 只记录了部分手动 DDL,不是完整 schema 引导。因此 baseline 只能用于"已经有完整表的库"(生产/dev 本机 MySQL 都满足),不能用于全新空库。如果以后需要在空库上重建 schema,先用 `mysqldump --no-data` 从生产或本机导出 schema 灌入,再启动应用让 Flyway baseline。

### 2.5 启动容器

```bash
# 文件名按实际填写
VERSION=abc123-20260615 bash docker-load-and-run.sh metric-code-abc123-20260615.tar.gz
```

脚本会自动:
1. 加载镜像 `docker load`
2. 验证 `.env` 配置
3. 检查 `HOST_FILES_PATH` 目录(不存在则创建)
4. `docker compose up -d` 启动
5. 健康检查(最多等 90 秒)

**成功提示**: `✅ 部署完成`,并打印监听端口、数据目录等。

### 2.6 nginx 反向代理(前端配合)

前端依然走 nginx 静态部署,只需把 API 请求代理到 `127.0.0.1:18080`:

```nginx
location /api/ {
    proxy_pass         http://127.0.0.1:18080;
    proxy_set_header   Host $host;
    proxy_set_header   X-Real-IP $remote_addr;
    proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 300s;
    client_max_body_size 100M;
}
```

---

## 3. 日常更新流程

### 3.1 标准更新（代码变更）

```bash
# Mac 端
git pull
bash deploy/scripts/docker-build.sh
# 复制 tar.gz 到服务器
scp deploy/metric-code-*.tar.gz $SERVER:/home/appuser/metric-code2/

# 服务器端
ssh $SERVER
cd /home/appuser/metric-code2
# 修改 .env 的 VERSION 字段为新版本号（与 tar.gz 文件名对应）
vi .env
VERSION=新版本号 bash docker-load-and-run.sh metric-code-新版本号.tar.gz
```

### 3.2 仅修改配置（不重新构建）

```bash
# 改 .env 后
docker compose down
docker compose up -d
```

### 3.3 紧急回滚

```bash
# 启动旧版本（旧镜像还在 docker 里）
# 改 .env 把 VERSION 改回旧版本号
docker compose down
docker compose up -d

# 查看本机所有镜像版本
docker images | grep metric-code
```

---

## 4. 配置项说明

### 4.1 `.env` 字段速查

| 字段 | 必填 | 说明 |
|---|---|---|
| `VERSION` | ✅ | 镜像 tag,对应 tar.gz 文件名中的版本号 |
| `APP_PORT` | ⭕ | 宿主机暴露端口,默认 18080 |
| `DB_URL` | ✅ | JDBC URL,**容器内可访问** |
| `DB_USER` | ✅ | 数据库用户名 |
| `DB_PASSWORD` | ✅ | 数据库密码 |
| `HOST_FILES_PATH` | ✅ | 宿主机文件存储绝对路径 |
| `HOST_LOGS_PATH` | ✅ | 宿主机日志路径 |
| `JAVA_OPTS` | ⭕ | JVM 参数,默认 `-Xms512m -Xmx2g -XX:+UseG1GC` |

### 4.2 容器内固定路径

| 容器内路径 | 用途 | 绑定关系 |
|---|---|---|
| `/app/app.jar` | 应用 jar | 镜像内嵌 |
| `/app/files` | 上传/备份根目录 | 挂载 `HOST_FILES_PATH` |
| `/app/logs` | 日志目录 | 挂载 `HOST_LOGS_PATH` |
| `/opt/venv/bin/python3` | Python 解释器 | 镜像内嵌(默认 PATH) |

---

## 5. 数据备份指南

### 5.1 数据库备份(在宿主机执行)

```bash
mysqldump -u root -p \
    --single-transaction --routines --triggers \
    system_operations_metrics | gzip > /backup/metric_db_$(date +%Y%m%d).sql.gz
```

建议加入 cron,每天自动备份。

### 5.2 文件备份

```bash
rsync -av --delete \
    /home/appuser/metric-code2/files/ \
    /backup/metric_files/
```

### 5.3 镜像备份

可选,主要是回滚用。本机已有的旧镜像默认就在 docker 里。

---

## 6. 排错指南

### 6.1 容器起不来

```bash
# 查看容器状态
docker compose ps

# 查看日志（最近 100 行）
docker compose logs --tail 100 backend

# 实时跟踪日志
docker compose logs -f backend
```

常见原因:
- `.env` 配错(DB_URL/密码错误)→ 日志会有 `Communications link failure`
- 宿主机 MySQL 没启动 → `Connection refused`
- 宿主机 MySQL 监听 `127.0.0.1` 而非 `0.0.0.0` → 容器内 `host.docker.internal` 连不上
- 端口 18080 被占用 → 改 `APP_PORT=18081` 等

### 6.2 Flyway 报错

```
FlywayException: Validate failed: Migrations have failed validation
```

可能原因:
- 数据库现状与 baseline-version=33 不匹配
- 某个 V 文件被改动导致 checksum 不一致

**临时跳过 Flyway 校验**(应急):

```bash
# 改 docker-compose.yml 增加环境变量
SPRING_FLYWAY_VALIDATE_ON_MIGRATE: "false"
```

修复后再改回 true。

### 6.3 Python 脚本报 ModuleNotFoundError

镜像内已装的库见 Dockerfile。若用户上传的脚本需要其他库:

**临时补装**(单容器内,重启失效):
```bash
docker exec metric-backend pip install <库名> \
    -i https://pypi.tuna.tsinghua.edu.cn/simple
```

**永久解决**: 改 Dockerfile,在 `pip install` 列表里加上,重新构建镜像。

### 6.4 文件上传后看不到

检查宿主机挂载目录:
```bash
ls -la $HOST_FILES_PATH
```

如果是空的,说明挂载没生效。检查:
- `.env` 中 `HOST_FILES_PATH` 是绝对路径
- 该目录的所有者/权限允许容器内 root 用户写入

### 6.5 ScanScheduler 没执行

```bash
docker compose logs backend | grep -i "scan"
```

应能看到定时任务调度日志。如果没有,检查是否有报错或 schedule 配置问题。

---

## 7. 与原 jar 部署的关系

| 维度 | 原 jar 部署([DEPLOY_GUIDE.md](DEPLOY_GUIDE.md)) | 新 Docker 部署(本文档) |
|---|---|---|
| 启动方式 | `start.sh` 脚本 + `java -jar` | `docker compose up -d` |
| 配置注入 | 命令行参数 `--spring.datasource.*` | 环境变量 + `.env` |
| Python 环境 | 手工 `setup_python_env.sh` 装系统 Python | 镜像内嵌虚拟环境 |
| DB Schema | 手工执行 SQL | Flyway 自动迁移 |
| 路径差异 | Mac/Linux 路径不一致经常出 bug | 容器内固定 `/app/files` |
| 升级 | scp jar + 重启 | scp tar.gz + 一键脚本 |
| 回滚 | 替换 jar 重启 | 改 `.env` 中 VERSION 重启 |

**推荐**: 新部署用 Docker;原 jar 部署保留作为回退方案,生产稳定运行至少一个月后再考虑下线。

---

## 8. 常用命令速查

```bash
# 查看运行状态
docker compose ps

# 查看日志
docker compose logs -f backend

# 进入容器内 shell
docker exec -it metric-backend bash

# 重启容器（不更新版本）
docker compose restart

# 停止
docker compose down

# 完全清理（含镜像和卷,谨慎使用）
docker compose down
docker rmi metric-code:latest

# 查看镜像清单
docker images metric-code

# 删除旧版本镜像（节省空间）
docker image prune -a --filter "label!=keep"
```
