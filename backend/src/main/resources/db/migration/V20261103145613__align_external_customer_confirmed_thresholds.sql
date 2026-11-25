-- Confirmed workbook: 1、外部客户体验（标绿色）, D3:D21 and L3:L21.
-- Numeric thresholds only: the source does not specify comparison operators or ticket thresholds.
-- Preserve metric IDs, windows/counts, raw values, business dates, task states and protected categories.
START TRANSACTION;
SET @external_thresholds='[{"code":"payCreditAvgTime","threshold":"5"},{"code":"credit1minTimelyRate","threshold":"99.00"},{"code":"accountCheckDiffRate","threshold":"1.00"},{"code":"creditStartOrderAvgTime","threshold":"5"},{"code":"handle1minTimelyRate","threshold":"88.00"},{"code":"billCollectAvgTime","threshold":"3"},{"code":"collect1minTimelyRate","threshold":"99.00"},{"code":"billDecodeAvgTime","threshold":"30"},{"code":"decode1minTimelyRate","threshold":"99.00"},{"code":"billPricingAvgTime","threshold":"30"},{"code":"pricing1minTimelyRate","threshold":"99.00"},{"code":"remindFileAvgTime","threshold":"30"},{"code":"remindFile1minTimelyRate","threshold":"99.00"},{"code":"remindOrderAvgTime","threshold":"5"},{"code":"remindOrder1minTimelyRate","threshold":"99.00"},{"code":"smsHandleAvgTime","threshold":"5"},{"code":"smsHandle1minTimelyRate","threshold":"99.00"},{"code":"smsSendAvgTime","threshold":"5"},{"code":"smsSend1minTimelyRate","threshold":"99.00"}]';

SET @external_metric_count=(SELECT COUNT(*) FROM metric_item m
  JOIN metric_category c ON c.id=m.category_id
  JOIN JSON_TABLE(@external_thresholds,'$[*]' COLUMNS(code VARCHAR(50) PATH '$.code')) expected
    ON BINARY m.metric_code=BINARY expected.code
  WHERE BINARY c.category_code=BINARY 'external_customer'
    AND EXISTS (SELECT 1 FROM alarm_strategy s WHERE s.target_id=m.id AND s.target_type='metric' AND s.strategy_type='metric'));
SET @external_code_count=(SELECT COUNT(DISTINCT BINARY m.metric_code) FROM metric_item m
  JOIN metric_category c ON c.id=m.category_id
  JOIN JSON_TABLE(@external_thresholds,'$[*]' COLUMNS(code VARCHAR(50) PATH '$.code')) expected
    ON BINARY m.metric_code=BINARY expected.code
  WHERE BINARY c.category_code=BINARY 'external_customer');
SET @guard_sql=IF(@external_metric_count=19 AND @external_code_count=19,'SELECT 1',
 'SELECT * FROM external_customer_rules_require_exactly_nineteen_confirmed_metric_codes');
PREPARE external_guard FROM @guard_sql;
EXECUTE external_guard;
DEALLOCATE PREPARE external_guard;

UPDATE alarm_strategy s JOIN metric_item m ON m.id=s.target_id
 JOIN metric_category c ON c.id=m.category_id
 JOIN JSON_TABLE(@external_thresholds,'$[*]' COLUMNS(code VARCHAR(50) PATH '$.code', threshold_value DECIMAL(15,4) PATH '$.threshold')) expected
   ON BINARY m.metric_code=BINARY expected.code
SET s.alarm_threshold=expected.threshold_value,s.alarm_condition=NULL,s.alarm_enabled=0,s.alarm_rules=NULL,
 s.gongdan_threshold=NULL,s.gongdan_condition=NULL,s.gongdan_enabled=0,s.gongdan_rules=NULL,s.update_time=NOW(6)
WHERE BINARY c.category_code=BINARY 'external_customer' AND s.target_type='metric' AND s.strategy_type='metric';

UPDATE metric_monitor_config mc JOIN metric_item m ON m.id=mc.metric_id
 JOIN metric_category c ON c.id=m.category_id
 JOIN JSON_TABLE(@external_thresholds,'$[*]' COLUMNS(code VARCHAR(50) PATH '$.code', threshold_value DECIMAL(15,4) PATH '$.threshold')) expected
   ON BINARY m.metric_code=BINARY expected.code
SET mc.alarm_threshold=expected.threshold_value,mc.alarm_condition=NULL,mc.alarm_status=0,
 mc.gongdan_threshold=NULL,mc.gongdan_condition=NULL,mc.gongdan_status=0,mc.update_time=NOW(6)
WHERE BINARY c.category_code=BINARY 'external_customer';
COMMIT;
