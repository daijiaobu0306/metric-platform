-- 合并 CSV：取数脚本一轮只产出一份 CSV（dataset/data_time/_empty/payload），平台侧 reader 兼容该格式。
-- 重新绑定内部人员与运行效率采集任务到支持合并格式的新版本脚本；旧版本保持不变，可随时回退。
UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json, '$.worker.version', 'internal_staff-production-20261116-003', '$.enabled', TRUE),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) IN (
        'internal_staff-production-20261103-001',
        'internal_staff-production-20261112-001',
        'internal_staff-production-20261116-001',
        'internal_staff-production-20261116-002');

UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json, '$.worker.version', 'running_efficiency-production-20261116-002', '$.enabled', TRUE),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'running_efficiency'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) IN (
        'running_efficiency-production-20261103-001',
        'running_efficiency-production-20261112-001',
        'running_efficiency-production-20261116-001');
