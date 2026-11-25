-- Restore the existing internal_staff task to its pre-request release.
-- Paired with V20261112104804 to keep Flyway history valid while leaving platform behavior unchanged.
UPDATE collection_source
SET config_json=JSON_SET(config_json,'$.worker.version','internal_staff-production-20261103-001'),
    last_error=NULL
WHERE code='internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.version'))='internal_staff-production-20261112-001';
