-- ============================================================
-- V44 一次性修复 Flyway baseline=33 导致的 schema 漂移
-- ------------------------------------------------------------
-- 背景：dev DB 的生产现状对齐 V33（详见 application.yml 的
--   flyway.baseline-version=33）。当 Flyway 首次启用时，
--   V1~V32 被 baseline 标记为「已应用」但 ALTER TABLE 语句
--   从未真正执行，导致：
--     - metric_item 缺 V3（sub_category_*）、V11（calc_*）的 6 列
--     - issue 缺 V32（sub_category_name）+ 实体定义的 5 列
--     - gongdan 缺 V18（external_id/external_status/synced/synced_time）+ V27（strategy_id）
--   结果：JPA 查询这些实体全部 500，前端页面随之报错。
--
-- 设计要点：使用 information_schema 条件判断，幂等。
--   - dev DB 不存在 → 添加
--   - production DB 已有 → 跳过
--   同一份 SQL 在两种环境都不会报错。
-- ============================================================

SET @db = DATABASE();

-- ----------------------------------------------------------
-- 1) metric_item: 补 V3 (sub_category_*) + V11 (calc_*)
-- ----------------------------------------------------------

-- sub_category_name (V3)
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='metric_item' AND COLUMN_NAME='sub_category_name');
SET @s = IF(@c=0, 'ALTER TABLE metric_item ADD COLUMN sub_category_name VARCHAR(100) COMMENT ''指标小类名称'' AFTER category_id', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- sub_category_code (V3)
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='metric_item' AND COLUMN_NAME='sub_category_code');
SET @s = IF(@c=0, 'ALTER TABLE metric_item ADD COLUMN sub_category_code VARCHAR(50) COMMENT ''指标小类编码'' AFTER sub_category_name', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- idx_sub_category_code (V3)
SET @i = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='metric_item' AND INDEX_NAME='idx_sub_category_code');
SET @s = IF(@i=0, 'ALTER TABLE metric_item ADD INDEX idx_sub_category_code (sub_category_code)', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- calc_rule_type (V11)
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='metric_item' AND COLUMN_NAME='calc_rule_type');
SET @s = IF(@c=0, 'ALTER TABLE metric_item ADD COLUMN calc_rule_type VARCHAR(50) COMMENT ''规则类型: count_gt, count_lt, fixed_calc, distinct_count, keyword_match''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- calc_source_table (V11)
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='metric_item' AND COLUMN_NAME='calc_source_table');
SET @s = IF(@c=0, 'ALTER TABLE metric_item ADD COLUMN calc_source_table VARCHAR(100) COMMENT ''原始数据表名''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- calc_field (V11)
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='metric_item' AND COLUMN_NAME='calc_field');
SET @s = IF(@c=0, 'ALTER TABLE metric_item ADD COLUMN calc_field VARCHAR(100) COMMENT ''计算字段''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- calc_extra (V11)
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='metric_item' AND COLUMN_NAME='calc_extra');
SET @s = IF(@c=0, 'ALTER TABLE metric_item ADD COLUMN calc_extra VARCHAR(500) COMMENT ''额外参数(JSON)''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------
-- 2) issue: 补 V32 (sub_category_name) + 实体定义的 5 列
-- ----------------------------------------------------------

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='issue' AND COLUMN_NAME='sub_category_name');
SET @s = IF(@c=0, 'ALTER TABLE issue ADD COLUMN sub_category_name VARCHAR(100) DEFAULT NULL COMMENT ''指标小类名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='issue' AND COLUMN_NAME='system_name');
SET @s = IF(@c=0, 'ALTER TABLE issue ADD COLUMN system_name VARCHAR(100) DEFAULT NULL COMMENT ''系统名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='issue' AND COLUMN_NAME='category_name');
SET @s = IF(@c=0, 'ALTER TABLE issue ADD COLUMN category_name VARCHAR(100) DEFAULT NULL COMMENT ''指标大类名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='issue' AND COLUMN_NAME='metric_name');
SET @s = IF(@c=0, 'ALTER TABLE issue ADD COLUMN metric_name VARCHAR(100) DEFAULT NULL COMMENT ''指标名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='issue' AND COLUMN_NAME='attachment_name');
SET @s = IF(@c=0, 'ALTER TABLE issue ADD COLUMN attachment_name VARCHAR(255) DEFAULT NULL COMMENT ''附件原始文件名''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='issue' AND COLUMN_NAME='attachment_path');
SET @s = IF(@c=0, 'ALTER TABLE issue ADD COLUMN attachment_path VARCHAR(500) DEFAULT NULL COMMENT ''附件存储路径''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------
-- 3) gongdan: 补 V18 (external_*/synced*) + V27 (strategy_id)
-- ----------------------------------------------------------

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='gongdan' AND COLUMN_NAME='external_id');
SET @s = IF(@c=0, 'ALTER TABLE gongdan ADD COLUMN external_id VARCHAR(100) COMMENT ''外部系统工单ID''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='gongdan' AND COLUMN_NAME='external_status');
SET @s = IF(@c=0, 'ALTER TABLE gongdan ADD COLUMN external_status VARCHAR(50) COMMENT ''外部工单状态''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='gongdan' AND COLUMN_NAME='synced');
SET @s = IF(@c=0, 'ALTER TABLE gongdan ADD COLUMN synced TINYINT DEFAULT 0 COMMENT ''是否已同步''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='gongdan' AND COLUMN_NAME='synced_time');
SET @s = IF(@c=0, 'ALTER TABLE gongdan ADD COLUMN synced_time DATETIME COMMENT ''同步时间''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='gongdan' AND COLUMN_NAME='strategy_id');
SET @s = IF(@c=0, 'ALTER TABLE gongdan ADD COLUMN strategy_id BIGINT COMMENT ''关联告警策略ID''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;