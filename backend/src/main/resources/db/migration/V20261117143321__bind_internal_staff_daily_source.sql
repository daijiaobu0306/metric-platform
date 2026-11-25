-- Bind the immutable release containing yesterday's full-day acquisition window.
UPDATE collection_source SET config_json=JSON_SET(config_json,
 '$.worker.version','internal_staff-production-20261117-001',
 '$.schedule',JSON_OBJECT('type','daily','time','06:00'),
 '$.arrival',JSON_OBJECT('lagDays',1,'dueTime','06:00')),
 next_scan_at=CASE WHEN NOW()<TIMESTAMP(CURDATE(),'06:00:00')
 THEN TIMESTAMP(CURDATE(),'06:00:00') ELSE TIMESTAMP(CURDATE(),'06:00:00')+INTERVAL 1 DAY END
WHERE code='internal_staff';
