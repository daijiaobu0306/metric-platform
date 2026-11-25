-- Existing tasks only. Preserve IDs, paths, schedules, enabled states and business rules.
UPDATE collection_source SET config_json=JSON_SET(config_json,
 '$.worker.version',CONCAT(code,'-production-20261103-001'),
 '$.worker.timeoutSeconds',300,'$.format','csv','$.filePattern','*.csv',
 '$.encoding','UTF-8','$.delimiter',',','$.dateMode','data_time'),last_error=NULL
WHERE code IN ('internal_staff','running_efficiency')
 AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker';

UPDATE metric_item i JOIN metric_category c ON c.id=i.category_id
 SET i.data_source=CASE WHEN c.category_code='metric_g2' THEN 'internal_staff' ELSE 'running_efficiency' END
WHERE c.category_code='metric_g2'
 OR (c.category_code='system_run' AND i.sub_category_name IN ('系统性能','系统异常'));

UPDATE metric_import_config SET enabled=0
WHERE sub_category_code IN ('inner','performance','error','error_frontend');
