CREATE TABLE IF NOT EXISTS dashboard_share_link (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(48) NOT NULL,
  secret_hash CHAR(64) NOT NULL,
  view_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  expires_at DATETIME NOT NULL,
  max_refresh_seconds INT NOT NULL DEFAULT 30,
  created_by BIGINT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  last_used_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dashboard_share_public_id (public_id),
  KEY idx_dashboard_share_view_status (view_id,status),
  KEY idx_dashboard_share_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='免登录专屏分享链接';

CREATE TABLE IF NOT EXISTS dashboard_share_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  share_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  ip_hash CHAR(64) NULL,
  user_agent VARCHAR(500) NULL,
  device_type VARCHAR(32) NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_active_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dashboard_share_session_token (token_hash),
  KEY idx_dashboard_share_session_share (share_id,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专屏分享访客会话';

CREATE TABLE IF NOT EXISTS dashboard_share_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  share_id BIGINT NOT NULL,
  session_id BIGINT NULL,
  event_name VARCHAR(64) NOT NULL,
  target_type VARCHAR(64) NULL,
  target_id VARCHAR(128) NULL,
  page_name VARCHAR(100) NULL,
  ip_hash CHAR(64) NULL,
  device_type VARCHAR(32) NULL,
  user_agent VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_dashboard_share_event_share_time (share_id,create_time),
  KEY idx_dashboard_share_event_session_time (session_id,create_time),
  KEY idx_dashboard_share_event_name_time (event_name,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专屏分享访问与交互事件';
