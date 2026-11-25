# 指标体系平台 - Linux 部署操作手册

## 1. 系统概述

### 1.1 系统简介
指标体系平台是一个用于监控和管理CRM系统运行指标的Web应用平台。系统采用Java + Spring Boot + Vue.js构建，提供指标管理、告警管理、工单管理、问题管理等核心功能。

### 1.2 技术架构
- **后端**: Java 17 + Spring Boot 3.2.0
- **数据库**: MySQL 8.0
- **前端**: Vue.js 3 + Element Plus
- **Web服务器**: Nginx
- **构建工具**: Maven (后端) / npm (前端)

### 1.3 部署拓扑
```
                    用户访问 (http://IP:28080/metric-code/)
                              │
                              ▼
                    ┌─────────────────────┐
                    │  Nginx (28080端口)   │
                    │  /metric-code/路径   │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              │                                 │
              ▼                                 ▼
    ┌─────────────────┐              ┌─────────────────┐
    │  前端静态资源    │              │   后端API        │
    │  /var/www/...   │              │  localhost:18080│
    └─────────────────┘              └────────┬────────┘
                                             │
                                             ▼
                                   ┌─────────────────┐
                                   │     MySQL       │
                                   │  10.13x.1x7.4x  │
                                   │     3306端口     │
                                   └─────────────────┘
```

---

## 2. 环境要求

| 软件 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17+ | 必须使用JDK 17及以上版本 |
| Maven | 3.6+ | 项目构建工具 |
| MySQL | 8.0+ | 数据库 |
| Nginx | 1.18+ | Web服务器/反向代理 |
| Node.js | 16+ | 前端构建 (仅构建时需要) |
| npm | 8+ | 前端包管理 (仅构建时需要) |

### 2.1 服务器已有JDK8应用时的注意事项

**重要**: 如果服务器上已有JDK8运行其他应用，JDK17**不会**与之冲突，因为：
- 部署脚本使用 `$JAVA_HOME/bin/java` **绝对路径**启动本应用
- 不依赖系统默认的 `java` 命令
- JDK8应用和JDK17应用可以各自独立运行

请确保在 `config/env.conf` 中正确配置 `JAVA_HOME` 指向JDK17安装路径。

---

## 3. 部署包结构

```
metric-code-deploy/
├── bin/                          # 服务控制脚本
│   ├── start.sh                  # 启动后端服务
│   ├── stop.sh                   # 停止后端服务
│   ├── restart.sh                # 重启后端服务
│   └── health_check.sh           # 健康检查
├── backend/
│   ├── metric-code-0.0.1-SNAPSHOT.jar  # 后端JAR包
│   └── logs/                     # 日志目录
│       └── metric-code.log       # 后端日志文件
├── frontend/                      # 前端构建产物（直接解压使用）
│   ├── index.html
│   └── assets/
├── sql/
│   ├── init_schema.sql           # 完整数据库初始化脚本
│   └── setup_db.sh               # 数据库初始化脚本
├── nginx/
│   └── metric-code.conf          # Nginx配置文件
├── scripts/
│   ├── setup_complete.sh         # 一键部署脚本
│   └── setup_db.sh               # 数据库初始化脚本
├── config/
│   └── env.conf                  # 环境配置文件
└── DEPLOY_GUIDE.md              # 本文档
```

---

## 4. 配置说明

### 4.1 环境配置 (config/env.conf) - **必须修改**

```bash
# JDK17配置 - 请根据实际JDK17安装路径修改
JAVA_HOME=/usr/local/jdk17
export PATH=$JAVA_HOME/bin:$PATH

# 数据库配置 - 根据实际情况修改
DB_HOST=10.13x.1x7.4x         # MySQL服务器地址
DB_PORT=3306                   # MySQL端口
DB_NAME=system_operations_metrics  # 数据库名
DB_USER=root                   # 数据库用户名
DB_PASSWORD=请替换为实际密码    # 数据库密码（请修改！）

# 应用配置
APP_PORT=18080                 # 后端服务端口
FRONTEND_PORT=28080            # Nginx监听端口（前端统一入口）
UPLOAD_BASE_PATH=/home/appuser/metric-code2/files  # 文件存储目录（与 dev 端 files/ 保持一致）
LOG_DIR=backend/logs           # 日志目录
```

