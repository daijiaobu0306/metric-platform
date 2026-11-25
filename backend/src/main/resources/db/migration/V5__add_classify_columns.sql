-- 为指标值历史表和健康度历史表添加一级/二级分类字段（用于系统稽核分组展示）
ALTER TABLE metric_value_history ADD COLUMN IF NOT EXISTS first_classify VARCHAR(100);
ALTER TABLE metric_value_history ADD COLUMN IF NOT EXISTS second_classify VARCHAR(100);
ALTER TABLE metric_health_history ADD COLUMN IF NOT EXISTS first_classify VARCHAR(100);
ALTER TABLE metric_health_history ADD COLUMN IF NOT EXISTS second_classify VARCHAR(100);