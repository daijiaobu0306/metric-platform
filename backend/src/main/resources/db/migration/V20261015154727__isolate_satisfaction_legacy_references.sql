-- Correct legacy dangling references that reused the IDs assigned by the preceding seed.

-- Existing legacy records and historical alarms keep their original metric IDs.

-- Restore only the fields touched by the preceding seed/monitor, using the verified V12 snapshot.

SELECT MAX(n) INTO @satisfaction_reserved_id FROM (SELECT MAX(id) n FROM metric_item UNION ALL SELECT MAX(`metric_id`) n FROM `_local_legacy_monitor_config_backup` UNION ALL SELECT MAX(`metric_id`) n FROM `alarm` UNION ALL SELECT MAX(`metric_id`) n FROM `alarm_trigger_history` UNION ALL SELECT MAX(`metric_id`) n FROM `gongdan` UNION ALL SELECT MAX(`metric_id`) n FROM `health_snapshot_1d` UNION ALL SELECT MAX(`metric_id`) n FROM `health_snapshot_1h` UNION ALL SELECT MAX(`metric_id`) n FROM `health_snapshot_30d` UNION ALL SELECT MAX(`metric_id`) n FROM `health_snapshot_6h` UNION ALL SELECT MAX(`metric_id`) n FROM `health_snapshot_7d` UNION ALL SELECT MAX(`metric_id`) n FROM `issue` UNION ALL SELECT MAX(`metric_id`) n FROM `metric_data` UNION ALL SELECT MAX(`metric_id`) n FROM `metric_error_keywords` UNION ALL SELECT MAX(`metric_id`) n FROM `metric_health_history` UNION ALL SELECT MAX(`metric_id`) n FROM `metric_monitor_config` UNION ALL SELECT MAX(`metric_id`) n FROM `metric_realtime` UNION ALL SELECT MAX(`metric_id`) n FROM `metric_value_history` UNION ALL SELECT MAX(`metric_id`) n FROM `weight_config` UNION ALL SELECT MAX(target_id) n FROM alarm_strategy WHERE target_type='metric' UNION ALL SELECT MAX(target_id) n FROM shield_rule WHERE shield_type='metric') reserved;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R003' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='单个接口的失败次数 策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=20.0000,alarm_condition='>',update_time='2026-04-22 23:05:23' WHERE metric_id=@satisfaction_old_id AND id=119;

UPDATE alarm_strategy SET alarm_threshold=20.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.859316' WHERE target_id=@satisfaction_old_id AND id=124 AND strategy_name='单个接口的失败次数 策略';

UPDATE metric_realtime SET metric_value=2.0000,data_time='2026-05-18 12:38:43',is_exceed=0,exceed_count=1514,last_exceed_time='2026-04-07 17:28:00',status='正常',create_time='2026-04-02 14:45:01',update_time='2026-05-18 12:38:45' WHERE metric_id=@satisfaction_old_id AND id=11;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R004' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='单个接口的成功率 策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=5.0000,alarm_condition='>',update_time='2026-04-22 23:05:23' WHERE metric_id=@satisfaction_old_id AND id=120;

UPDATE alarm_strategy SET alarm_threshold=5.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.859416' WHERE target_id=@satisfaction_old_id AND id=125 AND strategy_name='单个接口的成功率 策略';

UPDATE metric_realtime SET metric_value=0.0000,data_time='2026-05-18 12:38:43',is_exceed=0,exceed_count=6,last_exceed_time='2026-04-08 10:04:01',status='正常',create_time='2026-04-02 14:45:01',update_time='2026-05-18 12:38:45' WHERE metric_id=@satisfaction_old_id AND id=12;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R005' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='接口的整体成功率 策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=95.0000,alarm_condition='>',update_time='2026-04-22 23:05:23' WHERE metric_id=@satisfaction_old_id AND id=121;

UPDATE alarm_strategy SET alarm_threshold=95.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.836126' WHERE target_id=@satisfaction_old_id AND id=126 AND strategy_name='接口的整体成功率 策略';

UPDATE metric_realtime SET metric_value=99.9987,data_time='2026-05-18 12:38:43',is_exceed=1,exceed_count=23667,last_exceed_time='2026-07-02 21:07:00',status='工单',create_time='2026-04-02 14:45:01',update_time='2026-07-02 21:07:00' WHERE metric_id=@satisfaction_old_id AND id=13;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R006' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='实例异常次数 策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=1.0000,alarm_condition='>',update_time='2026-04-22 23:05:23' WHERE metric_id=@satisfaction_old_id AND id=122;

UPDATE alarm_strategy SET alarm_threshold=1.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.859428' WHERE target_id=@satisfaction_old_id AND id=127 AND strategy_name='实例异常次数 策略';

UPDATE metric_realtime SET metric_value=33.0000,data_time='2026-05-18 12:38:43',is_exceed=1,exceed_count=35589,last_exceed_time='2026-07-02 21:07:00',status='工单',create_time='2026-04-02 14:45:01',update_time='2026-07-02 21:07:00' WHERE metric_id=@satisfaction_old_id AND id=14;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R007' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='JS异常类型数-告警策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=10.0000,alarm_condition='>',update_time='2026-04-22 20:57:22' WHERE metric_id=@satisfaction_old_id AND id=123;

