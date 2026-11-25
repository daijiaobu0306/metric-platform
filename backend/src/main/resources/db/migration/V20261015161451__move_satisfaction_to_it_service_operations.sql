-- Satisfaction belongs to IT服务运营平台, not CRM. Data-only correction.
-- Existing CRM and unrelated categories/records remain unchanged.
INSERT INTO system_info(system_code,system_name,description,weight,status)
SELECT 'IT_SERVICE_OPERATIONS','IT服务运营平台','IT服务运营平台',1.00,1
WHERE NOT EXISTS(SELECT 1 FROM system_info WHERE system_name='IT服务运营平台')
ON DUPLICATE KEY UPDATE system_name=VALUES(system_name);

SET @satisfaction_it_system=(SELECT id FROM system_info WHERE system_name='IT服务运营平台' ORDER BY id LIMIT 1);
SET @satisfaction_category=(SELECT id FROM metric_category WHERE category_code='satisfaction');

UPDATE metric_category SET system_id=@satisfaction_it_system
WHERE id=@satisfaction_category AND @satisfaction_it_system IS NOT NULL;

UPDATE alarm a LEFT JOIN metric_item m ON m.id=a.metric_id
SET a.system_id=@satisfaction_it_system,a.system_name='IT服务运营平台'
WHERE @satisfaction_it_system IS NOT NULL
AND (a.category_name='满意度及投诉服务体验' OR m.category_id=@satisfaction_category);

UPDATE gongdan g LEFT JOIN metric_item m ON m.id=g.metric_id
SET g.system_id=@satisfaction_it_system
WHERE @satisfaction_it_system IS NOT NULL
AND (g.category_id=@satisfaction_category OR m.category_id=@satisfaction_category);

UPDATE issue i LEFT JOIN metric_item m ON m.id=i.metric_id
SET i.system_id=@satisfaction_it_system,i.system_name='IT服务运营平台'
WHERE @satisfaction_it_system IS NOT NULL
AND (i.category_id=@satisfaction_category OR i.category_name='满意度及投诉服务体验'
     OR m.category_id=@satisfaction_category);

UPDATE weight_config w LEFT JOIN metric_item m ON m.id=w.metric_id
SET w.system_id=@satisfaction_it_system
WHERE @satisfaction_it_system IS NOT NULL
AND (w.category_id=@satisfaction_category OR m.category_id=@satisfaction_category);

UPDATE health_snapshot_1h h LEFT JOIN metric_item m ON m.id=h.metric_id
SET h.system_id=@satisfaction_it_system
WHERE @satisfaction_it_system IS NOT NULL AND h.level_type<>'system'
AND (h.category_name='满意度及投诉服务体验' OR m.category_id=@satisfaction_category);

UPDATE health_snapshot_6h h LEFT JOIN metric_item m ON m.id=h.metric_id
SET h.system_id=@satisfaction_it_system
WHERE @satisfaction_it_system IS NOT NULL AND h.level_type<>'system'
AND (h.category_name='满意度及投诉服务体验' OR m.category_id=@satisfaction_category);

UPDATE health_snapshot_1d h LEFT JOIN metric_item m ON m.id=h.metric_id
SET h.system_id=@satisfaction_it_system
WHERE @satisfaction_it_system IS NOT NULL AND h.level_type<>'system'
AND (h.category_name='满意度及投诉服务体验' OR m.category_id=@satisfaction_category);

UPDATE health_snapshot_7d h LEFT JOIN metric_item m ON m.id=h.metric_id
SET h.system_id=@satisfaction_it_system
WHERE @satisfaction_it_system IS NOT NULL AND h.level_type<>'system'
AND (h.category_name='满意度及投诉服务体验' OR m.category_id=@satisfaction_category);

UPDATE health_snapshot_30d h LEFT JOIN metric_item m ON m.id=h.metric_id
SET h.system_id=@satisfaction_it_system
WHERE @satisfaction_it_system IS NOT NULL AND h.level_type<>'system'
AND (h.category_name='满意度及投诉服务体验' OR m.category_id=@satisfaction_category);
