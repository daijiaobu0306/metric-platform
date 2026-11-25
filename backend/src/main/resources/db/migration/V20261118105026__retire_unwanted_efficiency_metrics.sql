-- Retire the two unwanted metrics while preserving collected history and references.
UPDATE metric_item m JOIN metric_category c ON c.id = m.category_id
SET m.status = 0
WHERE c.category_code = 'system_run'
  AND m.metric_code IN ('EFF_GREEN_205', 'EFF_GREEN_206');
