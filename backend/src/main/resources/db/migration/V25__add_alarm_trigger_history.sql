-- 告警触发历史表，用于记录连续触发次数
CREATE TABLE IF NOT EXISTS alarm_trigger_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    metric_id BIGINT COMMENT '指标ID',
    source_table VARCHAR(50) COMMENT '数据源表名',
    source_id BIGINT COMMENT '数据源记录ID',
    trigger_value DECIMAL(15,4) NOT NULL COMMENT '触发时的指标值',
    threshold_value DECIMAL(15,4) NOT NULL COMMENT '触发时的阈值',
    trigger_time DATETIME NOT NULL COMMENT '触发时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_strategy_metric (strategy_id, metric_id),
    INDEX idx_trigger_time (trigger_time),
    INDEX idx_source (source_table, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警触发历史记录表';
