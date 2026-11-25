-- 为 inner_step_response 表添加 step_type 字段
-- 用于区分步骤类型：页面加载 / 步骤时长
-- 步骤名称中含有"页面"的为"页面加载"，其它为"步骤时长"

-- 添加 step_type 字段
ALTER TABLE inner_step_response
ADD COLUMN step_type VARCHAR(50) COMMENT '步骤类型: 页面加载/步骤时长' AFTER sub_category;

-- 根据 step_name 字段更新 step_type 的值
UPDATE inner_step_response
SET step_type = CASE
    WHEN step_name LIKE '%页面%' THEN '页面加载'
    ELSE '步骤时长'
END;

-- 添加索引
ALTER TABLE inner_step_response ADD INDEX idx_step_type (step_type);