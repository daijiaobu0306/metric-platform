-- 告警策略表增加告警/工单独立配置字段
-- 支持 targetType: system/category/subCategory/metric

ALTER TABLE alarm_strategy ADD COLUMN alarm_enabled INT DEFAULT 1 COMMENT '告警是否启用 (0/1)';
ALTER TABLE alarm_strategy ADD COLUMN alarm_threshold DECIMAL(15,4) COMMENT '告警阈值';
ALTER TABLE alarm_strategy ADD COLUMN alarm_condition VARCHAR(20) DEFAULT '>' COMMENT '告警条件 (>/</>=/<=/=/!=/like)';
ALTER TABLE alarm_strategy ADD COLUMN alarm_consecutive_times INT DEFAULT 1 COMMENT '告警连续触发次数';

ALTER TABLE alarm_strategy ADD COLUMN gongdan_enabled INT DEFAULT 1 COMMENT '工单是否启用 (0/1)';
ALTER TABLE alarm_strategy ADD COLUMN gongdan_threshold DECIMAL(15,4) COMMENT '工单阈值';
ALTER TABLE alarm_strategy ADD COLUMN gongdan_condition VARCHAR(20) DEFAULT '>' COMMENT '工单条件';
ALTER TABLE alarm_strategy ADD COLUMN gongdan_consecutive_times INT DEFAULT 1 COMMENT '工单连续触发次数';

-- 迁移旧数据：根据 generate_type 设置对应的开关
UPDATE alarm_strategy SET alarm_enabled = 1, alarm_threshold = threshold_value, alarm_condition = condition_type, alarm_consecutive_times = COALESCE(consecutive_times, 1) WHERE generate_type = 'alarm';
UPDATE alarm_strategy SET gongdan_enabled = 1, gongdan_threshold = threshold_value, gongdan_condition = condition_type, gongdan_consecutive_times = COALESCE(consecutive_times, 1) WHERE generate_type = 'gongdan';

-- 保留旧字段（标记为废弃）
