-- Upgrade the known release only; preserve task configuration and custom versions.
UPDATE collection_source SET config_json=JSON_SET(config_json,'$.worker.version','internal_staff-production-20261118-001')
WHERE code='internal_staff' AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.version'))='internal_staff-production-20261117-003';
