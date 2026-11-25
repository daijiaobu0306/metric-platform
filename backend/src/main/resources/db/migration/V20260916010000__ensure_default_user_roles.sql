-- 确保手工新增用户及 SSO 自动建用户所需的基础角色存在。
-- 该 seed 必须幂等，兼容以 V33 baseline 初始化、跳过 V40 的环境。
INSERT INTO sys_role (role_name, description, status)
VALUES ('普通用户', '默认角色，可访问基础功能', 1),
       ('管理员', '系统管理员', 1)
ON DUPLICATE KEY UPDATE id = id;
