-- 指标实时状态表
CREATE TABLE IF NOT EXISTS metric_realtime (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_id BIGINT NOT NULL,
    metric_value DECIMAL(15,4) COMMENT '最新计算的指标值',
    data_time DATETIME COMMENT '对应原始数据的data_time',
    is_exceed TINYINT(1) DEFAULT 0 COMMENT '当前是否超限',
    exceed_count INT DEFAULT 0 COMMENT '连续超限次数',
    last_exceed_time DATETIME COMMENT '最近一次超限时间',
    status VARCHAR(20) DEFAULT 'normal' COMMENT '状态: normal/alarM/gongdan',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_metric_id (metric_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 指标监控配置表
CREATE TABLE IF NOT EXISTS metric_monitor_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_id BIGINT NOT NULL,
    alarm_window_minutes INT DEFAULT 5 COMMENT '告警时间窗口（分钟）',
    alarm_threshold DECIMAL(15,4) COMMENT '告警阈值',
    alarm_condition VARCHAR(20) DEFAULT '>' COMMENT '告警条件: > < >= <=',
    alarm_status TINYINT(1) DEFAULT 1 COMMENT '告警是否启用',
    gongdan_window_minutes INT DEFAULT 30 COMMENT '工单时间窗口（分钟）',
    gongdan_threshold DECIMAL(15,4) COMMENT '工单阈值',
    gongdan_condition VARCHAR(20) DEFAULT '>' COMMENT '工单条件',
    consecutive_count INT DEFAULT 3 COMMENT '连续超限次数（触发工单前需连续超限几个周期）',
    gongdan_status TINYINT(1) DEFAULT 1 COMMENT '工单是否启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_metric_id (metric_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 添加注释
ALTER TABLE metric_realtime COMMENT '指标实时状态表';
ALTER TABLE metric_monitor_config COMMENT '指标监控配置表';
