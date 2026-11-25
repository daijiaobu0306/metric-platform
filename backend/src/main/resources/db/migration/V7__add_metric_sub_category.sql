CREATE TABLE IF NOT EXISTS metric_sub_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL COMMENT '指标大类ID',
    sub_category_code VARCHAR(50) NOT NULL COMMENT '指标小类编码',
    sub_category_name VARCHAR(100) NOT NULL COMMENT '指标小类名称',
    description VARCHAR(500) COMMENT '描述',
    status VARCHAR(10) DEFAULT 'enabled' COMMENT '状态: enabled/disabled',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_category_subcode (category_id, sub_category_code),
    INDEX idx_category_id (category_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标小类配置表';

-- 预设数据：对内部人员 (category_id=9)
INSERT INTO metric_sub_category (category_id, sub_category_code, sub_category_name, status) VALUES
(9, 'crm', 'CRM', 'enabled'),
(9, 'query', '查询', 'enabled'),
(9, 'chbn', '存储', 'enabled');

-- 预设数据：对外部客户 (category_id=8) - 从system_jhd提取
INSERT INTO metric_sub_category (category_id, sub_category_code, sub_category_name, status)
SELECT DISTINCT 8, sub_category_code, sub_category_name, 'enabled'
FROM metric_item WHERE category_id = 8 AND sub_category_code IS NOT NULL AND sub_category_code != '';
