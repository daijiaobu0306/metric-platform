-- ============================================================================
-- 同步业务价值 alarmHandleRate 历史采集行的指标名称：告警处理及时率 -> 告警处理率
--
-- 原因：V20261119113628 已把 metric_item.metric_name 从「告警处理及时率」改为「告警处理率」
--       （用户确认的重命名），但当时只改了 metric_item 与 alarm_strategy，未同步
--       collection_row 里的历史采集行。运营报表的统一观测读取按 metric_name 判冲突
--       （名称不一致即 conflict，指标值置空显示「来源冲突/0」），而数据汇聚直接回放
--       采集行不判冲突，于是出现「运营报表为 0、数据汇聚有值」的分歧。
--
-- 范围：仅 businessValue 大类 alarmHandleRate 的历史采集行，且仅在名称仍为旧值时改。
--       幂等：改完后再执行影响 0 行。
-- 不改：指标值、数据时间、单位、采集批次、来源文件等任何其它字段。
-- ============================================================================

START TRANSACTION;

UPDATE collection_row r
SET r.data_json = JSON_SET(r.data_json, '$.metric_name', '告警处理率')
WHERE JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.metric_code')) = 'alarmHandleRate'
  AND JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.metric_name')) = '告警处理及时率';

COMMIT;
