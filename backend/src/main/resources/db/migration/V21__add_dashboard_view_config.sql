-- 专屏页面配置表
CREATE TABLE IF NOT EXISTS `dashboard_view_config` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `view_name` VARCHAR(100) NOT NULL COMMENT '视图名称',
    `view_code` VARCHAR(50) NOT NULL UNIQUE COMMENT '视图代码',
    `category_name` VARCHAR(50) COMMENT '指标大类',
    `sub_category_name` VARCHAR(50) COMMENT '指标小类',
    `layout_config` TEXT COMMENT '布局配置(JSON)',
    `module_config` TEXT COMMENT '模块配置(JSON)',
    `is_default` INT DEFAULT 0 COMMENT '是否默认：1默认 0非默认',
    `sort_order` INT DEFAULT 0 COMMENT '排序',
    `status` INT DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `create_time` DATETIME COMMENT '创建时间',
    `update_time` DATETIME COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专屏页面配置表';
