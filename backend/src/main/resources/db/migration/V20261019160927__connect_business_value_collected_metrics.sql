-- Replace the old derived current values with the actual collected business-value observations.
-- Safe to replay: only the first transition removes derived realtime rows and preserves old rules.
DELETE r FROM metric_realtime r
JOIN metric_item m ON m.id=r.metric_id JOIN metric_category c ON c.id=m.category_id
WHERE c.category_code='businessValue' AND (m.calc_rule_type IS NULL OR m.calc_rule_type<>'collected_value');

UPDATE metric_item m JOIN metric_category c ON c.id=m.category_id
SET m.calc_extra=JSON_OBJECT('sourceCode','business_value','parser','mapped-file',
    'previousRuleType',m.calc_rule_type,'previousSourceTable',m.calc_source_table,'previousExtra',m.calc_extra),
    m.calc_rule_type='collected_value',m.calc_source_table='collection_row',m.calc_field='metric_value',m.data_source='business_value'
WHERE c.category_code='businessValue' AND (m.calc_rule_type IS NULL OR m.calc_rule_type<>'collected_value');
