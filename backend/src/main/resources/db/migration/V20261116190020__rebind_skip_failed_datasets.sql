-- 取数脚本容错：某个数据集的接口不通或查询失败时，只跳过该数据集，其余数据集照常产出合并 CSV；
-- 全部数据集都失败时脚本以非 0 退出并打印原因，不再出现「一个接口 400 导致整轮无文件」。
-- 重新绑定内部人员与运行效率采集任务到带容错的新版本脚本；旧版本保持不变，可随时回退。
UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json, '$.worker.version', 'internal_staff-production-20261116-004', '$.enabled', TRUE),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) IN (
        'internal_staff-production-20261103-001',
        'internal_staff-production-20261112-001',
        'internal_staff-production-20261116-001',
        'internal_staff-production-20261116-002',
        'internal_staff-production-20261116-003');

UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json, '$.worker.version', 'running_efficiency-production-20261116-003', '$.enabled', TRUE),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'running_efficiency'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) IN (
        'running_efficiency-production-20261103-001',
        'running_efficiency-production-20261112-001',
        'running_efficiency-production-20261116-001',
        'running_efficiency-production-20261116-002');
