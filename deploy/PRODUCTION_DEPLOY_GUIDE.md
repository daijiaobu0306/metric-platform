# metric-code 生产环境部署实施方案

## 架构概览

```
┌─────────────────────────────────────────────────────┐
│                  生产 Linux 主机                       │
│                                                       │
│  ┌──────────────┐    ┌──────────────┐                │
│  │  metric-      │    │  metric-      │                │
│  │  frontend     │───▶│  backend      │                │
│  │  (Nginx:80)   │    │  (Java:18080) │                │
│  └──────────────┘    └──────┬───────┘                │
│                             │                         │
└─────────────────────────────┼─────────────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │   生产 MySQL 主机   │
                    │   (独立服务器)      │
                    │   3306 端口         │
                    └───────────────────┘
```

两个独立镜像：
- metric-frontend — Nginx + 前端静态文件，负责页面展示和 API 反向代理
- metric-backend — Spring Boot + Python，负责业务逻辑和数据处理

数据库：
- 独立 MySQL 服务器，不在 Docker 容器内
- 从开发环境备份数据，直接恢复到生产库

---

## 一、环境准备清单

### 生产 Linux 主机要求

| 项目 | 最低要求 |
|------|---------|
| 操作系统 | CentOS 7+ / Ubuntu 20.04+ / Debian 10+ |
| Docker | 20.10+（含 compose 插件） |
| 内存 | 4GB+（JVM 分 2GB） |
| 磁盘 | 20GB+（镜像 + 日志 + 上传文件） |
| 端口 | 80（前端）、18080（后端，可选外部暴露） |

### 生产 MySQL 主机要求

| 项目 | 要求 |
|------|-----|
| MySQL 版本 | 5.7+ / 8.0+ |
| 字符集 | utf8mb4 |
| 用户权限 | CREATE/ALTER/INSERT/UPDATE/DELETE/SELECT/DROP |
| 网络 | 应用主机能访问 MySQL 主机的 3306 端口 |

---

## 二、完整部署流程

### 第一阶段：开发环境 — 构建镜像 + 备份数据库

#### 步骤 1：构建前后端镜像

在 Mac 开发机执行：

```bash
cd /Users/developer/metric_code

# 一键构建两个镜像并打包
bash deploy/scripts/prod-build.sh v1.0.0
```

产物：
```
deploy/metric-backend-v1.0.0.tar.gz    (~300-500MB)
deploy/metric-frontend-v1.0.0.tar.gz   (~20-50MB)
```

#### 步骤 2：备份开发数据库

```bash
# 默认连接本机 MySQL（root 用户）
bash deploy/scripts/backup-db.sh

# 或指定连接参数
bash deploy/scripts/backup-db.sh -h 127.0.0.1 -P 3306 -u root -p your_password
```

产物：
```
deploy/db-backup-20260617_120000.sql.gz
```

---

### 第二阶段：传输文件到生产服务器

假设生产环境拓扑：
- 应用主机 app-server：运行 Docker 容器（frontend + backend）
- 数据库主机 db-server：运行 MySQL

#### 步骤 3：传输到应用主机

```bash
scp deploy/metric-backend-v1.0.0.tar.gz   user@app-server:/home/deploy/metric-code/
scp deploy/metric-frontend-v1.0.0.tar.gz  user@app-server:/home/deploy/metric-code/
scp deploy/docker-compose.prod.yml               user@app-server:/home/deploy/metric-code/docker-compose.yml
scp deploy/config/.env.production                        user@app-server:/home/deploy/metric-code/.env
scp deploy/scripts/prod-load-and-run.sh   user@app-server:/home/deploy/metric-code/
```

#### 步骤 4：传输到数据库主机

```bash
scp deploy/db-backup-20260617_120000.sql.gz user@db-server:/tmp/
scp deploy/scripts/restore-db.sh            user@db-server:/tmp/
```

---

### 第三阶段：生产 MySQL — 恢复数据

#### 步骤 5：在数据库主机上恢复

```bash
ssh user@db-server

# 恢复备份（会删除已有表，用备份数据替换）
bash /tmp/restore-db.sh /tmp/db-backup-20260617_120000.sql.gz \
    -h 127.0.0.1 -u root -p your_production_mysql_password
```

脚本会：
1. 删除 system_operations_metrics 库（如果存在）
2. 从备份文件重建库和所有表
3. 导入全部数据
4. 验证关键表的记录数

#### 步骤 6：创建生产数据库用户（推荐）

```sql
-- 在 MySQL 中执行
CREATE USER 'metric_app'@'%' IDENTIFIED BY 'strong_password_here';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP
    ON system_operations_metrics.* TO 'metric_app'@'%';
FLUSH PRIVILEGES;
```

---

### 第四阶段：应用主机 — 部署容器

#### 步骤 7：配置 .env

```bash
ssh user@app-server
cd /home/deploy/metric-code

# 编辑 .env
vi .env
```

关键配置项：

