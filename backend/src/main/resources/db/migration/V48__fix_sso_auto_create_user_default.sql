-- ==============================================================
-- V48 — 修 SSO 首次登录自动创建账号开关
--
-- 背景（2026-07-10 修复）：
--   V41 老 SSO 接入链路写入 config_key='sso_auto_create_user'='false'（无点）
--   V42 OIDC SSO 重构写入 config_key='sso.auto_create_user'='true'（有点）
--   SsoUserUpsertService.SSO_AUTO_CREATE_USER_KEY 是无点的老 key，
--   导致读到 V41 的 'false'，首次 SSO 登录被误判为不允许自动创建账号，
--   抛 "首次登录但 sso.auto_create_user=false，请联系管理员开通账号"。
--
-- 修复：
--   1. Java 侧：SSO_AUTO_CREATE_USER_KEY 改为带点新 key；老 key 作为 alias 兜底读取
--   2. SQL 侧（本文件）：保证 sso.auto_create_user 行存在且 value='true'
--                       老 key 行原样保留，仅在 log 里标记已废弃
-- ==============================================================

-- 新规范：sso.auto_create_user 强制为 'true'（V42 写过，再插一遍兜底）
INSERT INTO system_config (config_key, config_value, config_type, description, status, create_time, update_time)
SELECT 'sso.auto_create_user', 'true', 'sso',
       'OIDC 首次登录自动创建本地账号（默认 true；V48 把 V41 老 key sso_auto_create_user 标记为 deprecated，仅保留兼容 alias）',
       1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_config WHERE config_key = 'sso.auto_create_user');

UPDATE system_config
SET config_value = 'true', update_time = NOW()
WHERE config_key = 'sso.auto_create_user' AND config_value <> 'true';

-- 写一行审计 anchor：明确老 key 已废弃，迁完即可删
INSERT INTO system_config (config_key, config_value, config_type, description, status, create_time, update_time)
SELECT 'sso.auto_create_user_legacy.status', 'deprecated-but-kept', 'sso',
       'V41 老 SSO 接入链路的同名 config_key=sso_auto_create_user 已废弃。SsoUserUpsertService 仅作为 alias 兜底读取使用，不应再写入。请勿手工修改。',
       1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_config WHERE config_key = 'sso.auto_create_user_legacy.status');
