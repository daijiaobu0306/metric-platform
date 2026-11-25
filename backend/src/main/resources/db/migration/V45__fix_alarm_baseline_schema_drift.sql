-- ============================================================
-- V45 一次性修复 alarm 表的 baseline schema 漂移
-- ------------------------------------------------------------
-- 背景：同 V44，dev DB 的 Flyway baseline=33 导致 V15/V23/V24/
--   V29/V31_1 等迁移对 alarm 表的 ALTER 未实际执行。
-- 结果：Alarm 实体声明的 15 列（business_name、source_*
--   step_name 等）在 dev DB 中缺失，每次告警列表查询都 500。
--
-- 设计：使用 information_schema 条件判断，幂等。
--   production 已有则跳过，dev 缺失则补齐。
-- ============================================================

SET @db = DATABASE();

-- V31_1
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='related_issue_id');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN related_issue_id BIGINT COMMENT ''关联的问题ID''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V15
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='metric_name');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN metric_name VARCHAR(200) DEFAULT NULL', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V37 顺带把 category_name 升级为 VARCHAR(200)
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='category_name');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN category_name VARCHAR(200) DEFAULT NULL COMMENT ''指标大类名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V23
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='shield_time');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN shield_time DATETIME COMMENT ''屏蔽开始时间''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V24
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='strategy_id');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN strategy_id BIGINT COMMENT ''关联告警策略ID''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND INDEX_NAME='idx_strategy_id');
SET @s = IF(@i=0, 'ALTER TABLE alarm ADD INDEX idx_strategy_id (strategy_id)', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V29
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='shield_unshield_time');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN shield_unshield_time DATETIME COMMENT ''屏蔽解除时间''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='shield_unshield_type');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN shield_unshield_type VARCHAR(20) COMMENT ''解除方式: AUTO(到期自动), MANUAL(手工解除)''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 实体新增字段（原始数据维度）
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='source_table');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN source_table VARCHAR(50) COMMENT ''原始数据表名''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='source_id');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN source_id BIGINT COMMENT ''原始数据记录ID''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='sub_category_name');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN sub_category_name VARCHAR(100) COMMENT ''指标小类名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='business_name');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN business_name VARCHAR(200) COMMENT ''业务名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='step_name');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN step_name VARCHAR(200) COMMENT ''步骤名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='unit');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN unit VARCHAR(20) COMMENT ''指标单位''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='metric_definition');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN metric_definition VARCHAR(500) COMMENT ''指标定义''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alarm' AND COLUMN_NAME='system_name');
SET @s = IF(@c=0, 'ALTER TABLE alarm ADD COLUMN system_name VARCHAR(100) COMMENT ''系统名称''', 'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;