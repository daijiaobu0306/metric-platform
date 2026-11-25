-- Rebind the existing internal_staff task to its immutable new-environment CSV/Python release.
-- Preserve task enabled state, schedule, import/archive paths, thresholds, history and all other settings.
UPDATE collection_source
SET config_json=JSON_SET(config_json,'$.worker.version','internal_staff-production-20261112-001'),
    last_error=NULL
WHERE code='internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.version'))='internal_staff-production-20261103-001';
