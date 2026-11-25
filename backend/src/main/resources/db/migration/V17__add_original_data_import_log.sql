-- 原始数据导入日志表
CREATE TABLE IF NOT EXISTS original_data_import_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_category_code VARCHAR(50) NOT NULL COMMENT '指标小类编码',
    table_name VARCHAR(100) NOT NULL COMMENT '目标表名',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    record_count INT COMMENT '导入记录数',
    import_time DATETIME COMMENT '导入时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_import_time (import_time),
    INDEX idx_sub_category_code (sub_category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始数据导入日志';