-- =============================================
-- 运营观测 - 多粒度健康度预聚合快照表
-- 5 张表按粒度分（1h/6h/1d/7d/30d），每张表里通过 level_type 区分层级
-- 数据由 HealthSnapshotAggregationScheduler 定时写入
-- 保留期由 DataRetentionScheduler 按 platform_config 配置清理
--
-- 注意：每行只设置对应层级的字段，其他字段保持 NULL
-- 例：metric 行只有 metric_id 有值，category/subcategory/system 字段均为 NULL
-- 这样 1 个组合 UNIQUE KEY (level_type, metric_id, subcategory_name, category_name, system_id, snapshot_time)
-- 能保证：(level=metric, metricId=X, null, null, null, time) 唯一；同一 metric 同一时间只 1 条
-- =============================================

-- 1 小时粒度快照表
CREATE TABLE IF NOT EXISTS health_snapshot_1h (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level_type VARCHAR(20) NOT NULL COMMENT '层级: metric/subcategory/category/system',
    metric_id BIGINT NULL COMMENT '指标ID（level_type=metric 时使用）',
    subcategory_name VARCHAR(100) NULL COMMENT '指标小类名（level_type=subcategory 时使用）',
    category_name VARCHAR(50) NULL COMMENT '指标大类名（level_type=category/subcategory 时使用）',
    system_id BIGINT NULL COMMENT '系统ID（level_type=system 时使用）',
    snapshot_time DATETIME NOT NULL COMMENT '快照代表的时间点（整点）',
    health_score INT NOT NULL COMMENT '健康度分数（0-100）',
    status VARCHAR(20) NOT NULL COMMENT '状态: normal/warning/alarm',
    normal_count INT DEFAULT 0 COMMENT '正常指标数',
    warning_count INT DEFAULT 0 COMMENT '预警指标数',
    alarm_count INT DEFAULT 0 COMMENT '告警指标数',
    aggregate_count INT DEFAULT 0 COMMENT '由多少条明细聚合而成',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 1 个组合 UNIQUE KEY 保证幂等（依靠 MySQL 允许多个 NULL）
    UNIQUE KEY uk_composite (level_type, metric_id, subcategory_name, category_name, system_id, snapshot_time),
    -- 查询优化索引
    INDEX idx_snapshot_time (snapshot_time),
    INDEX idx_level_metric (level_type, metric_id, snapshot_time),
    INDEX idx_level_subcategory (level_type, subcategory_name, snapshot_time),
    INDEX idx_level_category (level_type, category_name, snapshot_time),
    INDEX idx_level_system (level_type, system_id, snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康度1小时粒度快照';

-- 6 小时粒度快照表
CREATE TABLE IF NOT EXISTS health_snapshot_6h (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level_type VARCHAR(20) NOT NULL,
    metric_id BIGINT NULL,
    subcategory_name VARCHAR(100) NULL,
    category_name VARCHAR(50) NULL,
    system_id BIGINT NULL,
    snapshot_time DATETIME NOT NULL COMMENT '快照代表的时间点（6小时段起点）',
    health_score INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    normal_count INT DEFAULT 0,
    warning_count INT DEFAULT 0,
    alarm_count INT DEFAULT 0,
    aggregate_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_composite (level_type, metric_id, subcategory_name, category_name, system_id, snapshot_time),
    INDEX idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康度6小时粒度快照';

-- 1 天粒度快照表
CREATE TABLE IF NOT EXISTS health_snapshot_1d (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level_type VARCHAR(20) NOT NULL,
    metric_id BIGINT NULL,
    subcategory_name VARCHAR(100) NULL,
    category_name VARCHAR(50) NULL,
    system_id BIGINT NULL,
    snapshot_time DATETIME NOT NULL COMMENT '快照代表的时间点（当日 00:00）',
    health_score INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    normal_count INT DEFAULT 0,
    warning_count INT DEFAULT 0,
    alarm_count INT DEFAULT 0,
    aggregate_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_composite (level_type, metric_id, subcategory_name, category_name, system_id, snapshot_time),
    INDEX idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康度1天粒度快照';

-- 7 天粒度快照表
CREATE TABLE IF NOT EXISTS health_snapshot_7d (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level_type VARCHAR(20) NOT NULL,
    metric_id BIGINT NULL,
    subcategory_name VARCHAR(100) NULL,
    category_name VARCHAR(50) NULL,
    system_id BIGINT NULL,
    snapshot_time DATETIME NOT NULL COMMENT '快照代表的时间点（周一 00:00）',
    health_score INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    normal_count INT DEFAULT 0,
    warning_count INT DEFAULT 0,
    alarm_count INT DEFAULT 0,
    aggregate_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_composite (level_type, metric_id, subcategory_name, category_name, system_id, snapshot_time),
    INDEX idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康度7天粒度快照';

-- 30 天粒度快照表
CREATE TABLE IF NOT EXISTS health_snapshot_30d (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level_type VARCHAR(20) NOT NULL,
    metric_id BIGINT NULL,
    subcategory_name VARCHAR(100) NULL,
    category_name VARCHAR(50) NULL,
    system_id BIGINT NULL,
    snapshot_time DATETIME NOT NULL COMMENT '快照代表的时间点（每月1号 00:00）',
    health_score INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    normal_count INT DEFAULT 0,
    warning_count INT DEFAULT 0,
    alarm_count INT DEFAULT 0,
    aggregate_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_composite (level_type, metric_id, subcategory_name, category_name, system_id, snapshot_time),
    INDEX idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康度30天粒度快照';

-- =============================================
-- 健康度数据保留期配置（写入 platform_config 表）
-- 由 DataRetentionScheduler 每天凌晨 4 点按这些配置清理过期数据
-- =============================================
INSERT INTO platform_config (config_key, config_value, description) VALUES
    ('health_retention_1h_days', '30', '1小时快照表保留天数'),
    ('health_retention_6h_days', '90', '6小时快照表保留天数'),
    ('health_retention_1d_days', '365', '日级快照表保留天数'),
    ('health_retention_7d_days', '730', '周级快照表保留天数（2年）'),
    ('health_retention_30d_days', '1825', '月级快照表保留天数（5年）'),
    ('health_retention_detail_days', '7', '明细表保留天数（用于5m下钻）')
ON DUPLICATE KEY UPDATE description = VALUES(description);
