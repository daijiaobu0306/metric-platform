-- =============================================================================
-- 健康度算法改造 P1 修正：metric_health_rule 的布尔/状态列由 TINYINT 改为 INT
--
-- 原因：MySQL Connector/J 默认把 TINYINT(1) 映射成 JDBC 的 BIT，Hibernate 6 的
-- schema-validation 期望 INTEGER，启动时报
--   "wrong column type encountered in column [exempt] ... found [bit], expecting [integer]"。
-- 规则表只是配置表、数据量极小，用 INT 语义更直观（0/1），也避免依赖 JDBC 参数
-- tinyInt1isBit 这类环境差异。
-- =============================================================================

ALTER TABLE metric_health_rule
  MODIFY COLUMN exempt INT NOT NULL DEFAULT 0 COMMENT '1=白名单不计分，不参与任何层聚合',
  MODIFY COLUMN status INT NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用';
