-- V56: 幂等补种 system_config 默认值
--
-- 背景：staging 库 system_config 表为空（0 行），但 flyway_schema_history 中 V41/V42/V48
--       均标记 success=1（DB 从其它环境 dump 后 baseline，seed 数据丢失）。
--       结果：配置管理页 loadSsoConfig 打 /api/config/system/key/{key} 全部 404 →
--       前端 console 报 "加载 SSO 配置失败: Error: Config not found"。
--
-- 做法：INSERT ... ON DUPLICATE KEY UPDATE id=id（纯 no-op），config_key 上有唯一键，
--       已有值的环境不会被覆盖，缺失的环境自动补齐。
--
-- 注意：sso.* 的客户端凭据/端点在运行期由 application.yml / 环境变量绑定（UI 只读展示），
--       这里只保证「行存在」，不改动任何已有值。

INSERT INTO system_config (config_key, config_value, config_type, description, status, create_time, update_time) VALUES
('session_idle_timeout',           '20',                                                             'session', '会话空闲超时（分钟）',                          1, NOW(), NOW()),
('sso_auto_create_user',           'false',                                                          'sso', 'SSO 访问链接是否在用户不存在时自动创建账号（true/false，V41 旧 key）', 1, NOW(), NOW()),
('sso_user_not_exist_message',     '没有该账号，请联系管理员申请账号后再次访问',                        'sso', '关闭自动创建时，用户不存在时展示给前端的提示文案', 1, NOW(), NOW()),
('sso.client_id',                  'placeholder-client-id',                                          'sso', 'OIDC client_id（向统一认证平台运维获取）',       1, NOW(), NOW()),
('sso.client_secret',              'placeholder-client-secret',                                      'sso', 'OIDC client_secret',                            1, NOW(), NOW()),
('sso.authorization_uri',          'http://127.0.0.1:7200/usermanager/oauth/authorize',              'sso', 'OIDC 授权端点',                                 1, NOW(), NOW()),
('sso.token_uri',                  'http://127.0.0.1:7200/usermanager/oauth/token',                  'sso', 'OIDC Token 端点',                               1, NOW(), NOW()),
('sso.jwks_uri',                   'http://127.0.0.1:7200/usermanager/.well-known/jwks.json',        'sso', 'OIDC JWK Set 端点',                             1, NOW(), NOW()),
('sso.user_info_uri',              'http://127.0.0.1:7200/usermanager/usermanager/user/me',          'sso', 'OIDC userinfo 端点',                            1, NOW(), NOW()),
('sso.scope',                      'openid profile',                                                 'sso', 'OIDC scope',                                    1, NOW(), NOW()),
('sso.redirect_uri',               'http://localhost:18080/login/oauth2/code/sso',                   'sso', 'OIDC 回调 URI',                                 1, NOW(), NOW()),
('sso.frontend_base_url',          'http://localhost:28080',                                         'sso', '前端 base URL（生产用空值走 nginx 同源）',       1, NOW(), NOW()),
('sso.issuer',                     'http://127.0.0.1:7200/usermanager',                              'sso', 'OIDC issuer',                                   1, NOW(), NOW()),
('sso.auto_create_user',           'true',                                                           'sso', 'OIDC 首次登录自动创建本地账号（V42 统一开关）',   1, NOW(), NOW()),
('sso.sync.enabled',               'false',                                                          'sso', '是否启用定时从 IdP 同步用户/角色',               1, NOW(), NOW()),
('sso.sync.fixed_delay_ms',        '3600000',                                                        'sso', '定时同步间隔（毫秒，默认 1 小时）',              1, NOW(), NOW()),
('sso.sync.api_base_url',          '',                                                               'sso', 'IdP 同步 API base URL',                         1, NOW(), NOW()),
('sso.sync.api_token',             '',                                                               'sso', 'IdP 同步 API token',                            1, NOW(), NOW())
ON DUPLICATE KEY UPDATE id = id;
