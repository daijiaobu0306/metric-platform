-- Keep the already-applied V20261015154727 immutable. Its local-only backup
-- contributes no reserved IDs when absent. Supply that empty input on this
-- migration connection only; never create or modify a persistent backup table.
SET @satisfaction_optional_backup_created =
  NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='20261015154727' AND success=1)
  AND NOT EXISTS (SELECT 1 FROM information_schema.tables
                  WHERE table_schema=DATABASE() AND table_name='_local_legacy_monitor_config_backup');
SET @satisfaction_optional_backup_sql = IF(@satisfaction_optional_backup_created,
  'CREATE TEMPORARY TABLE IF NOT EXISTS _local_legacy_monitor_config_backup (metric_id BIGINT)',
  'DO 0');
PREPARE satisfaction_optional_backup FROM @satisfaction_optional_backup_sql;
EXECUTE satisfaction_optional_backup;
DEALLOCATE PREPARE satisfaction_optional_backup;
