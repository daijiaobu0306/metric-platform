-- V20260914130000: 阶段 3 采集层骨架 — seed 12 行 metric_import_config
--
-- 6 个新 ScanScheduler 各自对应的 sub_category_code：
--   1. ExternalCustomerScheduler (payActive / trafficAlert)
--   2. ComplaintScheduler (cityComplaint)
--   3. ServiceComplaintScheduler (serviceComplaint)
--   4. SatisfactionScheduler (innerSatisfaction)
--   5. DemandScheduler (demandDev)
--   6. DqScheduler (dataVolatility / businessIndicatorAccuracy / fieldAccuracy /
--                  enumValueAbnormalTrigger / crossSystemDataTimeliness / dataConsistency)
--
-- 业务价值 2 个 sub_category（operationEffect / reportBoard）走内部聚合（MetricMonitorService
-- 阶段 2 已实现），不需要文件通道。
--
-- 幂等：使用 ON DUPLICATE KEY UPDATE（metric_import_config UNIQUE on sub_category_code）。

INSERT INTO metric_import_config
  (sub_category_code, import_path, archive_path, scan_interval_minutes, file_pattern, enabled)
VALUES
  ('payActive',                  'raw_data/import/payActive/',                 'raw_data/archive/payActive/',                 5, '*.xlsx,*.csv', 1),
  ('trafficAlert',               'raw_data/import/trafficAlert/',              'raw_data/archive/trafficAlert/',              5, '*.xlsx,*.csv', 1),
  ('cityComplaint',              'raw_data/import/cityComplaint/',             'raw_data/archive/cityComplaint/',             5, '*.xlsx,*.csv', 1),
  ('serviceComplaint',           'raw_data/import/serviceComplaint/',          'raw_data/archive/serviceComplaint/',          5, '*.xlsx,*.csv', 1),
  ('innerSatisfaction',          'raw_data/import/innerSatisfaction/',         'raw_data/archive/innerSatisfaction/',         5, '*.xlsx,*.csv', 1),
  ('demandDev',                  'raw_data/import/demandDev/',                 'raw_data/archive/demandDev/',                 5, '*.xlsx,*.csv', 1),
  ('dataVolatility',             'raw_data/import/dataVolatility/',            'raw_data/archive/dataVolatility/',            5, '*.xlsx,*.csv', 1),
  ('businessIndicatorAccuracy',  'raw_data/import/businessIndicatorAccuracy/', 'raw_data/archive/businessIndicatorAccuracy/', 5, '*.xlsx,*.csv', 1),
  ('fieldAccuracy',              'raw_data/import/fieldAccuracy/',             'raw_data/archive/fieldAccuracy/',             5, '*.xlsx,*.csv', 1),
  ('enumValueAbnormalTrigger',   'raw_data/import/enumValueAbnormalTrigger/',  'raw_data/archive/enumValueAbnormalTrigger/',  5, '*.xlsx,*.csv', 1),
  ('crossSystemDataTimeliness',  'raw_data/import/crossSystemDataTimeliness/', 'raw_data/archive/crossSystemDataTimeliness/', 5, '*.xlsx,*.csv', 1),
  ('dataConsistency',            'raw_data/import/dataConsistency/',           'raw_data/archive/dataConsistency/',           5, '*.xlsx,*.csv', 1)
ON DUPLICATE KEY UPDATE
  import_path = VALUES(import_path),
  archive_path = VALUES(archive_path),
  scan_interval_minutes = VALUES(scan_interval_minutes),
  file_pattern = VALUES(file_pattern),
  enabled = VALUES(enabled);
