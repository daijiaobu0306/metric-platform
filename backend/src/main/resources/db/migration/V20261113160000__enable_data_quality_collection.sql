-- 启用数据质量采集任务（用户 2026-11-13 确认：数据质量使用模拟数据）。
--
-- 背景：数据质量此前随合规一并暂缓，任务 enabled=0，数据汇聚/运营报表该大类为空。
-- 本次只把既有任务启用，协议、字段映射、路径、脚本版本均不变；
-- 模拟数据由独立生成器投递到 raw_data/import/data_quality，仍走正常采集链路入库，
-- 不新建模拟任务、不写默认阈值、不改指标规则。
-- 幂等：仅在任务仍停用且脚本版本未变时更新。

UPDATE collection_source
SET enabled = 1,
    last_error = NULL,
    next_scan_at = NOW(),
    config_json = JSON_SET(config_json, '$.enabled', true)
WHERE code = 'data_quality'
  AND enabled = 0
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) = 'data_quality-20261027-001';
