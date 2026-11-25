-- 指标体系平台数据库初始化脚本
-- 数据库名: system_operations_metrics

-- 创建数据库
CREATE DATABASE IF NOT EXISTS system_operations_metrics DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE system_operations_metrics;

-- ============================================
-- 1. 系统信息表 (system_info)
-- ============================================
DROP TABLE IF EXISTS system_info;
CREATE TABLE system_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    system_code VARCHAR(50) NOT NULL UNIQUE COMMENT '系统编码',
    system_name VARCHAR(100) NOT NULL COMMENT '系统名称',
    description VARCHAR(500) COMMENT '系统描述',
    weight DECIMAL(5,2) DEFAULT 1.00 COMMENT '权重(0-1)',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统信息表';

-- ============================================
-- 2. 指标大类表 (metric_category)
-- ============================================
DROP TABLE IF EXISTS metric_category;
CREATE TABLE metric_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    system_id BIGINT NOT NULL COMMENT '系统ID',
    category_code VARCHAR(50) NOT NULL COMMENT '指标大类编码',
    category_name VARCHAR(100) NOT NULL COMMENT '指标大类名称',
    category_type VARCHAR(50) COMMENT '指标大类类型',
    description VARCHAR(500) COMMENT '描述',
    weight DECIMAL(5,2) DEFAULT 1.00 COMMENT '权重(0-1)',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_system_id (system_id),
    UNIQUE KEY uk_system_category (system_id, category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标大类表';

-- ============================================
-- 3. 具体指标表 (metric_item)
-- ============================================
DROP TABLE IF EXISTS metric_item;
CREATE TABLE metric_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    category_id BIGINT NOT NULL COMMENT '指标大类ID',
    metric_code VARCHAR(50) NOT NULL COMMENT '指标编码',
    metric_name VARCHAR(100) NOT NULL COMMENT '指标名称',
    metric_type VARCHAR(50) COMMENT '指标类型',
    metric_definition VARCHAR(500) COMMENT '指标定义',
    unit VARCHAR(20) COMMENT '单位',
    data_source VARCHAR(100) COMMENT '数据来源',
    collect_frequency VARCHAR(50) COMMENT '采集频率',
    baseline_value DECIMAL(10,2) COMMENT '基线值',
    normal_min DECIMAL(10,2) COMMENT '正常最小值',
    normal_max DECIMAL(10,2) COMMENT '正常最大值',
    alarm_threshold DECIMAL(10,2) COMMENT '告警阈值',
    alarm_threshold_type VARCHAR(20) COMMENT '告警阈值类型: >, <, =, >=, <=',
    gongdan_threshold DECIMAL(10,2) COMMENT '工单阈值',
    gongdan_threshold_type VARCHAR(20) COMMENT '工单阈值类型',
    weight DECIMAL(5,2) DEFAULT 1.00 COMMENT '权重(0-1)',
    trend_direction VARCHAR(20) DEFAULT 'auto' COMMENT '趋势方向: auto, up, down',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_category_id (category_id),
    UNIQUE KEY uk_category_metric (category_id, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='具体指标表';

-- ============================================
-- 4. 告警表 (alarm)
-- ============================================
DROP TABLE IF EXISTS alarm;
CREATE TABLE alarm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    system_id BIGINT COMMENT '系统ID',
    category_id BIGINT COMMENT '指标大类ID',
    metric_id BIGINT COMMENT '指标ID',
    alarm_type VARCHAR(50) COMMENT '告警类型: 指标值,健康度',
    metric_value DECIMAL(10,2) COMMENT '指标值',
    threshold_value DECIMAL(10,2) COMMENT '阈值',
    health_score INT COMMENT '健康度分数',
    alarm_content TEXT COMMENT '告警内容',
    alarm_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '告警生成时间',
    process_status TINYINT DEFAULT 0 COMMENT '处理状态: 0-未处理, 1-已处理',
    process_time DATETIME COMMENT '处理时间',
    process_user VARCHAR(50) COMMENT '处理人',
    shield_status TINYINT DEFAULT 0 COMMENT '屏蔽状态: 0-未屏蔽, 1-已屏蔽',
    shield_end_time DATETIME COMMENT '屏蔽结束时间',
    tag_status TINYINT DEFAULT 0 COMMENT '打标状态: 0-未打标, 1-已打标',
    tag_time DATETIME COMMENT '打标时间',
    responsible_person VARCHAR(50) COMMENT '责任人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_alarm_time (alarm_time),
    INDEX idx_process_status (process_status),
    INDEX idx_system_id (system_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警表';

-- ============================================
-- 5. 工单表 (gongdan)
-- ============================================
DROP TABLE IF EXISTS gongdan;
CREATE TABLE gongdan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    gongdan_id VARCHAR(50) COMMENT '工单ID(外部系统)',
    system_id BIGINT COMMENT '系统ID',
    category_id BIGINT COMMENT '指标大类ID',
    metric_id BIGINT COMMENT '指标ID',
    gongdan_title VARCHAR(200) NOT NULL COMMENT '工单标题',
    gongdan_content TEXT COMMENT '工单内容',
    gongdan_type VARCHAR(50) COMMENT '工单类型: 自动,手动',
    current_step VARCHAR(100) COMMENT '当前环节',
    current_handler VARCHAR(50) COMMENT '当前处理人',
    closed_status TINYINT DEFAULT 0 COMMENT '关单状态: 0-未关单, 1-已关单',
    closed_time DATETIME COMMENT '关单时间',
    gongdan_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '工单生成时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_gongdan_time (gongdan_time),
    INDEX idx_closed_status (closed_status),
    INDEX idx_system_id (system_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单表';

-- ============================================
-- 6. 问题表 (issue)
-- ============================================
DROP TABLE IF EXISTS issue;
CREATE TABLE issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    issue_code VARCHAR(50) COMMENT '问题编号',
    issue_type VARCHAR(50) COMMENT '问题类型',
    system_id BIGINT COMMENT '系统ID',
    category_id BIGINT COMMENT '指标大类ID',
    metric_id BIGINT COMMENT '指标ID',
    issue_description TEXT COMMENT '问题描述',
    issue_attribute VARCHAR(50) COMMENT '问题属性: 新问题,老问题',
    issue_source VARCHAR(50) COMMENT '问题来源: 告警,人工巡检',
    discoverer VARCHAR(50) COMMENT '发现者',
    responsible_person VARCHAR(50) COMMENT '责任人',
    check_reason TEXT COMMENT '核查原因',
    plan_resolve_time DATETIME COMMENT '预计解决时间',
    resolve_status VARCHAR(50) DEFAULT '未解决' COMMENT '解决状态',
    resolve_time DATETIME COMMENT '解决时间',
    post_evaluation TEXT COMMENT '后评估',
    tag_status TINYINT DEFAULT 0 COMMENT '打标状态: 0-未打标, 1-已打标',
    tag_time DATETIME COMMENT '打标时间',
    register_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
    modify_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_register_time (register_time),
    INDEX idx_resolve_status (resolve_status),
    INDEX idx_system_id (system_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问题表';

-- ============================================
-- 7. 告警策略表 (alarm_strategy)
-- ============================================
DROP TABLE IF EXISTS alarm_strategy;
CREATE TABLE alarm_strategy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    strategy_name VARCHAR(100) NOT NULL COMMENT '策略名称',
    strategy_type VARCHAR(50) NOT NULL COMMENT '策略类型: 指标值,健康度',
    target_type VARCHAR(50) NOT NULL COMMENT '目标类型: system,category,metric',
    target_id BIGINT COMMENT '目标ID',
    condition_type VARCHAR(50) COMMENT '条件类型: >, <, =, >=, <=',
    threshold_value DECIMAL(10,2) COMMENT '阈值',
    consecutive_times INT DEFAULT 1 COMMENT '连续次数',
    generate_type VARCHAR(50) DEFAULT 'alarm' COMMENT '生成类型: alarm-告警, gongdan-工单',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警策略表';

-- ============================================
-- 8. 系统配置表 (system_config)
-- ============================================
DROP TABLE IF EXISTS system_config;
CREATE TABLE system_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    config_type VARCHAR(50) COMMENT '配置类型: platform_name, theme, etc.',
    description VARCHAR(500) COMMENT '描述',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- ============================================
-- 9. 指标数据表 (metric_data) - 存储采集的指标数据
-- ============================================
DROP TABLE IF EXISTS metric_data;
CREATE TABLE metric_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    metric_id BIGINT NOT NULL COMMENT '指标ID',
    metric_value DECIMAL(10,4) NOT NULL COMMENT '指标值',
    collect_time DATETIME NOT NULL COMMENT '采集时间',
    health_score INT COMMENT '健康度分数',
    is_anomaly TINYINT DEFAULT 0 COMMENT '是否异常: 0-正常, 1-异常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_metric_id (metric_id),
    INDEX idx_collect_time (collect_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标数据表';

-- ============================================
-- 初始化默认数据
-- ============================================

-- 插入默认系统(CRM系统)
INSERT INTO system_info (system_code, system_name, description, weight, status)
VALUES ('CRM', 'CRM系统', '客户关系管理系统', 1.0, 1);

-- 插入CRM系统的4大指标大类
INSERT INTO metric_category (system_id, category_code, category_name, category_type, description, weight, status)
VALUES
(1, 'EXTERNAL_CUSTOMER', '对外部客户', 'customer', '客户体验相关指标', 0.25, 1),
(1, 'INTERNAL_STAFF', '对内部人员', 'staff', '内部人员使用体验指标', 0.25, 1),
(1, 'SYSTEM_RUN', '系统运行', 'system', '系统运行质量指标', 0.25, 1),
(1, 'PLAN_EXECUTE', '计划执行', 'plan', '工单闭环处理指标', 0.25, 1);

-- 插入部分对外部客户-缴费开机指标
INSERT INTO metric_item (category_id, metric_code, metric_name, metric_type, metric_definition, unit, alarm_threshold, alarm_threshold_type, trend_direction, status)
VALUES
(1, 'PAY001', '缴费上账平均时长', '时长', '抽取1天的缴费工单，统计从工单生成时间到工单完工时间的平均时长', '秒', 5, '>', 'down', 1),
(1, 'PAY002', '1分钟上账及时率', '比率', '抽取1天的缴费工单，统计1分钟以内完成的工单占比', '%', 99, '<', 'up', 1),
(1, 'PAY003', '对账差异率', '比率', '抽取1天对账差异记录数占比', '%', 1, '>', 'down', 1),
(1, 'XK001', '信控开机工单处理平均时长', '时长', '抽取1天的信控开机工单平均处理时长', '秒', 5, '>', 'down', 1),
(1, 'XK002', '1分钟处理及时率', '比率', '信控开机工单1分钟完成占比', '%', 99, '<', 'up', 1);

-- 插入系统默认配置
INSERT INTO system_config (config_key, config_value, config_type, description, status)
VALUES
('platform_name', '指标体系平台', 'platform', '平台名称', 1),
('platform_logo', '', 'platform', '平台Logo配置', 1),
('alarm_check_cron', '0 */5 * * * ?', 'alarm', '告警检查定时任务表达式', 1),
('data_retention_days', '90', 'data', '数据保留天数', 1);

-- 插入告警策略示例
INSERT INTO alarm_strategy (strategy_name, strategy_type, target_type, target_id, condition_type, threshold_value, consecutive_times, generate_type, status)
VALUES
('指标值告警-缴费上账平均时长', 'metric', 'metric', 1, '>', 5, 3, 'alarm', 1),
('健康度告警-缴费上账平均时长', 'health', 'metric', 1, '<', 70, 3, 'alarm', 1),
('指标大类健康度告警-对外部客户', 'health', 'category', 1, '<', 70, 3, 'alarm', 1),
('系统健康度告警-CRM系统', 'health', 'system', 1, '<', 70, 3, 'alarm', 1);
