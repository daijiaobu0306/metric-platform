-- 原始数据告警配置表V2版，重构为支持个性化阈值
-- 废弃原有的raw_data_alarm_config表配置，创建新的统一配置

-- 先清空旧数据（如果需要迁移可以先备份）
-- TRUNCATE TABLE raw_data_alarm_config;

ALTER TABLE raw_data_alarm_config
    ADD COLUMN filter_field VARCHAR(50) COMMENT '过滤字段（维度）' AFTER metric_field,
    ADD COLUMN filter_value VARCHAR(100) COMMENT '过滤值' AFTER filter_field,
    ADD COLUMN source_id_field VARCHAR(50) COMMENT '源记录ID字段' AFTER filter_value,
    ADD COLUMN agg_method VARCHAR(20) DEFAULT 'NONE' COMMENT '聚合方法:NONE/AVG/SUM/COUNT/MAX/MIN' AFTER source_id_field,
    ADD COLUMN global_threshold DECIMAL(15,4) COMMENT '全局阈值' AFTER agg_method,
    ADD COLUMN global_condition VARCHAR(10) DEFAULT '>' COMMENT '全局条件' AFTER global_threshold,
    ADD COLUMN dimension_thresholds JSON COMMENT '维度阈值配置：[{"filterValue":"xxx","threshold":123,"condition":">"}]' AFTER global_condition,
    ADD COLUMN object_thresholds JSON COMMENT '对象阈值配置：[{"sourceId":123,"threshold":456,"condition":">"}]' AFTER dimension_thresholds,
    ADD COLUMN strategy_id BIGINT COMMENT '关联策略ID' AFTER description;

-- 更新现有记录的默认值
UPDATE raw_data_alarm_config SET global_condition = '>' WHERE global_condition IS NULL OR global_condition = '';
UPDATE raw_data_alarm_config SET agg_method = 'NONE' WHERE agg_method IS NULL OR agg_method = '';
