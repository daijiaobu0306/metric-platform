-- Resource input: daily complete demand snapshots. Source workbook has no alarm thresholds.
SET @resource_category = (SELECT id FROM metric_category WHERE category_code='resourceInput' LIMIT 1);
UPDATE metric_item SET metric_code=CASE metric_code
    WHEN 'demandCount' THEN 'requirementCount'
    WHEN 'demandAvgWorkhour' THEN 'avgRequirementDuration'
    WHEN 'demandTotalWorkhour' THEN 'totalRequirementDuration' ELSE metric_code END,
    collect_frequency='天',data_source='需求管理平台',calc_rule_type='resource_demand',
    calc_source_table='collection_row',calc_field='work_hours',
    alarm_threshold=NULL,alarm_threshold_type=NULL,gongdan_threshold=NULL,gongdan_threshold_type=NULL,
    calc_extra='{"sourceCode":"resource_input","snapshot":"daily","thresholdNote":"源清单未提供阈值，暂不启用阈值告警"}'
WHERE category_id=@resource_category AND metric_code IN ('demandCount','demandAvgWorkhour','demandTotalWorkhour','requirementCount','avgRequirementDuration','totalRequirementDuration');

INSERT INTO metric_monitor_config(metric_id,alarm_status,gongdan_status,alarm_threshold,gongdan_threshold,create_time,update_time)
SELECT id,0,0,NULL,NULL,NOW(),NOW() FROM metric_item WHERE category_id=@resource_category AND calc_rule_type='resource_demand'
ON DUPLICATE KEY UPDATE alarm_status=0,gongdan_status=0,alarm_threshold=NULL,gongdan_threshold=NULL,alarm_condition=NULL,gongdan_condition=NULL,update_time=NOW();
UPDATE alarm_strategy s JOIN metric_item m ON s.target_id=m.id
SET s.alarm_enabled=0,s.gongdan_enabled=0,s.alarm_threshold=NULL,s.gongdan_threshold=NULL,s.threshold_value=NULL
WHERE s.target_type='metric' AND s.strategy_type='metric' AND m.category_id=@resource_category AND m.calc_rule_type='resource_demand';

INSERT INTO collection_source(code,name,config_json,enabled,next_scan_at)
VALUES ('resource_input','资源投入 · 需求开发',
 '{"code":"resource_input","name":"资源投入 · 需求开发","importPath":"raw_data/import/resource_input","archivePath":"raw_data/archive/resource_input","intervalSeconds":86400,"settleSeconds":5,"parser":"resource-demand","format":"csv","filePattern":"*.csv","dateMode":"filename","dateRegex":"^([0-9]{4}-[0-9]{2}-[0-9]{2})[_-].*","dateFormat":"uuuu-MM-dd","enabled":true,"sheet":"","delimiter":",","encoding":"UTF-8","fields":[{"source":"需求编号","target":"demand_id","type":"string","required":true},{"source":"需求名称","target":"demand_name","type":"string","required":true},{"source":"投入工时","target":"work_hours","type":"decimal","required":true},{"source":"数据时间","target":"data_time","type":"datetime","required":true},{"source":"数据来源标记","target":"provenance","type":"string","required":true}]}',1,NOW())
ON DUPLICATE KEY UPDATE code=VALUES(code);
