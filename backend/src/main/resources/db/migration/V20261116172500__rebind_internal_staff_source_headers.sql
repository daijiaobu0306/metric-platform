-- 修正对内部人员生产 CSV 采集的列名错误。
--
-- 背景：20261112-001 的 source_export.py 直接用 JDBC 的 columnLabel 当 CSV 表头。
-- 部分驱动（连接串带 useOldAliasMetadataBehavior=true，或旧版驱动）对"普通列 + 别名"
-- 返回原始列名 business_name / step_name / identify_character，只对表达式返回别名，
-- 于是 CSV 表头缺 `业务名称`，reader.py 报
--   Missing column 业务名称 for INNER_QUERY_PAGE_LOAD（其余 5 个 JDBC 数据集同理）。
--
-- 20261116-001 改为按 SQL 的 AS 别名生成表头（description 只用于核对列数），
-- 并保留 JDBC 字符集参数与问号列名检查。
--
-- 本迁移只切换脚本版本，不动指标规则、阈值与历史采集数据，幂等：
--   - 版本已是 20261116-001 → 命中 0 行
--   - 版本仍是旧值 → 切到 20261116-001，并确保 enabled=1

UPDATE collection_source
SET enabled = 1,
    config_json = JSON_SET(config_json,
        '$.worker.version', 'internal_staff-production-20261116-001',
        '$.enabled', true),
    last_error = NULL,
    next_scan_at = NOW()
WHERE code = 'internal_staff'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version'))
      IN ('internal_staff-production-20261103-001', 'internal_staff-production-20261112-001');
