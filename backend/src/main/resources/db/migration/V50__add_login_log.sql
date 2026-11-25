-- V50: 登录日志表（2026-07-10 恢复并升级）
--
-- 历史：
--   V35 创建 user_session（本地登录会话表，2026-07-08 V47 DROP）
--   本次独立建 login_log：分离"会话状态"与"登录审计"两个职责。
--     会话状态 → Bearer JWT 持有在客户端 / Authorization header（无服务端会话）
--     登录审计 → login_log 持久化每次登录事件，支持多维查询 + 统计聚合

CREATE TABLE IF NOT EXISTS login_log (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  username          VARCHAR(100) NOT NULL,
  user_id           BIGINT,
  source            VARCHAR(20)  NOT NULL,
  result            VARCHAR(20)  NOT NULL,
  status            VARCHAR(20)  NOT NULL,
  client_ip         VARCHAR(50),
  user_agent        VARCHAR(500),
  browser_name      VARCHAR(50),
  browser_version   VARCHAR(20),
  os_name           VARCHAR(50),
  device_type       VARCHAR(20),
  screen_width      INT,
  screen_height     INT,
  login_time        DATETIME     NOT NULL,
  logout_time       DATETIME,
  duration_minutes  INT,
  fail_reason       VARCHAR(200),
  jwt_iat           DATETIME,
  jwt_sub           VARCHAR(100),
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  INDEX idx_login_time (login_time),
  INDEX idx_username (username),
  INDEX idx_status (status),
  INDEX idx_source (source),
  INDEX idx_result (result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录审计日志';