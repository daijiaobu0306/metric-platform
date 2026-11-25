-- =============================================================================
-- MCP 平台化：服务开关 / 访问令牌 / 调用日志（2026-11-19）
--
-- 背景：平台对外提供三个只读 MCP 服务（告警 alarm、工单 gongdan、问题 issue），
-- 内嵌在 backend 进程内，随平台启动；通过 Bearer Token 以机器身份访问。
-- 本迁移只新增三张表，不改动任何既有表结构。
--
-- 设计要点：
--   1. 布尔/状态列一律用 INT，不用 TINYINT(1)。
--      原因见台账 V55：MySQL Connector/J 把 TINYINT(1) 映射为 JDBC BIT，
--      Hibernate 6 的 schema-validation 期望 INTEGER，会导致启动校验失败。
--   2. 令牌只存 SHA-256，明文仅在创建时返回一次；token_prefix 用于页面展示与人工核对。
--   3. 三个服务初始 enabled=0（关闭），由管理员在「技能管理 → MCP 管理」手动开启。
--   4. 脚本无 USE / CREATE DATABASE / DROP，可安全对目标库执行。
-- =============================================================================

-- 1. MCP 服务配置（一服务一行）
CREATE TABLE IF NOT EXISTS mcp_server_config (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  server_key     VARCHAR(32)  NOT NULL COMMENT '服务标识：alarm / gongdan / issue',
  display_name   VARCHAR(64)  NOT NULL COMMENT '页面展示名',
  description    VARCHAR(255) DEFAULT NULL COMMENT '一句话说明，同时作为 MCP instructions',
  endpoint_path  VARCHAR(64)  NOT NULL COMMENT '后端 Servlet 路径，如 /mcp/alarm',
  enabled        INT          NOT NULL DEFAULT 0 COMMENT '1=对外开启，0=关闭（关闭时返回 404）',
  updated_by     VARCHAR(64)  DEFAULT NULL COMMENT '最后一次开关操作人',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mcp_server_key (server_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务开关与端点配置';

-- 2. MCP 访问令牌（机器身份，类比 API Key）
CREATE TABLE IF NOT EXISTS mcp_access_token (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  token_name    VARCHAR(64)  NOT NULL COMMENT '用途/使用者，如「运维一组-agent」',
  token_prefix  VARCHAR(16)  NOT NULL COMMENT '展示用前缀，如 mcp_7f3a9c2e',
  token_hash    VARCHAR(64)  NOT NULL COMMENT '令牌 SHA-256（hex），不存明文',
  server_scope  VARCHAR(128) NOT NULL DEFAULT '*' COMMENT '可访问服务：* 或逗号分隔 alarm,gongdan,issue',
  enabled       INT          NOT NULL DEFAULT 1 COMMENT '1=有效，0=停用',
  expires_at    DATETIME     DEFAULT NULL COMMENT 'NULL=永久有效',
  last_used_at  DATETIME     DEFAULT NULL COMMENT '最后一次成功调用时间',
  call_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '累计调用次数',
  created_by    VARCHAR(64)  DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at    DATETIME     DEFAULT NULL COMMENT '吊销时间，非空即不可用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_mcp_token_hash (token_hash),
  KEY idx_mcp_token_prefix (token_prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 访问令牌';

-- 3. MCP 调用日志（只读审计，不记录返回正文）
CREATE TABLE IF NOT EXISTS mcp_call_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  server_key    VARCHAR(32)  NOT NULL COMMENT 'alarm / gongdan / issue',
  token_id      BIGINT       DEFAULT NULL COMMENT '关联 mcp_access_token.id',
  token_name    VARCHAR(64)  DEFAULT NULL COMMENT '冗余令牌名，便于令牌删除后仍可追溯',
  tool_name     VARCHAR(64)  NOT NULL,
  args_digest   VARCHAR(500) DEFAULT NULL COMMENT '入参摘要（截断，不含敏感正文）',
  result_count  INT          DEFAULT NULL COMMENT '返回记录数；聚合类工具为分组数',
  duration_ms   INT          DEFAULT NULL,
  success       INT          NOT NULL DEFAULT 1 COMMENT '1=成功，0=失败',
  error_message VARCHAR(500) DEFAULT NULL,
  client_ip     VARCHAR(64)  DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mcp_log_created (created_at),
  KEY idx_mcp_log_server (server_key, created_at),
  KEY idx_mcp_log_token (token_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 工具调用审计';

-- 4. 三个服务初始配置（幂等；默认关闭）
INSERT INTO mcp_server_config (server_key, display_name, description, endpoint_path, enabled)
VALUES
  ('alarm',   '告警 MCP', '查询告警记录、告警对象与告警维度统计（只读）', '/mcp/alarm',   0),
  ('gongdan', '工单 MCP', '查询工单记录、BOMC 推送状态与处理环节（只读）', '/mcp/gongdan', 0),
  ('issue',   '问题 MCP', '查询问题登记、跟踪与解决情况（只读）',           '/mcp/issue',   0)
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  description  = VALUES(description),
  endpoint_path = VALUES(endpoint_path);
