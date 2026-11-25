-- 新增 metric_name 字段到 alarm 表，用于存储指标名称
ALTER TABLE alarm ADD COLUMN metric_name VARCHAR(200) DEFAULT NULL;