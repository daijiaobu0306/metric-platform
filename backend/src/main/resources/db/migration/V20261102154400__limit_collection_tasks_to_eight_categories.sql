-- User-confirmed eight workbook categories exclude plan execution.
-- Remove only the identified unused out-of-scope/duplicate configurations, never their history.
START TRANSACTION;
SET @busy_sources=(SELECT COUNT(*) FROM collection_worker_run
  WHERE source_code IN ('plan_execution','metric_g1_mvt8vdiv_fa7b') AND state='CLAIMED' AND lease_until>NOW(6));
SET @unexpected_sources=(SELECT COUNT(*) FROM collection_source
  WHERE code IN ('plan_execution','metric_g1_mvt8vdiv_fa7b') AND (
    enabled<>0 OR NOT (JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.categoryCode')) <=>
      CASE code WHEN 'plan_execution' THEN 'metric_g4' ELSE 'metric_g1' END)));
SET @guard_sql=IF(@busy_sources=0 AND @unexpected_sources=0,'SELECT 1',
  'SELECT * FROM collection_scope_cleanup_requires_expected_paused_tasks');
PREPARE scope_guard FROM @guard_sql;
EXECUTE scope_guard;
DEALLOCATE PREPARE scope_guard;
DELETE FROM collection_source WHERE code IN ('plan_execution','metric_g1_mvt8vdiv_fa7b');
COMMIT;
