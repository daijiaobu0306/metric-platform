-- ============================================================
-- V53: 把所有"状态/标志"列从 TINYINT 改为 INT
--
-- 背景（2026-09-08 staging 上线踩坑）：
--   V1/V10/V18/V39/V40 把状态列定义为 TINYINT，但 Java 实体里大量写成了
--   private Integer xxxStatus/xxxEnabled/xxxAnomaly。
--   本地 dev 因 docker-compose.dev.yml 强制 SPRING_JPA_HIBERNATE_DDL_AUTO=none
--   完全跳过了 schema 校验，所以这个 drift 一直隐藏。
--   staging 严格模式（validate-on-migrate=true）启动时直接报
--   "Schema-validation: wrong column type encountered ... found [tinyint],
--    but expecting [integer]" 导致 Spring 上下文初始化失败。
--
-- 修法选型：DB 端迁就实体代码（1 个 migration，零实体改动），原因是：
--   - 波及面广（22 列 / 14 表），改实体要改 50+ 文件 + 跑全套回归
--   - TINYINT vs INT 在 JDBC 层都是数字范围，业务语义无差
--   - 一致性更重要：未来 V54+ 不应再引入新 drift
--   - 容量代价：每行多 3 字节，10 万行 = 300KB，可忽略
--
-- 注意：MODIFY 在 MySQL 上是幂等的（多次执行结果相同），不需要 IF NOT EXISTS
-- ============================================================

-- alarm
ALTER TABLE alarm
    MODIFY process_status INT DEFAULT 0 COMMENT '处理状态: 0-未处理, 1-已处理',
    MODIFY shield_status  INT DEFAULT 0 COMMENT '屏蔽状态: 0-未屏蔽, 1-已屏蔽',
    MODIFY tag_status     INT DEFAULT 0 COMMENT '打标状态: 0-未打标, 1-已打标';

-- alarm_strategy
ALTER TABLE alarm_strategy
    MODIFY status INT DEFAULT 1 COMMENT '策略状态: 0-禁用, 1-启用';

-- gongdan
ALTER TABLE gongdan
    MODIFY closed_status INT DEFAULT 0 COMMENT '关单状态: 0-未关单, 1-已关单',
    MODIFY synced        INT DEFAULT 0 COMMENT '是否已同步';

-- issue
ALTER TABLE issue
    MODIFY tag_status INT DEFAULT 0 COMMENT '打标状态';

-- metric_category
ALTER TABLE metric_category
    MODIFY status INT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用';

-- metric_data
ALTER TABLE metric_data
    MODIFY is_anomaly INT DEFAULT 0 COMMENT '是否异常: 0-正常, 1-异常';

-- metric_item
ALTER TABLE metric_item
    MODIFY status INT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用';

-- plugin_config
ALTER TABLE plugin_config
    MODIFY enabled INT DEFAULT 1 COMMENT '启用状态';

-- sys_menu
ALTER TABLE sys_menu
    MODIFY status INT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用';

-- sys_role
ALTER TABLE sys_role
    MODIFY status INT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用';

-- sys_user
ALTER TABLE sys_user
    MODIFY is_admin INT DEFAULT 0 COMMENT '是否管理员: 0=否 1=是',
    MODIFY status   INT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用';

-- system_config
ALTER TABLE system_config
    MODIFY status INT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用';

-- system_info
ALTER TABLE system_info
    MODIFY status INT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用';
