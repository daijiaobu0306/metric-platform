-- V41: SSO 访问链接支持自动创建用户
--
-- 1. 开关：sso_auto_create_user（true/false），关闭时维持原 404 行为
-- 2. 文案：sso_user_not_exist_message，关闭时用户不存在返给前端的提示文案
--
-- 配套后端：SsoAccessLinkController 在用户不存在分支读取这两个配置

INSERT INTO system_config (config_key, config_value, config_type, description, status, create_time, update_time) VALUES
('sso_auto_create_user', 'false', 'sso', 'SSO 访问链接是否在用户不存在时自动创建账号（true/false）', 1, NOW(), NOW()),
('sso_user_not_exist_message', '没有该账号，请联系管理员申请账号后再次访问', 'sso', '关闭自动创建时，用户不存在时展示给前端的提示文案', 1, NOW(), NOW());
