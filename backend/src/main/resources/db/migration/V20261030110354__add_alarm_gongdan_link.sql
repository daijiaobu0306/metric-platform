-- Preserve historical alarms; only manual conversions populate this association.
SET @present = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alarm' AND COLUMN_NAME = 'related_gongdan_id');
SET @ddl = IF(@present = 0, 'ALTER TABLE `alarm` ADD COLUMN `related_gongdan_id` BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
