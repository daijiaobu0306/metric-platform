-- ============================================================
-- V54: 恢复 user_session 表
--
-- 背景（2026-09-08 staging 上线踩坑）：
--   V47 在 2026-07 P5 Bearer JWT 重构时 DROP TABLE user_session。
--   但是 Java 实体 / Repository / Security Filter 当时没同步清理，
--   dev 库因 docker-compose.dev.yml 强制 SPRING_JPA_HIBERNATE_DDL_AUTO=none
--   跳过了 schema 校验，掩盖了"代码引用已删表"的 drift。
--   staging 严格模式（validate-on-migrate=true）启动时报：
--     "Schema-validation: missing table [user_session]"
--
-- 与 V53 同样的根本解决思路：DB 端迁就实体代码（重建表），
-- 代价：表空着，业务也不再写入（鉴权已迁到 JWT）。
-- 表保留只为让 Hibernate validate 通过；后续 P5 重构未完成清理时可以渐进删。
--
-- 用 IF NOT EXISTS 保证幂等（虽然 DROP 后不可能存在，但防御性写法）。
-- ============================================================

CREATE TABLE IF NOT EXISTS user_session (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    session_key      VARCHAR(64)  NOT NULL COMMENT '前端生成的会话标识(sessionStorage)',
    username         VARCHAR(50)  NOT NULL DEFAULT '管理员' COMMENT '用户名',
    client_ip        VARCHAR(50)  DEFAULT '' COMMENT '客户端IP',
    user_agent       VARCHAR(500) DEFAULT '' COMMENT '完整 User-Agent',
    browser_name     VARCHAR(50)  DEFAULT '' COMMENT '浏览器名称',
    browser_version  VARCHAR(20)  DEFAULT '' COMMENT '浏览器版本',
    os_name          VARCHAR(50)  DEFAULT '' COMMENT '操作系统',
    device_type      VARCHAR(20)  DEFAULT 'desktop' COMMENT '设备类型: desktop/tablet/mobile',
    screen_width     INT          DEFAULT 0 COMMENT '屏幕宽度',
    screen_height    INT          DEFAULT 0 COMMENT '屏幕高度',
    login_time       DATETIME     NOT NULL COMMENT '登录时间',
    last_heartbeat   DATETIME     NOT NULL COMMENT '最后心跳时间',
    duration_minutes INT          DEFAULT 0 COMMENT '已登录时长(分钟)',
    is_active        INT          DEFAULT 1 COMMENT '是否活跃: 1=活跃 0=已结束',
    logout_time      DATETIME     DEFAULT NULL COMMENT '离开/结束时间',
    source           VARCHAR(50)  DEFAULT NULL COMMENT '会话来源: local / sso_oidc / sso_access_link',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_key (session_key),
    INDEX idx_login_time (login_time),
    INDEX idx_username (username),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户会话跟踪（保留兼容 Hibernate 实体）';
