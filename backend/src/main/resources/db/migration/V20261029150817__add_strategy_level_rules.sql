-- Additive only; existing thresholds and collected values are unchanged.
SET @present = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alarm_strategy' AND COLUMN_NAME = 'alarm_rules');
SET @ddl = IF(@present = 0, 'ALTER TABLE `alarm_strategy` ADD COLUMN `alarm_rules` TEXT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @present = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alarm_strategy' AND COLUMN_NAME = 'gongdan_rules');
SET @ddl = IF(@present = 0, 'ALTER TABLE `alarm_strategy` ADD COLUMN `gongdan_rules` TEXT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @present = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alarm' AND COLUMN_NAME = 'alarm_level');
SET @ddl = IF(@present = 0, 'ALTER TABLE `alarm` ADD COLUMN `alarm_level` INT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

