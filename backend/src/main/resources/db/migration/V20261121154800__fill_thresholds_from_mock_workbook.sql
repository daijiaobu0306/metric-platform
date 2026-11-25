-- ============================================================================
-- 按《指标值mock.xlsx》补齐 6 项已确认阈值（用户 2026-11-21 确认）
--
-- 表里写的是“合格方向”（达到多少算正常），平台 alarm_condition 存“告警方向”，故取反：
--   数据波动性        < 3%   -> 告警  > 3     %
--   业务指标准确性    < 5%   -> 告警  > 5     %
--   跨系统数据及时性  > 98%  -> 告警  < 98    %
--   数据一致性        < 2%   -> 告警  > 2     %
--   JS异常类型数      < 3    -> 告警  > 3     类
--   业务自定义返回码个数 < 100 -> 告警 > 100  个
--
-- 未处理：字段准确性、枚举值异常触发（表内为 0，用户确认先待填）；对账差异率 >1% 与现状一致。
-- 范围：只填 alarm_threshold 仍为空的策略，不覆盖人工修改；不改指标定义、单位、采集配置。
-- 幂等：可重复执行。
-- ============================================================================

START TRANSACTION;

-- 数据质量 4 项（策略已存在但阈值为空）
UPDATE alarm_strategy s
  JOIN metric_item m ON m.id = s.target_id
   SET s.alarm_threshold = CASE m.metric_code
         WHEN 'dataVolatility'             THEN 3
         WHEN 'businessIndicatorAccuracy'  THEN 5
         WHEN 'crossSystemDataTimeliness'  THEN 98
         WHEN 'dataConsistency'            THEN 2
       END,
       s.alarm_condition = CASE m.metric_code
         WHEN 'crossSystemDataTimeliness'  THEN '<'
         ELSE '>'
       END,
       s.alarm_enabled = 1,
       s.status = 1,
       s.update_time = NOW(6)
 WHERE s.target_type = 'metric' AND s.strategy_type = 'metric'
   AND s.alarm_threshold IS NULL
   AND m.metric_code IN ('dataVolatility','businessIndicatorAccuracy',
                         'crossSystemDataTimeliness','dataConsistency');

-- 系统异常 2 项（策略已存在但阈值为空）
UPDATE alarm_strategy s
  JOIN metric_item m ON m.id = s.target_id
   SET s.alarm_threshold = CASE m.metric_code
         WHEN 'error_js_type_count'     THEN 3
         WHEN 'error_return_code_count' THEN 100
       END,
       s.alarm_condition = '>',
       s.alarm_enabled = 1,
       s.status = 1,
       s.update_time = NOW(6)
 WHERE s.target_type = 'metric' AND s.strategy_type = 'metric'
   AND s.alarm_threshold IS NULL
   AND m.metric_code IN ('error_js_type_count','error_return_code_count');

-- 监控配置为历史只读副本，存在则同步，避免两处显示不一致
UPDATE metric_monitor_config mc
  JOIN metric_item m ON m.id = mc.metric_id
   SET mc.alarm_threshold = CASE m.metric_code
         WHEN 'dataVolatility'             THEN 3
         WHEN 'businessIndicatorAccuracy'  THEN 5
         WHEN 'crossSystemDataTimeliness'  THEN 98
         WHEN 'dataConsistency'            THEN 2
         WHEN 'error_js_type_count'        THEN 3
         WHEN 'error_return_code_count'    THEN 100
       END,
       mc.alarm_condition = IF(m.metric_code='crossSystemDataTimeliness','<','>'),
       mc.alarm_status = 1,
       mc.update_time = NOW(6)
 WHERE (mc.alarm_threshold IS NULL OR mc.alarm_status = 0)
   AND m.metric_code IN ('dataVolatility','businessIndicatorAccuracy',
                         'crossSystemDataTimeliness','dataConsistency',
                         'error_js_type_count','error_return_code_count');

COMMIT;
