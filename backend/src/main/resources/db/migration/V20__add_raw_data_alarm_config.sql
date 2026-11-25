-- 原始数据动态告警阈值配置表
CREATE TABLE IF NOT EXISTS `raw_data_alarm_config` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `table_name` VARCHAR(50) NOT NULL COMMENT '数据表名',
    `filter_field` VARCHAR(50) COMMENT '过滤字段',
    `filter_value` VARCHAR(100) COMMENT '过滤值',
    `metric_field` VARCHAR(50) NOT NULL COMMENT '指标字段',
    `calc_method` VARCHAR(20) DEFAULT 'AVG' COMMENT '计算方法：AVG/SUM/COUNT/MAX/MIN',
    `time_window_minutes` INT DEFAULT 60 COMMENT '时间窗口（分钟）',
    `threshold_multiplier` DECIMAL(5,2) DEFAULT 2.00 COMMENT '阈值倍数（用于动态阈值）',
    `alarm_threshold` DECIMAL(15,4) COMMENT '固定告警阈值',
    `alarm_status` INT DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `description` VARCHAR(200) COMMENT '描述',
    `create_time` DATETIME COMMENT '创建时间',
    `update_time` DATETIME COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始数据动态告警配置表';
