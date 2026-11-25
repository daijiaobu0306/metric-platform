-- 修正对内部人员的脚本版本绑定。
--
-- 背景：V20261116104000 的 UPDATE 带了 `AND enabled = 0` 条件，意图是“仅在任务停用时启用并切版本”。
-- 但部分环境里该任务已被人工启用（enabled=1），条件不成立 → 整条语句命中 0 行，
-- 结果 enabled 是 1、版本却仍停在 internal_staff-production-20261103-001。
--
-- 本迁移不再依赖 enabled 状态，只按“版本仍是旧值”判断，幂等：
--   - 版本已是 20261112-001 → 命中 0 行，无副作用
--   - 版本仍是 20261103-001 → 切到 20261112-001，并确保 enabled=1
--
-- 只改采集任务配置，不动指标规则、阈值与历史采集数据。

UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json,
        '$.worker.version', 'internal_staff-production-20261112-001',
        '$.enabled', true),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) = 'internal_staff-production-20261103-001';
