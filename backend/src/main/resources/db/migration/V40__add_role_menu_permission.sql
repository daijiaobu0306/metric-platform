-- V40: 新增角色/菜单/权限管理
--
-- 1. 创建 sys_role 角色表
-- 2. 创建 sys_user_role 用户-角色关联表
-- 3. 创建 sys_menu 菜单表
-- 4. 创建 sys_role_menu 角色-菜单权限表
-- 5. sys_user 表新增 is_admin 字段

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_name VARCHAR(50) NOT NULL UNIQUE COMMENT '角色名称',
    description VARCHAR(200) COMMENT '描述',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色表';

-- 用户-角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 菜单表
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    menu_name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    route_path VARCHAR(100) NOT NULL COMMENT '路由路径',
    icon VARCHAR(50) COMMENT '图标名称',
    sort_order INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统菜单表';

-- 角色-菜单权限表
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    UNIQUE KEY uk_role_menu (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单权限表';

-- 插入默认角色
INSERT INTO sys_role (role_name, description) VALUES ('普通用户', '默认角色，可访问基础功能');

-- 插入菜单
INSERT INTO sys_menu (menu_name, route_path, icon, sort_order) VALUES
('运营观测', '/observation', 'DataLine', 1),
('运营报表', '/report', 'DataBoard', 2),
('数据汇聚', '/data', 'Grid', 3),
('告警管理', '/alarm', 'Bell', 4),
('工单管理', '/gongdan', 'Document', 5),
('问题管理', '/issue', 'Warning', 6),
('指标体系配置', '/metric', 'TrendCharts', 7),
('变更审计', '/audit', 'Monitor', 8),
('专屏视图', '/dashboard-view', 'Monitor', 9),
('脚本技能库', '/script', 'Tools', 10),
('配置管理', '/config', 'Setting', 11),
('能力运营', '/ability-operation', 'Connection', 12);

-- 默认管理员关联普通用户角色
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 普通用户角色：默认拥有全部菜单权限
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;

-- sys_user 表新增 is_admin 字段
ALTER TABLE sys_user ADD COLUMN is_admin TINYINT DEFAULT 0 COMMENT '是否管理员: 0=否 1=是';
UPDATE sys_user SET is_admin = 1 WHERE username = 'admin';
