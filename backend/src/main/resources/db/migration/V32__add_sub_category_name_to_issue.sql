-- 为 issue 表添加 sub_category_name 字段，支持小类层级
ALTER TABLE issue ADD COLUMN sub_category_name VARCHAR(100) DEFAULT NULL COMMENT '指标小类名称';