-- One-time preparation for the local V51 database COPY after migration
-- V20260914113000 fails on duplicate (category_id, metric_code).
-- Run only against system_operations_metrics_collection, never the source DB.
USE system_operations_metrics_collection;
CREATE TEMPORARY TABLE local_duplicate_map AS
SELECT legacy.id AS legacy_id, current_item.id AS current_id
FROM metric_item legacy
JOIN metric_item current_item
  ON current_item.metric_code = legacy.metric_code
 AND current_item.category_id = legacy.category_id - 2
WHERE legacy.category_id IN (10, 11, 12, 13);

-- Preserve the removed metadata for inspection/recovery. The source database
-- and the pre-migration dump are also retained.
CREATE TABLE _local_legacy_metric_item_backup AS
SELECT item.* FROM metric_item item
JOIN local_duplicate_map map ON item.id = map.legacy_id;
CREATE TABLE _local_legacy_monitor_config_backup AS
SELECT config.* FROM metric_monitor_config config
JOIN local_duplicate_map map ON config.metric_id = map.legacy_id;

-- Before applying, verify that no other metric_id references point at legacy
-- IDs. On this workstation only these two tables reference the legacy IDs.
START TRANSACTION;
UPDATE metric_error_keywords keywords
JOIN local_duplicate_map map ON keywords.metric_id = map.legacy_id
SET keywords.metric_id = map.current_id;
DELETE config FROM metric_monitor_config config
JOIN local_duplicate_map map ON config.metric_id = map.legacy_id;
DELETE item FROM metric_item item
JOIN local_duplicate_map map ON item.id = map.legacy_id;
COMMIT;
DROP TEMPORARY TABLE local_duplicate_map;
