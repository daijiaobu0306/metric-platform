-- alarm_strategy表增加target_name字段，用于存储目标名称
ALTER TABLE alarm_strategy ADD COLUMN target_name VARCHAR(200) COMMENT '目标名称';
