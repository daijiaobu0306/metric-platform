-- User-confirmed boundary: an independent upstream generator only delivers files.
-- Reuse existing Python collection tasks; do not expose separate simulation tasks.
-- Preserve all batches, raw rows, run history, values, rules and archive files.
START TRANSACTION;
SET @existing_sources = (SELECT COUNT(*) FROM collection_source
  WHERE code IN ('business_value','external_customer','resource_input','running_efficiency','satisfaction_report')
    AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker'
    AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.format'))='csv'
    AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.categoryCode'))=CASE code
      WHEN 'business_value' THEN 'businessValue' WHEN 'external_customer' THEN 'external_customer'
      WHEN 'resource_input' THEN 'resourceInput' WHEN 'running_efficiency' THEN 'system_run'
      WHEN 'satisfaction_report' THEN 'satisfaction' END);
SET @active_duplicate_runs = (SELECT COUNT(*) FROM collection_worker_run
  WHERE source_code IN ('mock_business_value','mock_external_customer','mock_resource_input','mock_running_efficiency','mock_satisfaction_report')
    AND state='CLAIMED' AND lease_until>NOW(6));
SET @guard_sql=IF(@existing_sources=5 AND @active_duplicate_runs=0,'SELECT 1',
  'SELECT * FROM collection_task_consolidation_requires_existing_tasks_and_idle_workers');
PREPARE consolidation_guard FROM @guard_sql;
EXECUTE consolidation_guard;
DEALLOCATE PREPARE consolidation_guard;

UPDATE collection_source
SET config_json=JSON_SET(config_json,'$.filePattern','*.csv'),next_scan_at=NOW()
WHERE code IN ('business_value','external_customer','resource_input','running_efficiency','satisfaction_report');

DELETE FROM collection_source
WHERE code IN ('mock_business_value','mock_external_customer','mock_resource_input','mock_running_efficiency','mock_satisfaction_report');
COMMIT;
