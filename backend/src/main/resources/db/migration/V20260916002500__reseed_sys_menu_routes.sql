-- 兜底补齐菜单路由。
-- 某些环境以 baseline 导入后，V40 的菜单 seed 可能被跳过，但管理员权限
-- 仍依赖 sys_menu 中的启用路由集合。

INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '运营报表', '/report', 'DataBoard', 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/report');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '数据汇聚', '/data', 'Grid', 3, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/data');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '告警管理', '/alarm', 'Bell', 4, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/alarm');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '工单管理', '/gongdan', 'Document', 5, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/gongdan');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '问题管理', '/issue', 'Warning', 6, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/issue');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '指标体系配置', '/metric', 'TrendCharts', 7, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/metric');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '变更审计', '/audit', 'Monitor', 8, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/audit');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '专屏视图', '/dashboard-view', 'Monitor', 9, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/dashboard-view');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '脚本技能库', '/script', 'Tools', 10, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/script');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '配置管理', '/config', 'Setting', 11, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/config');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '能力运营', '/ability-operation', 'Connection', 12, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/ability-operation');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '用户管理', '/user', 'User', 13, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/user');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '角色管理', '/role', 'UserFilled', 14, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/role');
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '仪表盘', '/dashboard', 'DataLine', 15, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/dashboard');
