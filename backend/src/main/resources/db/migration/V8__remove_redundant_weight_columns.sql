-- 移除冗余的 weight 字段（权重配置已迁移到 weight_config 表）
-- 该迁移删除 system_info、metric_category、metric_item 表中的 weight 字段

-- 删除 system_info 表的 weight 字段
ALTER TABLE system_info DROP COLUMN IF EXISTS weight;

-- 删除 metric_category 表的 weight 字段
ALTER TABLE metric_category DROP COLUMN IF EXISTS weight;

-- 删除 metric_item 表的 weight 字段
ALTER TABLE metric_item DROP COLUMN IF EXISTS weight;