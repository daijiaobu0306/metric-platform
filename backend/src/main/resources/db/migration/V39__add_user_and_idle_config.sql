-- V39: 新增用户表 + 会话空闲超时配置
--
-- 1. 创建 sys_user 用户表，支持用户名+密码登录
-- 2. 插入默认管理员账号（admin / admin123）
-- 3. 插入 session_idle_timeout 配置项（默认 20 分钟）

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密）',
    display_name VARCHAR(50) COMMENT '显示名称',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 默认管理员（密码: admin123）
INSERT INTO sys_user (username, password, display_name)
VALUES ('admin', '$2y$10$Q.AkQEnTdpVjm2bnKMHv6eNY14m0mOp8BIJ5WqtyBdE51UEmkF8ga', '管理员');

-- 会话空闲超时配置
INSERT INTO system_config (config_key, config_value, config_type, description, status)
VALUES ('session_idle_timeout', '20', 'session', '会话空闲超时时间（分钟），超过此时间无操作自动结束会话', 1);
