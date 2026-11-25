-- ============================================================
-- V55: 兜底 seed 默认 sys_role 角色
--
-- 背景（2026-09-09 staging silent SSO 上线踩坑）：
--   Flyway baseline = V33，V34-V40 全部跳过。但 staging 库 sys_role 表是从
--   生产 dump 导入的（结构存在、数据空）—— V40 那条
--     INSERT INTO sys_role VALUES ('普通用户', '默认角色，可访问基础功能')
--   没执行。结果 SSO callback 走到业务层创建用户时报：
--     "sys_role '普通用户' 不存在，请确认 V40 迁移已执行"
--
-- 与 V54 同样思路：DB 端兜底（补 seed），让业务代码不报错。
-- 用 INSERT IGNORE 保证幂等（环境已有角色不覆盖）。
-- ============================================================

INSERT IGNORE INTO sys_role (role_name, description, status) VALUES
    ('普通用户', '默认角色，可访问基础功能', 1),
    ('管理员',   '系统管理员',                1);
