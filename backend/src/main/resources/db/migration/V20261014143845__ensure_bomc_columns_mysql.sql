-- MySQL-compatible additive repair for BOMC fields. Historical migrations remain unchanged.
-- Safe when the fields already exist (e.g. an environment that ran the old migration).

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'alarm_level'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN alarm_level INT');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'alarm_priority'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN alarm_priority VARCHAR(20)');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'alarm_impact'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN alarm_impact VARCHAR(20)');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'alarm_object'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN alarm_object VARCHAR(200)');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'alarm_first_time'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN alarm_first_time DATETIME');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'alarm_last_time'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN alarm_last_time DATETIME');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'alarm_count'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN alarm_count INT');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'responsible_person'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN responsible_person VARCHAR(100)');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'second_responsible'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN second_responsible VARCHAR(100)');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'execute_user'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN execute_user VARCHAR(50)');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'external_sync_msg'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN external_sync_msg VARCHAR(500)');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;

SET @bomc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'last_query_time'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN last_query_time DATETIME');
PREPARE bomc_stmt FROM @bomc_ddl;
EXECUTE bomc_stmt;
DEALLOCATE PREPARE bomc_stmt;
