CREATE TABLE IF NOT EXISTS weight_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    system_id BIGINT COMMENT '系统ID',
    category_id BIGINT COMMENT '指标大类ID',
    sub_category_name VARCHAR(50) COMMENT '指标小类名称',
    metric_id BIGINT COMMENT '具体指标ID',
    weight DECIMAL(5,2) NOT NULL COMMENT '权重值(0-1)',
    weight_type VARCHAR(20) COMMENT '权重类型: system/category/subcategory/metric',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_system_id (system_id),
    INDEX idx_category_id (category_id),
    INDEX idx_metric_id (metric_id),
    INDEX idx_weight_type (weight_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权重配置表';