```ini
# 数据库连接（指向数据库主机 IP）
DB_URL=jdbc:mysql://192.168.1.100:3306/system_operations_metrics?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USER=metric_app
DB_PASSWORD=strong_password_here

# 文件存储路径
HOST_FILES_PATH=/home/deploy/metric-code/files
HOST_LOGS_PATH=/home/deploy/metric-code/logs

# 前端端口
FRONTEND_PORT=80

# JVM 参数
JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC
```

#### 步骤 8：启动容器

```bash
cd /home/deploy/metric-code

# 加载镜像 + 启动 + 健康检查
bash prod-load-and-run.sh
```

脚本会：
1. 加载 backend 和 frontend 镜像
2. 验证 .env 配置
3. 停止旧容器（如果有）
4. 启动新容器
5. 等待后端健康检查通过
6. 验证前端可访问

#### 步骤 9：验证部署

```bash
# 查看容器状态
docker compose ps

# 应看到两个容器都是 running：
# metric-frontend   running   0.0.0.0:80->80/tcp
# metric-backend    running   0.0.0.0:18080->18080/tcp

# 测试后端 API
curl http://localhost:18080/api/metric/statistics

# 测试前端页面
curl -I http://localhost/metric-code/

# 查看实时日志
docker compose logs -f
```

浏览器访问：http://your-server-ip/metric-code/

---

## 三、后续更新流程

### 只更新前端

```bash
# 开发机：构建前端镜像
cd /Users/developer/metric_code
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
cd frontend && npm run build && cd ..
docker build -f deploy/docker/Dockerfile.frontend -t metric-frontend:v1.1.0 .
docker save metric-frontend:v1.1.0 | gzip > deploy/metric-frontend-v1.1.0.tar.gz

# 传输 + 部署
scp deploy/metric-frontend-v1.1.0.tar.gz user@app-server:/home/deploy/metric-code/
ssh user@app-server
cd /home/deploy/metric-code
VERSION=v1.1.0
gunzip -c metric-frontend-${VERSION}.tar.gz | docker load
VERSION=$VERSION docker compose up -d frontend
```

### 只更新后端

```bash
# 开发机：构建后端镜像
docker build -f deploy/docker/Dockerfile -t metric-backend:v1.1.0 .
docker save metric-backend:v1.1.0 | gzip > deploy/metric-backend-v1.1.0.tar.gz

# 传输 + 部署
scp deploy/metric-backend-v1.1.0.tar.gz user@app-server:/home/deploy/metric-code/
ssh user@app-server
cd /home/deploy/metric-code
VERSION=v1.1.0
gunzip -c metric-backend-${VERSION}.tar.gz | docker load
VERSION=$VERSION docker compose up -d backend
```

### 同时更新前后端

```bash
bash deploy/scripts/prod-build.sh v1.1.0
# 传输两个 tar.gz + 重新执行 prod-load-and-run.sh
```

---

## 四、运维常用命令

```bash
# 查看容器状态
docker compose ps

# 查看实时日志
docker compose logs -f
docker compose logs -f backend
docker compose logs -f frontend

# 重启服务
docker compose restart
docker compose restart backend

# 停止服务
docker compose down

# 查看资源占用
docker stats

# 进入容器调试
docker exec -it metric-backend bash
docker exec -it metric-frontend sh

# 查看后端日志文件
tail -f /home/deploy/metric-code/logs/metric-code.log
```

---

## 五、故障排查

### 后端启动失败

```bash
# 查看日志
docker compose logs --tail 200 backend

# 常见原因：
# 1. 数据库连接失败 — 检查 .env 中 DB_URL 是否正确，MySQL 主机防火墙是否放行 3306
# 2. Flyway 迁移失败 — 检查 MySQL 用户权限
# 3. 端口冲突 — 检查 18080 是否被占用: lsof -i :18080
```

### 前端无法访问

```bash
# 检查 nginx 容器
docker compose logs frontend

# 常见原因：
# 1. 后端未就绪 — frontend depends_on backend healthy，等后端启动完成
# 2. 端口冲突 — 检查 80 是否被占用: lsof -i :80
# 3. 未构建前端 — 确保 frontend/dist 目录存在
```

### API 返回 502

```bash
# nginx 无法连接后端
# 检查后端是否正常：curl http://localhost:18080/api/metric/statistics
# 检查容器网络：docker network ls
```

---

## 六、文件清单

本次部署涉及的文件：

```
metric_code/
├── deploy/docker/Dockerfile                    # 后端镜像构建
├── deploy/docker/Dockerfile.frontend           # 前端镜像构建（新增）
├── deploy/docker-compose.prod.yml       # 生产环境 compose（新增）
├── deploy/config/.env.production               # 生产环境变量模板（新增）
├── deploy/
│   ├── nginx/
│   │   ├── metric-code.conf      # 生产 nginx 配置（新增）
│   │   └── metric-code-dev.conf  # 开发 nginx 配置（已有）
│   ├── scripts/
│   │   ├── prod-build.sh         # 生产构建脚本（新增）
│   │   ├── prod-load-and-run.sh  # 生产部署脚本（新增）
│   │   ├── backup-db.sh          # 数据库备份脚本（新增）
│   │   └── restore-db.sh         # 数据库恢复脚本（新增）
│   └── PRODUCTION_DEPLOY_GUIDE.md  # 本文档（新增）
```
