-- Keep the existing audit category and metric IDs. Only move the two external subcategories.
-- The source audit tables/files and their business classifications are intentionally unchanged.
INSERT INTO metric_category
    (system_id, category_code, category_name, category_type, description, weight, status)
SELECT audit_category.system_id, 'external_customer', '对外部客户', audit_category.category_type,
       '外部客户维度：缴费开机、流量提醒', 0, audit_category.status
FROM metric_category audit_category
WHERE audit_category.category_code = 'metric_g1'
  AND audit_category.category_name IN ('对外部客户', '合规管理')
ON DUPLICATE KEY UPDATE id = metric_category.id;

UPDATE metric_category
SET category_name = '合规管理', description = '合规管理维度：系统稽核'
WHERE category_code = 'metric_g1' AND category_name = '对外部客户';

UPDATE metric_item m
JOIN metric_category c ON m.category_id = c.id AND c.category_code = 'metric_g1'
JOIN metric_category e ON e.system_id = c.system_id AND e.category_code = 'external_customer'
SET m.category_id = e.id
WHERE m.sub_category_code IN ('payActive', 'trafficAlert')
   OR m.sub_category_name IN ('缴费开机', '流量提醒');

UPDATE metric_sub_category s
JOIN metric_category c ON s.category_id = c.id AND c.category_code = 'metric_g1'
JOIN metric_category e ON e.system_id = c.system_id AND e.category_code = 'external_customer'
SET s.category_id = e.id
WHERE s.sub_category_code IN ('payActive', 'trafficAlert');

UPDATE weight_config w
JOIN metric_category c ON w.category_id = c.id AND c.category_code = 'metric_g1'
JOIN metric_category e ON e.system_id = c.system_id AND e.category_code = 'external_customer'
LEFT JOIN metric_item m ON w.metric_id = m.id
SET w.category_id = e.id
WHERE w.sub_category_name IN ('payActive', 'trafficAlert', '缴费开机', '流量提醒')
   OR m.category_id = e.id;
-- Retain the old category weight for compliance. External has no collected health data yet;
-- do not invent a new system/category weight or change existing numeric weights.

UPDATE metric_value_history h
JOIN metric_item m ON h.metric_id = m.id
JOIN metric_category c ON m.category_id = c.id
SET h.category_name = c.category_name
WHERE h.category_name = '对外部客户' AND c.category_name = '合规管理';
UPDATE metric_health_history h
JOIN metric_item m ON h.metric_id = m.id
JOIN metric_category c ON m.category_id = c.id
SET h.category_name = c.category_name
WHERE h.category_name = '对外部客户' AND c.category_name = '合规管理';
UPDATE sub_category_health_history
SET category_name = '合规管理'
WHERE category_name = '对外部客户' AND sub_category_name = '系统稽核';

-- Reclassify a category snapshot only when its underlying rows identify audit alone.
UPDATE category_health_history h
SET h.category_name = '合规管理'
WHERE h.category_name = '对外部客户'
  AND EXISTS (SELECT 1 FROM sub_category_health_history s
              WHERE s.category_name = '合规管理' AND s.sub_category_name = '系统稽核'
                AND s.record_time = h.record_time)
  AND NOT EXISTS (SELECT 1 FROM sub_category_health_history s
                  WHERE s.category_name = '对外部客户' AND s.record_time = h.record_time);

UPDATE alarm a
LEFT JOIN metric_item m ON a.metric_id = m.id
LEFT JOIN metric_category c ON m.category_id = c.id
SET a.category_name = '合规管理',
    a.alarm_content = REPLACE(a.alarm_content, '对外部客户', '合规管理')
WHERE a.category_name = '对外部客户'
  AND (c.category_name = '合规管理' OR a.sub_category_name = '系统稽核'
       OR a.source_table IN ('system_jhd', 'system_jhd_detail'));
-- Category IDs in existing audit alarms/strategies remain valid (the ID is preserved).

UPDATE health_snapshot_1h h
LEFT JOIN metric_item m ON h.metric_id = m.id
LEFT JOIN metric_category c ON m.category_id = c.id
SET h.category_name = '合规管理'
WHERE h.category_name = '对外部客户'
  AND (c.category_name = '合规管理' OR h.subcategory_name = '系统稽核');

UPDATE health_snapshot_6h h
LEFT JOIN metric_item m ON h.metric_id = m.id
LEFT JOIN metric_category c ON m.category_id = c.id
SET h.category_name = '合规管理'
WHERE h.category_name = '对外部客户'
  AND (c.category_name = '合规管理' OR h.subcategory_name = '系统稽核');

UPDATE health_snapshot_1d h
LEFT JOIN metric_item m ON h.metric_id = m.id
LEFT JOIN metric_category c ON m.category_id = c.id
SET h.category_name = '合规管理'
WHERE h.category_name = '对外部客户'
  AND (c.category_name = '合规管理' OR h.subcategory_name = '系统稽核');

UPDATE health_snapshot_7d h
LEFT JOIN metric_item m ON h.metric_id = m.id
LEFT JOIN metric_category c ON m.category_id = c.id
SET h.category_name = '合规管理'
WHERE h.category_name = '对外部客户'
  AND (c.category_name = '合规管理' OR h.subcategory_name = '系统稽核');

UPDATE health_snapshot_30d h
LEFT JOIN metric_item m ON h.metric_id = m.id
LEFT JOIN metric_category c ON m.category_id = c.id
SET h.category_name = '合规管理'
WHERE h.category_name = '对外部客户'
  AND (c.category_name = '合规管理' OR h.subcategory_name = '系统稽核');
