-- 工单对接 BOMC 归档/撤销与重新推送内容比对：
--   pushed_title / pushed_content  上次推送 BOMC 时的标题、内容快照（重新推送前比对是否被编辑）
--   bomc_action / bomc_action_reason  撤销(QUASHED)/归档(ARCHIVED)标记与原因（本地打标，不删数据）
-- 历史迁移保持不变；MySQL 8 兼容幂等写法（同 V20261014143845）。

SET @gongdan_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'pushed_title'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN pushed_title VARCHAR(200) COMMENT ''上次推送BOMC的标题快照''');
PREPARE gongdan_stmt FROM @gongdan_ddl;
EXECUTE gongdan_stmt;
DEALLOCATE PREPARE gongdan_stmt;

SET @gongdan_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'pushed_content'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN pushed_content TEXT COMMENT ''上次推送BOMC的内容快照''');
PREPARE gongdan_stmt FROM @gongdan_ddl;
EXECUTE gongdan_stmt;
DEALLOCATE PREPARE gongdan_stmt;

SET @gongdan_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'bomc_action'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN bomc_action VARCHAR(20) COMMENT ''BOMC终结动作:QUASHED已撤销/ARCHIVED已归档''');
PREPARE gongdan_stmt FROM @gongdan_ddl;
EXECUTE gongdan_stmt;
DEALLOCATE PREPARE gongdan_stmt;

SET @gongdan_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'gongdan' AND column_name = 'bomc_action_reason'), 'SELECT 1', 'ALTER TABLE gongdan ADD COLUMN bomc_action_reason VARCHAR(500) COMMENT ''撤销/归档原因(opin)''');
PREPARE gongdan_stmt FROM @gongdan_ddl;
EXECUTE gongdan_stmt;
DEALLOCATE PREPARE gongdan_stmt;
