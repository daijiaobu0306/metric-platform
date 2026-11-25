CREATE TABLE shield_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shield_type VARCHAR(20) NOT NULL COMMENT '屏蔽类型: SYSTEM/CATEGORY/METRIC',
    target_id BIGINT NOT NULL COMMENT '目标ID（系统ID/大类ID/指标ID）',
    target_name VARCHAR(200) COMMENT '目标名称',
    shield_end_time DATETIME COMMENT '屏蔽结束时间，null表示永久屏蔽',
    shield_time DATETIME NOT NULL COMMENT '屏蔽开始时间',
    create_time DATETIME COMMENT '创建时间',
    INDEX idx_shield_type_target (shield_type, target_id),
    INDEX idx_shield_end_time (shield_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
