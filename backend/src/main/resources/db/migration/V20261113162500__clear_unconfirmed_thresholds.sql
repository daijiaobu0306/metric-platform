-- 清空尚未确认的告警/工单阈值（用户 2026-11-13 明确：阈值还未确定，先不设置）。
--
-- 范围：数据质量（dataQuality）与合规管理（metric_g1）。
--   - 数据质量：6 条策略的 95/< 、20/> 、10000/> 及工单阈值 90/< 均为历史种子数据，
--     梳理表未提供，非业务确认值；模拟数据落在 92~97，会按 >20 误判为异常。
--   - 合规管理：104 条策略阈值全为 0、条件 >，是 2026-10-19 批量写入的占位值，
--     导致任何非零指标都告警（实测 94 条）。
-- 平台口径：未提供阈值即视为“未配置”，对应页面显示未配置、不参与告警判定。
-- 实现依据：AlarmStrategyQueryService 仅在 alarmEnabled=1 且 alarmThreshold 非空时
-- 才纳入判定；工单同理需 gongdanEnabled=1 且 gongdanThreshold 非空。
--
-- 只清空阈值与开关，不动指标定义、不改条件字符串、不删历史告警记录。
-- 幂等：仅更新仍存在阈值的行；后续确认阈值后正常录入即可恢复。
-- 其他大类阈值保持不变。

UPDATE alarm_strategy s
JOIN metric_item m ON m.id = s.target_id
JOIN metric_category c ON c.id = m.category_id
SET s.alarm_threshold = NULL,
    s.alarm_enabled = 0,
    s.gongdan_threshold = NULL,
    s.gongdan_enabled = 0,
    s.threshold_value = NULL,
    s.update_time = NOW(6)
WHERE s.target_type = 'metric'
  AND s.strategy_type = 'metric'
  AND c.category_code IN ('dataQuality', 'metric_g1')
  AND (s.alarm_threshold IS NOT NULL
       OR s.gongdan_threshold IS NOT NULL
       OR s.threshold_value IS NOT NULL);
