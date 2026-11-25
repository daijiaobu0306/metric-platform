# 指标体系平台

面向企业内部运维的指标体系管理平台：从指标定义、数据采集、阈值告警，到工单流转、问题闭环，全链路打通。

后端 Spring Boot 3.2（模块化单体），前端 Vue 3 + Element Plus，数据库 MySQL 8。

> 本仓库为技术展示版本：已移除全部业务数据、客户信息与生产配置，保留完整的工程结构与实现。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.2 · Spring Security · Spring Data JPA · Flyway |
| 前端 | Vue 3 · Element Plus · Vite · Pinia · Axios |
| 数据库 | MySQL 8 · Flyway 版本化迁移 |
| 采集 | Python 采集器 · Worker 调度 · MCP 协议接入 |
| 部署 | Docker Compose · Nginx · systemd |

---

## 模块划分

后端按业务域拆成 14 个模块，同一模块内保持 `controller / service / repository / entity / dto` 分层：

| 模块 | 职责 |
|---|---|
| `metric` | 指标定义、指标大类与小类、系统信息、Excel 导入导出 |
| `alarm` | 多档告警策略、原始数据规则、告警分组与状态跟踪 |
| `gongdan` | 工单全生命周期，对接外部工单系统（创建 / 查询 / 归档 / 撤销） |
| `issue` | 问题登记、跟踪、闭环，可由告警转入 |
| `collect` | 采集任务配置、采集源管理、数据入库 |
| `observe` | 观测视图、专屏、自定义区间、共享看板 |
| `report` | 运营报表、趋势对比、周期汇总 |
| `security` | 认证授权、会话管理、多端登录、SSO 接入 |
| `mcp` | MCP Server 接入，把平台能力以工具形式开放给 AI Agent |
| `audit` | 登录日志、操作审计 |
| `shared` | 统一响应、异常、路径解析等通用能力 |
| `config` | 框架配置，含数据库迁移守卫 |

---

## 几个值得说说的工程实践

### 1. 数据库迁移：不允许"悄悄跳过"

Spring Boot 默认的 Flyway 策略是启动时自动补齐缺失迁移。这在多环境、多代际数据库上会出问题——历史迁移的方言差异、转储带来的账本不一致，都可能让应用对着错误的库结构启动。

这里覆盖了默认策略，加了显式守卫（`config/GuardedFlywayConfiguration`）：

- 只有**白名单内**的历史迁移允许被标记为 IGNORED 跳过
- 其余任何 PENDING / IGNORED 的迁移，直接抛出异常拒绝启动
- 已执行迁移的 checksum 仍然逐条校验

宁可启动失败，也不要带着不确定的库结构跑起来。配套的 `deploy/scripts/prepare-database.py` 负责在容器启动前完成备份与迁移补齐。

### 2. 会话鉴权：不用 JWT，自己管会话

`security` 模块实现的是一套基于会话键的认证：

- 自定义 `SessionKeyAuthenticationFilter` 接管请求认证
- 会话落库，支持多端登录、单点强制下线
- 空闲超时、会话续期、登录日志分离
- 同时支持本地账号和 OIDC 单点登录两条入口

对内部系统来说，会话可以随时吊销这一点，比无状态 JWT 更实用。

### 3. 让 AI Agent 安全地读数据

`mcp` 模块把平台能力包装成 MCP 工具（查表结构、只读查询、告警查询、工单查询），暴露给外部 AI Agent 平台。

- 每个工具独立鉴权，令牌可单独吊销
- 所有调用写入调用日志表，可追溯
- SQL 侧只开放只读能力，写操作需要显式配置表白名单

配套的 Python 实现见 [mcp-servers](https://github.com/daijiaobu0306/mcp-servers)。

### 4. 采集与平台解耦

数据采集不走 Java 内部定时任务，而是独立的 Python 采集器 + Worker：

- 采集器按"数据来源 + 指标编码"匹配指标，不依赖名称相似度
- 解析插件独立更新，不改主程序
- Worker 通过统一 API 回写指标值，平台只负责校验与入库

这样换数据源、加采集口径都不用动后端。

---

## 本地运行

```bash
# 后端（需要本地 MySQL 8）
mvn -f backend/pom.xml clean package
mvn -f backend/pom.xml spring-boot:run

# 前端
npm --prefix frontend install
npm --prefix frontend run dev
```

数据库连接通过环境变量注入，配置模板见 `deploy/config/.env.example`。

**注意**：`backend/src/main/resources/db/migration/V1__init_schema.sql` 内含 `CREATE DATABASE` 与无条件 `DROP TABLE` 语句，只可在**全新空库**执行。对已有数据的库重放会清空表。

---

## 目录结构

```
├── backend/
│   ├── src/main/java/com/metric/     # 14 个业务模块
│   ├── src/main/resources/
│   │   └── db/migration/             # Flyway 迁移脚本
│   └── collection-python/            # Python 采集器与插件
├── frontend/src/                     # Vue 3 应用
├── deploy/                           # 容器、Nginx、发布脚本、环境模板
└── scripts/                          # 开发辅助脚本
```

---

## 说明

- 本仓库不含任何真实业务数据、客户信息、生产地址或凭据
- 配置模板中的 IP、账号、口令均为示例值，使用前需替换
- 代码仅供技术参考，未针对公开环境做安全加固
