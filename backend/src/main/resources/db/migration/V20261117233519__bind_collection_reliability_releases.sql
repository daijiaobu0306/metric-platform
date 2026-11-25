-- Only upgrade the known preceding releases. Preserve custom bindings, enablement,
-- paths, schedules, rules and historical data. Worker and releases ship together.
UPDATE collection_source SET config_json=JSON_SET(config_json,'$.worker.version','running_efficiency-production-20261117-001')
WHERE code='running_efficiency'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.version'))='running_efficiency-production-20261116-003';

UPDATE collection_source SET config_json=JSON_SET(config_json,'$.worker.version','internal_staff-production-20261117-003')
WHERE code='internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.version'))='internal_staff-production-20261117-002';
