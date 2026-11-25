-- Correct resource input to consume upstream metric values directly; keep old detail batches for audit.
UPDATE metric_item m JOIN metric_category c ON c.id=m.category_id
SET m.calc_rule_type='resource_value',m.calc_field='metric_value',
    m.calc_extra='{"sourceCode":"resource_input","protocol":"metric-values-v1","thresholdNote":"源清单未提供阈值，暂不启用阈值告警"}'
WHERE c.category_code='resourceInput' AND m.calc_rule_type='resource_demand';

UPDATE metric_realtime r JOIN metric_item m ON m.id=r.metric_id JOIN metric_category c ON c.id=m.category_id
SET r.metric_value=NULL,r.data_time=NULL,r.is_exceed=0,r.exceed_count=0,r.status='缺数',r.update_time=NOW()
WHERE c.category_code='resourceInput' AND m.calc_rule_type='resource_value'
  AND NOT EXISTS (SELECT 1 FROM collection_file f JOIN collection_row cr ON cr.batch_id=f.id
                  WHERE f.source_code='resource_input' AND JSON_CONTAINS_PATH(cr.data_json,'one','$.metric_code'));

UPDATE collection_source SET config_json=JSON_SET(config_json,
    '$.parser','resource-values','$.filePattern','*_resource_input_values.csv',
    '$.fields',CAST('[{"source":"指标编码","target":"metric_code","type":"string","required":true},{"source":"指标名称","target":"metric_name","type":"string","required":true},{"source":"指标值","target":"metric_value","type":"decimal","required":true},{"source":"单位","target":"unit","type":"string","required":true},{"source":"数据时间","target":"data_time","type":"datetime","required":true},{"source":"数据来源标记","target":"provenance","type":"string","required":true}]' AS JSON)),next_scan_at=NOW(),last_error=NULL
WHERE code='resource_input' AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='resource-demand';