UPDATE alarm_strategy SET alarm_threshold=10.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.850253' WHERE target_id=@satisfaction_old_id AND id=128 AND strategy_name='JS异常类型数-告警策略';

UPDATE metric_realtime SET metric_value=12.0000,data_time='2026-05-18 12:38:43',is_exceed=1,exceed_count=12268,last_exceed_time='2026-06-17 16:47:00',status='告警',create_time='2026-04-02 14:45:01',update_time='2026-06-17 16:47:00' WHERE metric_id=@satisfaction_old_id AND id=15;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R008' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='单个JS异常的次数 策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=10.0000,alarm_condition='>',update_time='2026-04-22 23:05:23' WHERE metric_id=@satisfaction_old_id AND id=124;

UPDATE alarm_strategy SET alarm_threshold=10.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.854349' WHERE target_id=@satisfaction_old_id AND id=129 AND strategy_name='单个JS异常的次数 策略';

UPDATE metric_realtime SET metric_value=12.0000,data_time='2026-05-18 12:38:43',is_exceed=1,exceed_count=12268,last_exceed_time='2026-06-17 16:47:00',status='告警',create_time='2026-04-02 14:45:01',update_time='2026-06-17 16:47:00' WHERE metric_id=@satisfaction_old_id AND id=16;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R009' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='业务自定义返回异常 策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=3.0000,alarm_condition='>',update_time='2026-04-22 20:48:03' WHERE metric_id=@satisfaction_old_id AND id=125;

UPDATE alarm_strategy SET alarm_threshold=3.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.836029' WHERE target_id=@satisfaction_old_id AND id=130 AND strategy_name='业务自定义返回异常 策略';

UPDATE metric_realtime SET metric_value=0.0000,data_time='2026-05-18 12:38:32',is_exceed=0,exceed_count=0,last_exceed_time=NULL,status='正常',create_time='2026-04-02 14:45:01',update_time='2026-05-18 12:38:45' WHERE metric_id=@satisfaction_old_id AND id=17;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;

SET @satisfaction_old_id=(SELECT m.id FROM metric_item m JOIN alarm_strategy s ON s.target_type='metric' AND s.target_id=m.id WHERE m.metric_code='SAT_FINAL_R010' AND m.calc_rule_type='satisfaction_report' AND s.strategy_name='业务自定义返回码的个数 策略' LIMIT 1);

SET @satisfaction_reserved_id=@satisfaction_reserved_id+1;

SET @satisfaction_new_id=IF(@satisfaction_old_id IS NULL,NULL,@satisfaction_reserved_id);

UPDATE metric_item SET id=@satisfaction_new_id WHERE id=@satisfaction_old_id;

INSERT INTO metric_monitor_config(metric_id,alarm_window_minutes,alarm_threshold,alarm_condition,alarm_status,gongdan_window_minutes,gongdan_status,consecutive_count) SELECT id,1440,alarm_threshold,alarm_threshold_type,1,1440,0,1 FROM metric_item WHERE id=@satisfaction_new_id;

INSERT INTO alarm_strategy(strategy_name,strategy_type,target_type,target_id,target_name,alarm_enabled,alarm_threshold,alarm_condition,alarm_consecutive_times,gongdan_enabled,gongdan_consecutive_times,consecutive_times,generate_type,status,create_time,update_time) SELECT CONCAT(sub_category_name,'-',metric_name,'-预警'),'metric','metric',id,CONCAT(sub_category_name,'-',metric_name),1,alarm_threshold,alarm_threshold_type,1,0,1,1,'alarm',1,NOW(),NOW() FROM metric_item WHERE id=@satisfaction_new_id;

UPDATE alarm a JOIN alarm_strategy s ON s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=@satisfaction_new_id SET a.metric_id=@satisfaction_new_id,a.strategy_id=s.id,a.alarm_content=CONCAT('[',s.strategy_name,']',SUBSTRING(a.alarm_content,LOCATE(']',a.alarm_content)+1)) WHERE a.metric_id=@satisfaction_old_id AND a.alarm_time>='2026-10-15 15:44:00' AND a.alarm_content LIKE '%统计日期=%';

UPDATE metric_monitor_config SET alarm_threshold=200.0000,alarm_condition='>',update_time='2026-04-22 23:05:23' WHERE metric_id=@satisfaction_old_id AND id=126;

UPDATE alarm_strategy SET alarm_threshold=200.0000,alarm_condition='>',update_time='2026-04-22 23:05:22.851274' WHERE target_id=@satisfaction_old_id AND id=131 AND strategy_name='业务自定义返回码的个数 策略';

UPDATE metric_realtime SET metric_value=213.0000,data_time='2026-06-07 08:30:16',is_exceed=1,exceed_count=32150,last_exceed_time='2026-06-17 16:47:00',status='工单',create_time='2026-04-02 14:45:01',update_time='2026-06-17 16:47:00' WHERE metric_id=@satisfaction_old_id AND id=18;

DELETE FROM metric_item WHERE id=@satisfaction_old_id;
