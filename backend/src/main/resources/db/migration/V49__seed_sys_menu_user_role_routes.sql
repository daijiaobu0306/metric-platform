-- ==============================================================
-- V49 — 补 sys_menu 里 /user /role 路由条目
--
-- 背景（2026-07-10）：
--   P5 v2026.07.12 重构时漏挂 /user /role 前端路由
--   + V48 修 SSO auto-create 顺带恢复了 router.js + Layout.vue 入口
--   但 SessionService.computePermissions 从 sys_menu 聚合 JWT permissions claim
--   因此普通用户（非 admin）的 JWT permissions 不含 /user 与 /role，
--   路由守卫虽然能用 isAdmin 短路放过管理员，但普通用户拿不到这两个菜单项
--
-- Fix:
--   插入 sys_menu 两行（id 12 和 id 13 已被 cap），sort_order 接续 12 之后
-- ==============================================================

INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '用户管理', '/user', 'User', 12, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/user');

INSERT INTO sys_menu (menu_name, route_path, icon, sort_order, status, create_time)
SELECT '角色管理', '/role', 'UserFilled', 13, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE route_path = '/role');
