-- 采集脚本版本重绑定：修复插件输出超过 10MB 导致整批不入库的问题。
--
-- 背景：reader.py 把每个指标的源行整份复制进输出 JSON（details + source_details），
-- 同一个数据集有多条指标规则时同一批行会被重复复制，输出体积随行数与列数成倍放大。
-- 生产 20261116_173138_8d7703329784_13.csv（后端应用异常报错数据集）输出超过协议 10MB 上限，
-- Worker 报 "Plugin output missing or exceeds 10MB"，该批次全部指标值不入库。
--
-- 新版本（internal_staff-production-20261116-002、running_efficiency-production-20261116-001）：
--   - 明细只保留前 100 行样本，真实行数用 detail_row_count / source_row_count 表达；
--   - 输出体积超过 8MB 时自动按一半缩减明细行数重算，指标值仍按全量行计算，不受影响。
--
-- 本迁移只切换脚本版本，不动指标规则、阈值与历史采集数据，幂等：
--   - 版本已是目标版本 → 命中 0 行
--   - 版本仍是旧值 → 切到目标版本，并确保 enabled=1、清除 last_error

UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json,
        '$.worker.version', 'internal_staff-production-20261116-002',
        '$.enabled', true),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version'))
      IN ('internal_staff-production-20261103-001',
          'internal_staff-production-20261112-001',
          'internal_staff-production-20261116-001');

UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json,
        '$.worker.version', 'running_efficiency-production-20261116-001',
        '$.enabled', true),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'running_efficiency'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version'))
      IN ('running_efficiency-production-20261103-001',
          'running_efficiency-production-20261112-001');
