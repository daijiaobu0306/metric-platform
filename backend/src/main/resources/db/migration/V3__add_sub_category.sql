-- 添加指标小类字段
ALTER TABLE metric_item ADD COLUMN sub_category_name VARCHAR(100) COMMENT '指标小类名称' AFTER category_id;
ALTER TABLE metric_item ADD COLUMN sub_category_code VARCHAR(50) COMMENT '指标小类编码' AFTER sub_category_name;

-- 添加索引
ALTER TABLE metric_item ADD INDEX idx_sub_category_code (sub_category_code);
