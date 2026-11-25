-- 小类健康度历史表
CREATE TABLE IF NOT EXISTS sub_category_health_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(50) NOT NULL COMMENT '指标大类名称',
    sub_category_name VARCHAR(50) NOT NULL COMMENT '指标小类名称',
    health_score INT COMMENT '健康度分数（0-100）',
    status VARCHAR(20) COMMENT '状态：normal/warning/alarm',
    normal_count INT COMMENT '正常指标数',
    warning_count INT COMMENT '预警指标数',
    alarm_count INT COMMENT '告警指标数',
    record_time DATETIME NOT NULL COMMENT '记录时间',
    UNIQUE KEY uk_category_sub_record (category_name, sub_category_name, record_time),
    KEY idx_subcategory_health_record_time (record_time),
    KEY idx_subcategory_health_sub_category (sub_category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标小类健康度历史表';