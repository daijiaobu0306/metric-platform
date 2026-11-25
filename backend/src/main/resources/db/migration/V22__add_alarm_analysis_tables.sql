-- 告警规律分析报告表
CREATE TABLE IF NOT EXISTS `alarm_analysis_report` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `report_date` DATE NOT NULL COMMENT '报告日期',
    `metric_name` VARCHAR(100) COMMENT '指标名称',
    `alarm_count` INT COMMENT '告警次数',
    `avg_value` DECIMAL(15,4) COMMENT '平均值',
    `max_value` DECIMAL(15,4) COMMENT '最大值',
    `rule_type` VARCHAR(50) COMMENT '规则类型',
    `rule_description` TEXT COMMENT '规则描述',
    `suggestion` TEXT COMMENT '建议',
    `create_time` DATETIME COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规律分析报告表';

-- 告警规律分析配置表
CREATE TABLE IF NOT EXISTS `alarm_analysis_config` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `cron_expression` VARCHAR(50) DEFAULT '0 0 2 * * ?' COMMENT 'Cron表达式',
    `analysis_enabled` INT DEFAULT 1 COMMENT '是否启用：1启用 0禁用',
    `lookback_days` INT DEFAULT 7 COMMENT '回顾天数',
    `update_time` DATETIME COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规律分析配置表';

-- 初始化默认配置
INSERT INTO `alarm_analysis_config` (`cron_expression`, `analysis_enabled`, `lookback_days`) VALUES
('0 0 2 * * ?', 1, 7);
