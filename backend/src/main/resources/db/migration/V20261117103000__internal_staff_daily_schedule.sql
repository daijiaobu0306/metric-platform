-- 对内部人员：取数与采集频率改为每天一次（今天早上取昨天的数据）。
-- 采集窗口（昨天全天）与 data_time（昨天 00:00:00）由 categories/internal_staff/sources.json、source_export.py 决定，
-- 这里把任务自身从“按间隔 5 分钟扫描”改为每日 06:00 定时执行；应到规则 T-1（06:00 应到）。
UPDATE collection_source
SET config_json = JSON_SET(config_json,
        '$.schedule', JSON_OBJECT('type', 'daily', 'time', '06:00'),
        '$.arrival', JSON_OBJECT('lagDays', 1, 'dueTime', '06:00')),
    next_scan_at = CASE
        WHEN NOW() < TIMESTAMP(CURDATE(), '06:00:00') THEN TIMESTAMP(CURDATE(), '06:00:00')
        ELSE TIMESTAMP(CURDATE(), '06:00:00') + INTERVAL 1 DAY
    END
WHERE code = 'internal_staff';
