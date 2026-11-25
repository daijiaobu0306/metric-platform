-- 插件认证配置库 + plugin_config.auth_id 关联字段
CREATE TABLE IF NOT EXISTS plugin_authentication (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    login_url VARCHAR(500) NOT NULL,
    login_method VARCHAR(10) DEFAULT 'POST',
    login_content_type VARCHAR(50) DEFAULT 'application/json',
    login_body TEXT,
    login_headers TEXT,
    token_path VARCHAR(200) NOT NULL,
    token_header_name VARCHAR(100) DEFAULT 'Authorization',
    token_value_prefix VARCHAR(50) DEFAULT 'Bearer ',
    ttl_seconds INT DEFAULT 3600,
    enabled INT DEFAULT 1,
    create_time DATETIME,
    update_time DATETIME,
    UNIQUE KEY uk_plugin_authentication_name (name),
    INDEX idx_plugin_authentication_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='插件认证配置';

-- plugin_config 加 auth_id 关联
ALTER TABLE plugin_config
    ADD COLUMN auth_id BIGINT NULL COMMENT '关联认证配置 id',
    ADD INDEX idx_plugin_config_auth_id (auth_id);
