-- V38: 修复指标值告警/工单的 category_name 字段
--
-- 问题根因：
--   AlertGenerateService.createMetricAlarm() 中使用 strategy.getTargetName() 作为 categoryName，
--   但对 target_type=metric 的策略，targetName 存的是指标名而非指标大类名。
--   导致 176 条指标值告警/工单的 category_name == metric_name（重复且错误）。
--
-- 修复：
--   通过 metric_item.category_id → metric_category.category_name 回填正确的指标大类名。
--   仅修复 category_name == metric_name 且 metric_id 不为空的记录（避免误改其他正常数据）。

UPDATE alarm a
  JOIN metric_item mi ON a.metric_id = mi.id
  JOIN metric_category mc ON mi.category_id = mc.id
SET a.category_name = mc.category_name
WHERE a.alarm_type IN ('指标值告警', '指标值工单')
  AND a.category_name = a.metric_name
  AND a.metric_id IS NOT NULL;
