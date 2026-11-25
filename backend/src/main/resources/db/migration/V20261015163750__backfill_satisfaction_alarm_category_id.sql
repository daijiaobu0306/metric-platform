-- Restore the category ID used by alarm filters; retain the recorded values and alarm state.
UPDATE alarm a
JOIN metric_category c ON c.category_code='satisfaction'
LEFT JOIN metric_item m ON m.id=a.metric_id
SET a.category_id=c.id
WHERE (m.category_id=c.id OR (a.metric_id IS NULL AND a.category_name=c.category_name)
       OR (m.id IS NULL AND a.category_name=c.category_name))
AND (a.category_id IS NULL OR a.category_id<>c.id);