### 4.2 端口配置说明

| 端口 | 用途 | 默认值 | 配置位置 |
|------|------|--------|----------|
| 18080 | 后端Spring Boot | 18080 | config/env.conf 中的 APP_PORT |
| 28080 | Nginx监听端口 | 28080 | nginx/metric-code.conf 中的 listen |
| 3306 | MySQL | 3306 | config/env.conf 中的 DB_PORT |

### 4.3 文件存储路径配置 (UPLOAD_BASE_PATH)

此参数指定后端文件的存储路径，**必须使用绝对路径**。

```bash
# 正确示例 - 绝对路径（与 dev 端 files/ 目录对齐）
UPLOAD_BASE_PATH=/home/appuser/metric-code2/files

# 错误示例 - 相对路径（不要这样写！）
UPLOAD_BASE_PATH=files
```

**说明**：
- 文件存储功能由后端处理，文件存在后端服务器的本地文件系统中
- 前端不直接访问 files 目录，通过后端 API
- 建议创建专用存储目录，不要放在项目代码目录下
- 前后端分离部署时，此目录在后端服务器上
- **本项目已优化统一使用 `files/` 目录命名**（commit 214c1a3），与 dev 端保持一致，便于开发与部署路径对齐

**目录内容**（与 dev 端 `files/` 完全一致）：
| 目录 | 用途 |
|------|------|
| `ability/` | 能力指标相关文件 |
| `alarm/` | 告警相关文件 |
| `issue_attachments/` | 问题附件 |
| `logos/` | 平台Logo |
| `metric_backups/` | 指标数据备份 |
| `raw_data/` | 原始数据文件 |
| `scripts/` | 脚本技能库文件 |
| `test_folder/` | 测试目录 |
| `能力量纲文件上报数据/` | 能力量纲上报数据 |

---

## 5. 部署步骤

### 5.1 上传部署包

将部署包上传到服务器，解压到目标目录：

```bash
# 解压部署包
tar -xzf metric-code-deploy.tar.gz
cd metric-code-deploy
```

### 5.2 修改配置文件

**必须修改** `config/env.conf`：

```bash
# 使用vim或其他编辑器修改
vim config/env.conf

# 主要修改项：
# - JAVA_HOME: JDK17安装路径
# - DB_PASSWORD: MySQL密码
# - UPLOAD_BASE_PATH: 文件上传存储路径（必须是绝对路径）
```

### 5.3 初始化数据库

```bash
# 方式一：使用一键部署脚本（自动初始化数据库）
./scripts/setup_complete.sh

# 方式二：单独初始化数据库
./scripts/setup_db.sh
```

### 5.4 一键部署

如果使用一键部署脚本，脚本会自动完成：
1. 检查JDK17
2. 检查MySQL连接
3. 创建必要目录
4. 部署前端静态资源到 `/var/www/metric-code/frontend/`
5. 配置Nginx
6. 启动后端服务

```bash
./scripts/setup_complete.sh
```

### 5.5 手动部署（如需分步执行）

#### 5.5.1 部署前端
```bash
# 创建前端根目录
mkdir -p /var/www/metric-code

# 复制前端构建产物到Nginx目录
rm -rf /var/www/metric-code/frontend
cp -r frontend /var/www/metric-code/frontend
```

#### 5.5.2 配置Nginx
```bash
# 复制Nginx配置
cp nginx/metric-code.conf /etc/nginx/conf.d/

# 测试并重载Nginx
nginx -t
systemctl reload nginx
```

#### 5.5.3 启动后端
```bash
# 启动服务
./bin/start.sh

# 检查服务状态
./bin/health_check.sh
```

---

## 6. 服务控制

### 6.1 启动服务
```bash
./bin/start.sh
```

### 6.2 停止服务
```bash
./bin/stop.sh
```

### 6.3 重启服务
```bash
./bin/restart.sh
```

### 6.4 健康检查
```bash
./bin/health_check.sh
```

---

## 7. 验证部署

### 7.1 检查后端服务
```bash
# 检查后端进程
ps -ef | grep metric-code | grep -v grep

# 检查后端端口监听
netstat -tuln | grep 18080
# 或
ss -tuln | grep 18080

# 测试后端API（直连）
curl http://localhost:18080/api/system/list
```

