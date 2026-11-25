-- Preserve operator scheduling; only bind the daily standalone source-compatible parser.
UPDATE collection_source SET config_json=JSON_SET(config_json,'$.worker.version','internal_staff-production-20261117-002') WHERE code='internal_staff';
