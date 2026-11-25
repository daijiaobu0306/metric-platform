-- Retire only the 23 known legacy definitions; retain historical business records.
-- This applies after the final17 seed, so new installs and upgraded databases agree.
CREATE TEMPORARY TABLE satisfaction_retired_metrics AS
SELECT m.id FROM metric_item m JOIN metric_category c ON c.id=m.category_id
WHERE c.category_code='satisfaction' AND m.metric_code IN('firstLineHandleTimelyRate','firstLineProblemSolveRate','thirdLineHandleTimelyRate','firstLineHandleAvgTime','greenChannelComplainAvgTime','urgeOrderOver2TimesComplainAvgTime','claimUpgradeComplainOrderHandleTime','helpDeskHandleTime','helpDeskSolveRate','helpDeskSupportTimelyRate','registrantBackRate','oneStopServiceAssignApiSuccessRate','oneStopServiceAssignApiTimeoutRate','serviceFirstLineAvgTime','serviceFirstLineTimelyRate','serviceComplainRepeat2TimesRate','serviceComplainRepeat3TimesRate','businessHallSatisfaction','govAccountManagerSatisfaction','onlineCompanySatisfaction','gridStaffSatisfaction','businessDeptSatisfaction','financeDeptSatisfaction')
AND (SELECT COUNT(*) FROM metric_item f WHERE f.category_id=c.id AND f.calc_rule_type='satisfaction_report')=17;
CREATE TEMPORARY TABLE satisfaction_retired_strategies AS
SELECT id FROM alarm_strategy WHERE target_type='metric' AND target_id IN(SELECT id FROM satisfaction_retired_metrics);
UPDATE alarm SET metric_id=NULL WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
UPDATE alarm SET strategy_id=NULL WHERE strategy_id IN(SELECT id FROM satisfaction_retired_strategies);
UPDATE gongdan SET metric_id=NULL WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
UPDATE gongdan SET strategy_id=NULL WHERE strategy_id IN(SELECT id FROM satisfaction_retired_strategies);
UPDATE issue SET metric_id=NULL WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM raw_data_alarm_config WHERE strategy_id IN(SELECT id FROM satisfaction_retired_strategies);
DELETE FROM alarm_trigger_history WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics) OR strategy_id IN(SELECT id FROM satisfaction_retired_strategies);
DELETE FROM metric_monitor_config WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM metric_realtime WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM metric_data WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM metric_error_keywords WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM metric_health_history WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM metric_value_history WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM weight_config WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM health_snapshot_1h WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM health_snapshot_6h WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM health_snapshot_1d WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM health_snapshot_7d WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM health_snapshot_30d WHERE metric_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM shield_rule WHERE shield_type='metric' AND target_id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE FROM alarm_strategy WHERE id IN(SELECT id FROM satisfaction_retired_strategies);
DELETE FROM metric_item WHERE id IN(SELECT id FROM satisfaction_retired_metrics);
DELETE w FROM weight_config w JOIN metric_category c ON c.id=w.category_id
WHERE c.category_code='satisfaction' AND w.weight_type='subcategory' AND w.sub_category_name IN('地市投诉服务体验','客服投诉服务体验','内部满意度');
DELETE s FROM metric_sub_category s JOIN metric_category c ON c.id=s.category_id
WHERE c.category_code='satisfaction' AND s.sub_category_code IN('cityComplaint','serviceComplaint','innerSatisfaction')
AND NOT EXISTS(SELECT 1 FROM metric_item m WHERE m.category_id=s.category_id AND m.sub_category_code=s.sub_category_code);
DELETE FROM metric_import_config WHERE sub_category_code IN('cityComplaint','serviceComplaint','innerSatisfaction');
DROP TEMPORARY TABLE satisfaction_retired_strategies;
DROP TEMPORARY TABLE satisfaction_retired_metrics;

-- Match the four-decimal report values for newly generated alarms.
ALTER TABLE alarm MODIFY COLUMN metric_value DECIMAL(15,4) NULL, MODIFY COLUMN threshold_value DECIMAL(15,4) NULL;
-- Recover precision only when the original generated content agrees with the rounded stored value.
UPDATE alarm a JOIN metric_item m ON m.id=a.metric_id
SET a.metric_value=CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(a.alarm_content,'指标值=',-1),' ',1) AS DECIMAL(15,4))
WHERE m.calc_rule_type='satisfaction_report' AND a.alarm_type='指标值告警'
AND a.alarm_content REGEXP '指标值=[0-9]+([.][0-9]+)? '
AND ROUND(CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(a.alarm_content,'指标值=',-1),' ',1) AS DECIMAL(15,4)),2)=a.metric_value;
