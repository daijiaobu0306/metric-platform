-- User confirmed daily flows on 2026-11-17. Preserve legacy metrics/history and task schedule.

INSERT INTO metric_sub_category(category_id,sub_category_code,sub_category_name,status) SELECT id,'requirement_dev_boss','需求开发-boss','enabled' FROM metric_category WHERE category_code='resourceInput' AND NOT EXISTS(SELECT 1 FROM metric_sub_category s WHERE s.category_id=metric_category.id AND s.sub_category_name='需求开发-boss') ON DUPLICATE KEY UPDATE sub_category_code=VALUES(sub_category_code);

INSERT INTO metric_item(category_id,sub_category_code,sub_category_name,metric_code,metric_name,metric_type,metric_definition,statistics_caliber,unit,data_source,collect_frequency,data_sync_mode,status) SELECT c.id,s.sub_category_code,'需求开发-boss','requirementCountBoss','需求数量','导入','统计需求总量','所选数据日期区间内每日需求数量之和','条','resource_input','天','文件',1 FROM metric_category c JOIN metric_sub_category s ON s.category_id=c.id AND s.sub_category_name='需求开发-boss' WHERE c.category_code='resourceInput' ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code);

INSERT INTO metric_item(category_id,sub_category_code,sub_category_name,metric_code,metric_name,metric_type,metric_definition,statistics_caliber,unit,data_source,collect_frequency,data_sync_mode,status) SELECT c.id,s.sub_category_code,'需求开发-boss','totalRequirementDurationBoss','需求投入总工时','导入','计算所有需求的处理工时之和','所选数据日期区间内每日需求投入总工时之和','小时','resource_input','天','文件',1 FROM metric_category c JOIN metric_sub_category s ON s.category_id=c.id AND s.sub_category_name='需求开发-boss' WHERE c.category_code='resourceInput' ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code);

INSERT INTO metric_item(category_id,sub_category_code,sub_category_name,metric_code,metric_name,metric_type,metric_definition,statistics_caliber,unit,data_source,collect_frequency,data_sync_mode,status) SELECT c.id,s.sub_category_code,'需求开发-boss','avgRequirementDurationBoss','需求投入平均工时','导入','计算单个需求的平均处理时长','区间总工时/区间需求数量；每日原值保留','小时','resource_input','天','文件',1 FROM metric_category c JOIN metric_sub_category s ON s.category_id=c.id AND s.sub_category_name='需求开发-boss' WHERE c.category_code='resourceInput' ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code);

INSERT INTO metric_sub_category(category_id,sub_category_code,sub_category_name,status) SELECT id,'requirement_dev_bass','需求开发-bass','enabled' FROM metric_category WHERE category_code='resourceInput' AND NOT EXISTS(SELECT 1 FROM metric_sub_category s WHERE s.category_id=metric_category.id AND s.sub_category_name='需求开发-bass') ON DUPLICATE KEY UPDATE sub_category_code=VALUES(sub_category_code);

INSERT INTO metric_item(category_id,sub_category_code,sub_category_name,metric_code,metric_name,metric_type,metric_definition,statistics_caliber,unit,data_source,collect_frequency,data_sync_mode,status) SELECT c.id,s.sub_category_code,'需求开发-bass','requirementCountBass','需求数量','导入','统计需求总量','所选数据日期区间内每日需求数量之和','条','resource_input','天','文件',1 FROM metric_category c JOIN metric_sub_category s ON s.category_id=c.id AND s.sub_category_name='需求开发-bass' WHERE c.category_code='resourceInput' ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code);

INSERT INTO metric_item(category_id,sub_category_code,sub_category_name,metric_code,metric_name,metric_type,metric_definition,statistics_caliber,unit,data_source,collect_frequency,data_sync_mode,status) SELECT c.id,s.sub_category_code,'需求开发-bass','totalRequirementDurationBass','需求投入总工时','导入','计算所有需求的处理工时之和','所选数据日期区间内每日需求投入总工时之和','小时','resource_input','天','文件',1 FROM metric_category c JOIN metric_sub_category s ON s.category_id=c.id AND s.sub_category_name='需求开发-bass' WHERE c.category_code='resourceInput' ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code);

INSERT INTO metric_item(category_id,sub_category_code,sub_category_name,metric_code,metric_name,metric_type,metric_definition,statistics_caliber,unit,data_source,collect_frequency,data_sync_mode,status) SELECT c.id,s.sub_category_code,'需求开发-bass','avgRequirementDurationBass','需求投入平均工时','导入','计算单个需求的平均处理时长','区间总工时/区间需求数量；每日原值保留','小时','resource_input','天','文件',1 FROM metric_category c JOIN metric_sub_category s ON s.category_id=c.id AND s.sub_category_name='需求开发-bass' WHERE c.category_code='resourceInput' ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code);

-- Optional provenance: retain supplied marks; do not invent marks for external CSV files.
UPDATE collection_source s
JOIN (SELECT code,MIN(j.ordinality)-1 AS field_index FROM collection_source,
  JSON_TABLE(config_json,'$.fields[*]' COLUMNS(ordinality FOR ORDINALITY,target VARCHAR(100) PATH '$.target')) j
  WHERE code='resource_input' AND j.target='provenance' GROUP BY code) selected ON selected.code=s.code
SET s.config_json=JSON_SET(s.config_json,CONCAT('$.fields[',selected.field_index,'].required'),CAST('false' AS JSON));
