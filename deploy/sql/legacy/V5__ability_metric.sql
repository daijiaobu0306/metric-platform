-- 能力量纲数据表
CREATE TABLE IF NOT EXISTS ability_metric (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name VARCHAR(255),
    region_code VARCHAR(20),
    function_code VARCHAR(20),
    unified_capability_code VARCHAR(20),
    service_org_code VARCHAR(20),
    data_date VARCHAR(20),
    line_number INT,
    unified_scene_code VARCHAR(50),
    unified_service_code VARCHAR(50),
    ability_user_name VARCHAR(200),
    ability_user_code VARCHAR(50),
    ability_user_app_name VARCHAR(200),
    ability_user_app_code VARCHAR(50),
    ability_user_org_name VARCHAR(200),
    ability_user_org_code VARCHAR(50),
    portal_order_no VARCHAR(100),
    portal_sub_order_no VARCHAR(100),
    invoke_volume TEXT,
    invoke_failure TEXT,
    insert_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    unique_key VARCHAR(255) UNIQUE,

    INDEX idx_data_date (data_date),
    INDEX idx_unified_capability_code (unified_capability_code),
    INDEX idx_unified_service_code (unified_service_code),
    INDEX idx_ability_user_code (ability_user_code),
    INDEX idx_unified_scene_code (unified_scene_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 导入配置记录
INSERT INTO metric_import_config (sub_category_code, import_path, archive_path, scan_interval_minutes, file_pattern, enabled, create_time, update_time)
VALUES ('ability_metric', 'files/ability/import/', 'files/ability/archive/', 5, 'D_*.txt', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE update_time = NOW();
