-- Preserve current IDs/rules and historical business records. Remove only known obsolete definitions.
CREATE TEMPORARY TABLE external_current_metrics AS
SELECT m.id FROM metric_item m JOIN metric_category c ON c.id=m.category_id
WHERE c.category_code='external_customer' AND m.metric_code IN ('payCreditAvgTime','credit1minTimelyRate','accountCheckDiffRate','creditStartOrderAvgTime','handle1minTimelyRate','billCollectAvgTime','collect1minTimelyRate','billDecodeAvgTime','decode1minTimelyRate','billPricingAvgTime','pricing1minTimelyRate','remindFileAvgTime','remindFile1minTimelyRate','remindOrderAvgTime','remindOrder1minTimelyRate','smsHandleAvgTime','smsHandle1minTimelyRate','smsSendAvgTime','smsSend1minTimelyRate');
CREATE TEMPORARY TABLE external_catalog_guard (valid_catalog INT NOT NULL);
INSERT INTO external_catalog_guard SELECT IF(COUNT(*)=19,1,NULL) FROM external_current_metrics;
CREATE TEMPORARY TABLE external_retired_metrics AS
SELECT old.id FROM metric_item old
JOIN metric_category c ON c.id=old.category_id AND c.category_code='external_customer'
JOIN metric_item current ON current.category_id=old.category_id AND current.metric_code=CASE old.metric_code
 WHEN 'PAY001' THEN 'payCreditAvgTime' WHEN 'PAY002' THEN 'credit1minTimelyRate'
 WHEN 'PAY003' THEN 'accountCheckDiffRate' WHEN 'XK001' THEN 'creditStartOrderAvgTime'
 WHEN 'XK002' THEN 'handle1minTimelyRate' END
WHERE old.metric_code IN ('PAY001','PAY002','PAY003','XK001','XK002')
 AND old.metric_name=current.metric_name AND old.sub_category_name=current.sub_category_name;
CREATE TEMPORARY TABLE external_retired_strategies AS
SELECT id FROM alarm_strategy WHERE target_type='metric' AND target_id IN(SELECT id FROM external_retired_metrics);
UPDATE alarm SET metric_id=NULL WHERE metric_id IN(SELECT id FROM external_retired_metrics);
UPDATE alarm SET strategy_id=NULL WHERE strategy_id IN(SELECT id FROM external_retired_strategies);
UPDATE gongdan SET metric_id=NULL WHERE metric_id IN(SELECT id FROM external_retired_metrics);
UPDATE gongdan SET strategy_id=NULL WHERE strategy_id IN(SELECT id FROM external_retired_strategies);
UPDATE issue SET metric_id=NULL WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM raw_data_alarm_config WHERE strategy_id IN(SELECT id FROM external_retired_strategies);
DELETE FROM alarm_trigger_history WHERE metric_id IN(SELECT id FROM external_retired_metrics) OR strategy_id IN(SELECT id FROM external_retired_strategies);
DELETE FROM metric_monitor_config WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM metric_realtime WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM metric_data WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM metric_error_keywords WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM metric_health_history WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM metric_value_history WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM weight_config WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM health_snapshot_1h WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM health_snapshot_6h WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM health_snapshot_1d WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM health_snapshot_7d WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM health_snapshot_30d WHERE metric_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM shield_rule WHERE shield_type='metric' AND target_id IN(SELECT id FROM external_retired_metrics);
DELETE FROM alarm_strategy WHERE id IN(SELECT id FROM external_retired_strategies);
DELETE FROM metric_item WHERE id IN(SELECT id FROM external_retired_metrics);
UPDATE metric_item m JOIN external_current_metrics current ON current.id=m.id
SET m.calc_rule_type='external_value',m.calc_source_table='collection_row',m.calc_field='metric_value',
 m.calc_extra='{"sourceCode":"external_customer","parser":"external-customer-values","mode":"direct_value","metricKey":"metric_id"}',
 m.update_time=NOW();
-- Retain original definitions, frequencies and the 19 current threshold configurations.
DROP TEMPORARY TABLE external_retired_strategies;
DROP TEMPORARY TABLE external_retired_metrics;
DROP TEMPORARY TABLE external_catalog_guard;
DROP TEMPORARY TABLE external_current_metrics;
