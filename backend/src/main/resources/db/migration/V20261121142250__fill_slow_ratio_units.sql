-- ============================================================================
-- 补齐最后 4 条“慢访问占比”类指标的单位：% 
--
-- 前两版迁移因梳理表阈值写作小数（0.05 / 0.1）暂未回填，现按采集口径确认：
--   collection-scripts/releases/running_efficiency-production 的 config.json 字段定义：
--     SR_PG_ACCESS_SLOW_RATIO  -> 慢访问占比（%）
--     SR_API_SLOW_RATIO       -> 占比(%)
--     SR_SQL_SLOW_RATIO       -> 占比(%)
--     SR_REQ_ACCESS_SLOW_RATIO-> 慢请求访问量占比（同数据族，口径一致）
--   原始数据样例（系统运行-系统性能-数据汇聚-原始数据-20260328.xlsx）
--   “大于3s的页面访问量占比”表取值 0.19，即 0.19%，说明值本身是百分数。
-- 因此单位为 %，与梳理表阈值 0.1（=0.1%）一致，无需换算。
--
-- 范围：仅这 4 条，且只在单位为空时更新（幂等，不覆盖人工修改）。
-- 不改：阈值、比较符、告警策略、采集配置、历史数据。
-- ============================================================================

START TRANSACTION;

UPDATE metric_item
   SET unit = '%', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'SR_API_SLOW_RATIO',
    'SR_PG_ACCESS_SLOW_RATIO',
    'SR_REQ_ACCESS_SLOW_RATIO',
    'SR_SQL_SLOW_RATIO');

COMMIT;