### 7.2 检查Nginx代理
```bash
# 通过Nginx访问后端API
curl http://localhost:28080/metric-code/api/system/list

# 检查Nginx状态
systemctl status nginx
```

### 7.3 检查前端页面
```bash
# 访问前端首页
curl -I http://localhost:28080/metric-code/

# 应返回 200 OK，HTML 中包含 /metric-code/assets/ 路径的资源引用
```

### 7.4 检查数据库连接
```bash
mysql -h 10.13x.1x7.4x -u root -p -e "USE system_operations_metrics; SHOW TABLES;"
```

---

## 8. 日志说明

### 8.1 后端日志
- **位置**: `backend/logs/metric-code.log`
- **控制台输出**: `backend/logs/stdout.log`
- **PID文件**: `backend/logs/app.pid`
- **查看日志**:
  ```bash
  # 实时查看后端日志
  tail -f backend/logs/metric-code.log

  # 查看控制台输出
  tail -f backend/logs/stdout.log
  ```

### 8.2 Nginx日志
- **访问日志**: `/var/log/nginx/metric-code-access.log`
- **错误日志**: `/var/log/nginx/metric-code-error.log`

---

## 9. 目录结构

| 用途 | 路径 | 说明 |
|------|------|------|
| 前端静态资源 | `/var/www/metric-code/frontend/` | Nginx托管 |
| 文件存储目录 | `/home/appuser/metric-code2/files` | 用户上传/生成的各类文件（与 dev 端 `files/` 对齐） |
| 后端日志 | `backend/logs/` | 服务日志和PID |
| Nginx配置 | `/etc/nginx/conf.d/metric-code.conf` | 反向代理配置 |
| Nginx日志 | `/var/log/nginx/metric-code-*.log` | 访问和错误日志 |

---

## 10. 常见问题

### 10.1 后端启动失败
1. 检查JDK17是否正确安装：
   ```bash
   $JAVA_HOME/bin/java -version
   ```
2. 检查MySQL连接：
   ```bash
   mysql -h $DB_HOST -u $DB_USER -p$DB_PASSWORD -e "SELECT 1"
   ```
3. 查看后端日志：
   ```bash
   cat backend/logs/stdout.log
   ```

### 10.2 前端无法访问
1. 检查Nginx是否运行：
   ```bash
   systemctl status nginx
   ```
2. 检查前端目录是否存在：
   ```bash
   ls -la /var/www/metric-code/frontend/
   ```
3. 检查Nginx端口 28080 是否监听：
   ```bash
   ss -tuln | grep 28080
   ```
4. 检查Nginx配置：
   ```bash
   nginx -t
   ```

### 10.3 API请求失败
1. 检查后端端口 18080 是否监听：
   ```bash
   netstat -tuln | grep 18080
   ```
2. 检查Nginx反向代理配置是否正确（`/etc/nginx/conf.d/metric-code.conf`）
3. 检查防火墙是否开放 28080 端口

### 10.4 防火墙配置
```bash
# 开放28080端口 (CentOS/RHEL)
sudo firewall-cmd --add-port=28080/tcp --permanent
sudo firewall-cmd --reload

# 开放28080端口 (Ubuntu/Debian)
sudo ufw allow 28080/tcp

# 如需外部直接访问后端 18080（一般无需）
# sudo firewall-cmd --add-port=18080/tcp --permanent
# 或
# sudo ufw allow 18080/tcp
```

---

## 11. 卸载/重新部署

### 11.1 停止服务
```bash
./bin/stop.sh
```

### 11.2 清理资源
```bash
# 删除前端目录
rm -rf /var/www/metric-code

# 删除后端日志
rm -rf backend/logs/*

# 移除Nginx配置
rm /etc/nginx/conf.d/metric-code.conf
systemctl reload nginx
```

### 11.3 数据库保留
如果需要保留数据，不要删除数据库。重新部署只需：
1. 重启后端服务
2. 重新部署前端静态资源

---

## 12. 联系支持

如有问题，请联系系统管理员或开发团队。

---

*文档版本: 1.0*
*最后更新: 2026-05-18*