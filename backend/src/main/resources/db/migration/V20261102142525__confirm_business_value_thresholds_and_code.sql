-- Source: 6、业务价值!D3:L14 in the user-confirmed workbook.
-- 2026-11-02: user chose importantAlarmRatio and provisionally confirmed >= for all six thresholds.
-- No source values, collected records, historical alarms or existing IDs are rewritten.

-- Reject ambiguous code renames instead of choosing by similar names.
SET @business_categories = (SELECT COUNT(*) FROM metric_category WHERE BINARY category_code=BINARY 'businessValue');
SET @rename_targets = (SELECT COUNT(*) FROM metric_item m JOIN metric_category c ON c.id=m.category_id
  WHERE BINARY c.category_code=BINARY 'businessValue' AND BINARY m.metric_code IN ('importantAlarmRate','importantAlarmRatio'));
SET @guard_sql = IF(@business_categories=1 AND @rename_targets=1,'SELECT 1',
  'SELECT * FROM business_value_code_mapping_requires_manual_resolution');
PREPARE confirmed_guard FROM @guard_sql;
EXECUTE confirmed_guard;
DEALLOCATE PREPARE confirmed_guard;

UPDATE metric_item m JOIN metric_category c ON c.id=m.category_id
SET m.metric_code='importantAlarmRatio'
WHERE BINARY c.category_code=BINARY 'businessValue' AND BINARY m.metric_code=BINARY 'importantAlarmRate';

-- Existing strategy rows retain their IDs and consecutive-count/window settings.
-- No workbook work-order threshold exists: disable that branch and clear all its old values.
UPDATE alarm_strategy s
JOIN metric_item m ON m.id=s.target_id
JOIN metric_category c ON c.id=m.category_id
SET s.alarm_threshold=CASE m.metric_code
      WHEN 'alarmHandleRate' THEN 80 WHEN 'importantAlarmRatio' THEN 30
      WHEN 'orderCloseRate' THEN 80 WHEN 'problemSolveRate' THEN 90
      WHEN 'problemIndexCount' THEN 10 WHEN 'repeatProblemCount' THEN 10 ELSE NULL END,
    s.alarm_condition=CASE WHEN m.metric_code IN ('alarmHandleRate','importantAlarmRatio','orderCloseRate','problemSolveRate','problemIndexCount','repeatProblemCount') THEN '>=' ELSE NULL END,
    s.alarm_enabled=CASE WHEN m.metric_code IN ('alarmHandleRate','importantAlarmRatio','orderCloseRate','problemSolveRate','problemIndexCount','repeatProblemCount') THEN 1 ELSE 0 END,
    s.alarm_rules=NULL,
    s.gongdan_threshold=NULL,s.gongdan_condition=NULL,s.gongdan_enabled=0,s.gongdan_rules=NULL,
    s.update_time=NOW(6)
WHERE BINARY c.category_code=BINARY 'businessValue' AND s.target_type='metric' AND s.strategy_type='metric'
  AND BINARY m.metric_code IN ('alarmCount','alarmHandleRate','importantAlarmRatio','orderCount','orderCloseRate','problemCount',
    'problemSolveRate','problemIndexCount','optimizeIndexCount','repeatProblemCount','indexIncludeCount','dimensionIncludeCount');

-- Compatibility columns must not expose or revive retired defaults on legacy configuration screens.
UPDATE metric_monitor_config mc
JOIN metric_item m ON m.id=mc.metric_id JOIN metric_category c ON c.id=m.category_id
SET mc.alarm_threshold=CASE m.metric_code
      WHEN 'alarmHandleRate' THEN 80 WHEN 'importantAlarmRatio' THEN 30
      WHEN 'orderCloseRate' THEN 80 WHEN 'problemSolveRate' THEN 90
      WHEN 'problemIndexCount' THEN 10 WHEN 'repeatProblemCount' THEN 10 ELSE NULL END,
    mc.alarm_condition=CASE WHEN m.metric_code IN ('alarmHandleRate','importantAlarmRatio','orderCloseRate','problemSolveRate','problemIndexCount','repeatProblemCount') THEN '>=' ELSE NULL END,
    mc.alarm_status=CASE WHEN m.metric_code IN ('alarmHandleRate','importantAlarmRatio','orderCloseRate','problemSolveRate','problemIndexCount','repeatProblemCount') THEN 1 ELSE 0 END,
    mc.gongdan_threshold=NULL,mc.gongdan_condition=NULL,mc.gongdan_status=0
WHERE BINARY c.category_code=BINARY 'businessValue'
  AND BINARY m.metric_code IN ('alarmCount','alarmHandleRate','importantAlarmRatio','orderCount','orderCloseRate','problemCount',
    'problemSolveRate','problemIndexCount','optimizeIndexCount','repeatProblemCount','indexIncludeCount','dimensionIncludeCount');
