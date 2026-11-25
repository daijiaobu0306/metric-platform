-- 绑定「对内部人员」「运行效率」到 2026-11-16 新环境发行版，并启用对内部人员采集。
--
-- 依据：用户 2026-11-16 提供 internal_staff_CSV_new_environment_20261116.zip /
--       running_efficiency_CSV_new_environment_20261116.zip 两套发行版，要求部署到生产并升级平台。
--       两个发行版已发布进 collection-scripts/releases 并登记 catalog。
--
-- 只改采集任务的脚本版本与启用状态：
--   - internal_staff：internal_staff-production-20261103-001 -> internal_staff-production-20261112-001，并置 enabled=1
--   - running_efficiency：running_efficiency-production-20261103-001 -> running_efficiency-production-20261112-001
-- 不改指标规则、不改告警阈值、不动 files 采集目录和历史采集数据。
-- 扫描间隔保持原值（intervalSeconds=300）。新版本含 16 个来源（14 个 AnalyticDB + 2 个 BPM），
-- 若生产上单轮取数接近 300 秒超时上限，可单独把间隔调大，例如：
--   UPDATE collection_source SET config_json=JSON_SET(config_json,'$.intervalSeconds',3600) WHERE code='running_efficiency';
--
-- 幂等：仅在版本仍是旧值 / 任务仍停用时更新。

UPDATE collection_source
SET config_json = JSON_SET(config_json,
        '$.worker.version', 'running_efficiency-production-20261112-001',
        '$.enabled', true),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'running_efficiency'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) = 'running_efficiency-production-20261103-001';

UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json,
        '$.worker.version', 'internal_staff-production-20261112-001',
        '$.enabled', true),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'internal_staff'
  AND enabled = 0;
