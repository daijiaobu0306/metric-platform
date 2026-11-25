-- Retire editable metric calculation metadata. File collection remains keyed by metric code.
-- Preserve configured satisfaction alarm policies in the existing configuration store.
-- No thresholds, metric values, collection rows or health weights are changed.
SET @metric_policy_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.columns
          WHERE table_schema=DATABASE() AND table_name='metric_item' AND column_name='calc_extra'),
  'INSERT INTO system_config (config_key,config_value,config_type,description,status)
SELECT CONCAT(''satisfaction.alarm.policy.'',metric_code),
       JSON_MERGE_PATCH(JSON_OBJECT(),
         IF(JSON_CONTAINS_PATH(calc_extra,''one'',''$.monthlyThreshold''),JSON_OBJECT(''monthlyThreshold'',JSON_EXTRACT(calc_extra,''$.monthlyThreshold'')),JSON_OBJECT()),
         IF(JSON_CONTAINS_PATH(calc_extra,''one'',''$.edgeThreshold''),JSON_OBJECT(''edgeThreshold'',JSON_EXTRACT(calc_extra,''$.edgeThreshold'')),JSON_OBJECT()),
         IF(JSON_CONTAINS_PATH(calc_extra,''one'',''$.workdayOnly''),JSON_OBJECT(''workdayOnly'',JSON_EXTRACT(calc_extra,''$.workdayOnly'')),JSON_OBJECT())),
       ''alarm'',''满意度分段、月累计及工作日告警规则'',1
FROM metric_item
WHERE data_source=''satisfaction_report'' AND JSON_VALID(calc_extra)
  AND JSON_CONTAINS_PATH(calc_extra,''one'',''$.monthlyThreshold'',''$.edgeThreshold'',''$.workdayOnly'')
ON DUPLICATE KEY UPDATE config_key=VALUES(config_key)', 'DO 0');
PREPARE metric_policy_statement FROM @metric_policy_sql;
EXECUTE metric_policy_statement;
DEALLOCATE PREPARE metric_policy_statement;

SET @metric_column_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.columns
          WHERE table_schema=DATABASE() AND table_name='metric_item' AND column_name='calc_rule_type'),
  'ALTER TABLE metric_item DROP COLUMN calc_rule_type', 'DO 0');
PREPARE metric_column_statement FROM @metric_column_sql;
EXECUTE metric_column_statement;
DEALLOCATE PREPARE metric_column_statement;

SET @metric_column_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.columns
          WHERE table_schema=DATABASE() AND table_name='metric_item' AND column_name='calc_source_table'),
  'ALTER TABLE metric_item DROP COLUMN calc_source_table', 'DO 0');
PREPARE metric_column_statement FROM @metric_column_sql;
EXECUTE metric_column_statement;
DEALLOCATE PREPARE metric_column_statement;

SET @metric_column_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.columns
          WHERE table_schema=DATABASE() AND table_name='metric_item' AND column_name='calc_field'),
  'ALTER TABLE metric_item DROP COLUMN calc_field', 'DO 0');
PREPARE metric_column_statement FROM @metric_column_sql;
EXECUTE metric_column_statement;
DEALLOCATE PREPARE metric_column_statement;

SET @metric_column_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.columns
          WHERE table_schema=DATABASE() AND table_name='metric_item' AND column_name='calc_extra'),
  'ALTER TABLE metric_item DROP COLUMN calc_extra', 'DO 0');
PREPARE metric_column_statement FROM @metric_column_sql;
EXECUTE metric_column_statement;
DEALLOCATE PREPARE metric_column_statement;

