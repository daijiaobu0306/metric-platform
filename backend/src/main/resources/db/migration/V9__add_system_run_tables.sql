-- 系统运行汇总指标表
CREATE TABLE metric_system_run_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点',
    sub_category_code VARCHAR(50) NOT NULL COMMENT '指标小类编码',
    metric_attr VARCHAR(50) COMMENT '指标属性：前端页面/后端服务',
    metric_name VARCHAR(100) COMMENT '指标名称',
    object_name VARCHAR(200) COMMENT '对象名称（如应用名）',
    metric_value DECIMAL(18,4) COMMENT '指标值',
    unit VARCHAR(20) COMMENT '单位',
    INDEX idx_data_time (data_time),
    INDEX idx_sub_category (sub_category_code)
) COMMENT '系统运行汇总指标';

-- 系统运行明细指标表
CREATE TABLE metric_system_run_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_time DATETIME NOT NULL COMMENT '数据时间点',
    sub_category_code VARCHAR(50) NOT NULL COMMENT '指标小类编码',
    metric_attr VARCHAR(50) COMMENT '指标属性：前端页面/后端服务',
    metric_name VARCHAR(100) COMMENT '指标名称',
    object_name VARCHAR(200) COMMENT '对象名称',
    object_url VARCHAR(500) COMMENT '对象URL',
    access_count BIGINT COMMENT '访问量',
    metric_value DECIMAL(18,4) COMMENT '指标值',
    unit VARCHAR(20) COMMENT '单位',
    INDEX idx_data_time (data_time),
    INDEX idx_sub_category (sub_category_code),
    INDEX idx_metric_name (metric_name)
) COMMENT '系统运行明细指标';