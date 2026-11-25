-- 系统异常-接口原始数据
CREATE TABLE metric_error_interface (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点(导入时间)',
    interface_url VARCHAR(500) COMMENT '接口URL',
    access_count BIGINT COMMENT '访问量',
    failure_count BIGINT COMMENT '失败量',
    success_rate DECIMAL(10,4) COMMENT '成功率(%)',
    source_file VARCHAR(200) COMMENT '来源文件名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_time (data_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统异常-接口原始数据';

-- 系统异常-接口整体成功率(指标3计算结果)
CREATE TABLE metric_error_interface_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点',
    total_access BIGINT COMMENT '总访问量',
    total_failure BIGINT COMMENT '总失败量',
    success_rate DECIMAL(10,4) COMMENT '成功率(%)',
    source_file VARCHAR(200) COMMENT '来源文件名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_time (data_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统异常-接口整体成功率';

-- 系统异常-实例异常
CREATE TABLE metric_error_instance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点',
    app_name VARCHAR(100) COMMENT '应用名',
    error_name VARCHAR(200) COMMENT '异常名称',
    error_count BIGINT COMMENT '异常次数',
    source_file VARCHAR(200) COMMENT '来源文件名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_time (data_time),
    INDEX idx_app_error (app_name, error_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统异常-实例异常';

-- 系统异常-JS异常
CREATE TABLE metric_error_js (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点',
    error_name VARCHAR(500) COMMENT '报错名称',
    error_count BIGINT COMMENT '报错次数',
    total_error_count BIGINT COMMENT '总报错量',
    source_file VARCHAR(200) COMMENT '来源文件名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_time (data_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统异常-JS异常';

-- 系统异常-业务返回异常
CREATE TABLE metric_error_biz_return (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点',
    system_name VARCHAR(100) COMMENT '系统名',
    return_info VARCHAR(500) COMMENT '自定义返回信息',
    count BIGINT COMMENT '次数',
    source_file VARCHAR(200) COMMENT '来源文件名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_time (data_time),
    INDEX idx_system_info (system_name, return_info)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统异常-业务返回异常';

-- 系统异常-返回码
CREATE TABLE metric_error_return_code (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点',
    return_code VARCHAR(100) COMMENT '业务自定义返回码',
    count BIGINT COMMENT '返回次数',
    source_file VARCHAR(200) COMMENT '来源文件名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_time (data_time),
    INDEX idx_return_code (return_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统异常-返回码';

-- 系统异常-关键词配置
CREATE TABLE metric_error_keywords (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_id BIGINT NOT NULL COMMENT '关联metric_item.id',
    keyword VARCHAR(100) NOT NULL COMMENT '关键词',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_metric_id (metric_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统异常-关键词配置';

-- 指标导入配置表
CREATE TABLE metric_import_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_category_code VARCHAR(50) NOT NULL COMMENT '指标小类编码',
    import_path VARCHAR(500) NOT NULL COMMENT '监控目录(相对路径)',
    archive_path VARCHAR(500) NOT NULL COMMENT '归档目录(相对路径)',
    scan_interval_minutes INT DEFAULT 5 COMMENT '扫描间隔(分钟)',
    file_pattern VARCHAR(100) DEFAULT '*.xlsx,*.csv' COMMENT '匹配文件模式',
    enabled INT DEFAULT 1 COMMENT '是否启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sub_category (sub_category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标导入配置表';

-- metric_item 增加计算规则字段
ALTER TABLE metric_item ADD COLUMN calc_rule_type VARCHAR(50) COMMENT '规则类型: count_gt, count_lt, fixed_calc, distinct_count, keyword_match';
ALTER TABLE metric_item ADD COLUMN calc_source_table VARCHAR(100) COMMENT '原始数据表名';
ALTER TABLE metric_item ADD COLUMN calc_field VARCHAR(50) COMMENT '计算字段';
ALTER TABLE metric_item ADD COLUMN calc_extra VARCHAR(500) COMMENT '额外参数(JSON)';
