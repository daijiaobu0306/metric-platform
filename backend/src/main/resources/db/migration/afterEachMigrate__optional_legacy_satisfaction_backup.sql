-- TEMPORARY ensures an existing real backup table can never be removed.
SET @satisfaction_optional_backup_sql = IF(@satisfaction_optional_backup_created,
  'DROP TEMPORARY TABLE IF EXISTS _local_legacy_monitor_config_backup', 'DO 0');
PREPARE satisfaction_optional_backup FROM @satisfaction_optional_backup_sql;
EXECUTE satisfaction_optional_backup;
DEALLOCATE PREPARE satisfaction_optional_backup;
SET @satisfaction_optional_backup_created = FALSE;
