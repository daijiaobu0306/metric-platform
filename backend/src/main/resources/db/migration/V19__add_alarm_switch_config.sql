-- 告警/工单启停控制配置表
CREATE TABLE IF NOT EXISTS `alarm_switch_config` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `config_key` VARCHAR(50) NOT NULL UNIQUE COMMENT '配置键',
    `config_value` INT DEFAULT 1 COMMENT '配置值：1启用 0禁用',
    `description` VARCHAR(200) COMMENT '描述',
    `update_time` DATETIME COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警/工单开关配置表';

-- 初始化默认配置
INSERT INTO `alarm_switch_config` (`config_key`, `config_value`, `description`) VALUES
('alarm_global_switch', 1, '告警全局开关 - 1启用 0禁用'),
('gongdan_global_switch', 1, '工单全局开关 - 1启用 0禁用');
