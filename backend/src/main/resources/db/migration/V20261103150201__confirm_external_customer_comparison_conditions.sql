-- 2026-11-03: user authorized deriving comparison conditions from metric definitions.
-- Durations and discrepancy ratio: above the workbook limit (>).
-- Within-one-minute timely ratios: below the workbook minimum (<).
-- Equality is not an exceedance. Workbook threshold values remain unchanged.
-- Business-value six >= rules were separately confirmed as final; do not change them here.
START TRANSACTION;
SET @external_conditions='[{"code":"payCreditAvgTime","condition":">","threshold":"5"},{"code":"credit1minTimelyRate","condition":"<","threshold":"99.00"},{"code":"accountCheckDiffRate","condition":">","threshold":"1.00"},{"code":"creditStartOrderAvgTime","condition":">","threshold":"5"},{"code":"handle1minTimelyRate","condition":"<","threshold":"88.00"},{"code":"billCollectAvgTime","condition":">","threshold":"3"},{"code":"collect1minTimelyRate","condition":"<","threshold":"99.00"},{"code":"billDecodeAvgTime","condition":">","threshold":"30"},{"code":"decode1minTimelyRate","condition":"<","threshold":"99.00"},{"code":"billPricingAvgTime","condition":">","threshold":"30"},{"code":"pricing1minTimelyRate","condition":"<","threshold":"99.00"},{"code":"remindFileAvgTime","condition":">","threshold":"30"},{"code":"remindFile1minTimelyRate","condition":"<","threshold":"99.00"},{"code":"remindOrderAvgTime","condition":">","threshold":"5"},{"code":"remindOrder1minTimelyRate","condition":"<","threshold":"99.00"},{"code":"smsHandleAvgTime","condition":">","threshold":"5"},{"code":"smsHandle1minTimelyRate","condition":"<","threshold":"99.00"},{"code":"smsSendAvgTime","condition":">","threshold":"5"},{"code":"smsSend1minTimelyRate","condition":"<","threshold":"99.00"}]';

SET @confirmed_external_codes=(SELECT COUNT(DISTINCT BINARY m.metric_code)
 FROM metric_item m JOIN metric_category c ON c.id=m.category_id
 JOIN JSON_TABLE(@external_conditions,'$[*]' COLUMNS(code VARCHAR(50) PATH '$.code', threshold_value DECIMAL(15,4) PATH '$.threshold')) expected
   ON BINARY m.metric_code=BINARY expected.code
 WHERE BINARY c.category_code=BINARY 'external_customer'
 AND EXISTS(SELECT 1 FROM alarm_strategy s WHERE s.target_id=m.id AND s.target_type='metric'
   AND s.strategy_type='metric' AND s.status=1 AND s.alarm_threshold=expected.threshold_value));
SET @guard_sql=IF(@confirmed_external_codes=19,'SELECT 1',
 'SELECT * FROM external_customer_conditions_require_confirmed_workbook_thresholds');
PREPARE external_condition_guard FROM @guard_sql;
EXECUTE external_condition_guard;
DEALLOCATE PREPARE external_condition_guard;
UPDATE alarm_strategy s JOIN metric_item m ON m.id=s.target_id
 JOIN metric_category c ON c.id=m.category_id
 JOIN JSON_TABLE(@external_conditions,'$[*]' COLUMNS(code VARCHAR(50) PATH '$.code', comparison VARCHAR(2) PATH '$.condition')) expected
   ON BINARY m.metric_code=BINARY expected.code
SET s.alarm_condition=expected.comparison,s.alarm_enabled=1,s.update_time=NOW(6)
WHERE BINARY c.category_code=BINARY 'external_customer' AND s.target_type='metric'
 AND s.strategy_type='metric' AND s.status=1;
UPDATE metric_monitor_config mc JOIN metric_item m ON m.id=mc.metric_id
 JOIN metric_category c ON c.id=m.category_id
 JOIN JSON_TABLE(@external_conditions,'$[*]' COLUMNS(code VARCHAR(50) PATH '$.code', comparison VARCHAR(2) PATH '$.condition')) expected
   ON BINARY m.metric_code=BINARY expected.code
SET mc.alarm_condition=expected.comparison,mc.alarm_status=1,mc.update_time=NOW(6)
WHERE BINARY c.category_code=BINARY 'external_customer';
COMMIT;
