-- 普通用户是首次进入平台时自动分配的基础角色，只开放安全的起始菜单。
-- 旧 V40 曾为它配置全部菜单；后续环境还可能因 baseline 缺失菜单种子。
INSERT INTO sys_role (role_name, description, status)
VALUES ('普通用户', '首次登录基础角色，仅可访问运营观测', 1)
ON DUPLICATE KEY UPDATE sys_role.id = sys_role.id;

INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status)
SELECT '运营观测', '/observation', 'DataLine', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/observation');

DELETE rm FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id AND r.role_name = '普通用户'
LEFT JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.id IS NULL OR m.route_path <> '/observation';

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m ON m.route_path = '/observation'
WHERE r.role_name = '普通用户'
ON DUPLICATE KEY UPDATE sys_role_menu.id = sys_role_menu.id;

UPDATE sys_role SET description = '首次登录基础角色，仅可访问运营观测'
WHERE role_name = '普通用户' AND (description IS NULL OR description <> '首次登录基础角色，仅可访问运营观测');
