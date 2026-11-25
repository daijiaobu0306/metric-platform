-- V42: OIDC 统一认证 SSO 接入支持
--
-- 1. sys_user 扩展 OIDC 字段：external_id（IdP sub）、source（账号来源）、
--    email、last_login_time；display_name 扩到 VARCHAR(100)
-- 2. sys_role 扩展 OIDC 字段：external_id、source，便于按 IdP 角色同步
-- 3. user_session 增加 source 字段，区分三种登录入口产生的会话
-- 4. 新增 idp_sync_log 表，记录定时同步 IdP 用户/角色任务的执行情况
-- 5. system_config 写入 OIDC 客户端配置 + 同步任务默认参数
--    （config_type='sso'，与 V41 的 sso_auto_create_user 同 bucket）

-- ====== sys_user 扩展字段 ======
ALTER TABLE sys_user
    ADD COLUMN external_id VARCHAR(100) NULL COMMENT 'IdP subject (sub claim)，用于 OIDC 同步匹配',
    ADD COLUMN source VARCHAR(50) NULL COMMENT '账号来源: local / sso_oidc / sso_access_link',
    ADD COLUMN email VARCHAR(100) NULL COMMENT '邮箱',
    ADD COLUMN last_login_time DATETIME NULL COMMENT '最近一次登录时间',
    MODIFY COLUMN display_name VARCHAR(100) NULL COMMENT '显示名称';

CREATE UNIQUE INDEX uk_sys_user_external_id ON sys_user (external_id);
CREATE INDEX idx_sys_user_source ON sys_user (source);

-- ====== sys_role 扩展字段 ======
ALTER TABLE sys_role
    ADD COLUMN external_id VARCHAR(100) NULL COMMENT 'IdP 角色 ID，用于 OIDC 同步匹配',
    ADD COLUMN source VARCHAR(50) NULL COMMENT '角色来源: local / sso_oidc';

CREATE UNIQUE INDEX uk_sys_role_external_id ON sys_role (external_id);

-- ====== user_session 增加来源标识 ======
ALTER TABLE user_session
    ADD COLUMN source VARCHAR(50) NULL COMMENT '会话来源: local / sso_oidc / sso_access_link';

-- ====== idp_sync_log 同步日志表 ======
CREATE TABLE IF NOT EXISTS idp_sync_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    started_at DATETIME NOT NULL COMMENT '同步开始时间',
    finished_at DATETIME NULL COMMENT '同步结束时间',
    users_synced INT DEFAULT 0 COMMENT '同步的用户数量',
    roles_synced INT DEFAULT 0 COMMENT '同步的角色数量',
    status VARCHAR(20) NOT NULL COMMENT '执行状态: SUCCESS / FAILED / RUNNING',
    error TEXT NULL COMMENT '失败时的错误信息',
    trigger_type VARCHAR(20) NULL COMMENT '触发方式: scheduled / manual',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IdP 用户/角色同步日志';

CREATE INDEX idx_idp_sync_log_started_at ON idp_sync_log (started_at);

-- ====== OIDC + 同步 system_config 默认值 ======
-- 注意端口 18080（CLAUDE.md 后端端口），不要写成包样例的 8080
INSERT INTO system_config (config_key, config_value, config_type, description, status, create_time, update_time) VALUES
('sso.client_id',                 'placeholder-client-id',                                          'sso', 'OIDC client_id（向统一认证平台运维获取）',     1, NOW(), NOW()),
('sso.client_secret',             'placeholder-client-secret',                                      'sso', 'OIDC client_secret',                            1, NOW(), NOW()),
('sso.authorization_uri',         'http://127.0.0.1:7200/usermanager/oauth/authorize',             'sso', 'OIDC 授权端点',                                1, NOW(), NOW()),
('sso.token_uri',                 'http://127.0.0.1:7200/usermanager/oauth/token',                 'sso', 'OIDC Token 端点',                              1, NOW(), NOW()),
('sso.jwks_uri',                  'http://127.0.0.1:7200/usermanager/.well-known/jwks.json',       'sso', 'OIDC JWK Set 端点',                            1, NOW(), NOW()),
('sso.user_info_uri',             'http://127.0.0.1:7200/usermanager/usermanager/user/me',         'sso', 'OIDC userinfo 端点',                          1, NOW(), NOW()),
('sso.scope',                     'openid profile',                                                 'sso', 'OIDC scope',                                   1, NOW(), NOW()),
('sso.redirect_uri',              'http://localhost:18080/login/oauth2/code/sso',                  'sso', 'OIDC 回调 URI（端口 18080 与后端一致）',        1, NOW(), NOW()),
('sso.frontend_base_url',         'http://localhost:28080',                                         'sso', '前端 base URL（生产用空值走 nginx 同源）',     1, NOW(), NOW()),
('sso.issuer',                    'http://127.0.0.1:7200/usermanager',                             'sso', 'OIDC issuer',                                  1, NOW(), NOW()),
('sso.auto_create_user',          'true',                                                           'sso', 'OIDC 首次登录自动创建本地账号（覆盖 V41 的访问链接同名开关）', 1, NOW(), NOW()),
('sso.sync.enabled',              'false',                                                          'sso', '是否启用定时从 IdP 同步用户/角色',             1, NOW(), NOW()),
('sso.sync.fixed_delay_ms',       '3600000',                                                        'sso', '定时同步间隔（毫秒，默认 1 小时）',           1, NOW(), NOW()),
('sso.sync.api_base_url',         '',                                                               'sso', 'IdP 同步 API base URL（查询 /user、/role 时使用）', 1, NOW(), NOW()),
('sso.sync.api_token',            '',                                                               'sso', 'IdP 服务账号 token（如 IdP 查询需要鉴权时填）', 1, NOW(), NOW());