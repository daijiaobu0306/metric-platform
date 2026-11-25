-- 平台配置表
DROP TABLE IF EXISTS platform_config;
CREATE TABLE platform_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键: logo, platform_name, description',
    config_value TEXT COMMENT '配置值',
    description VARCHAR(500) COMMENT '配置说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台配置表';

-- 初始化默认数据
INSERT INTO platform_config (config_key, config_value, description) VALUES
('platform_name', '指标体系平台', '平台名称'),
('description', '系统指标管理平台', '平台描述'),
('logo', '', '平台Logo路径');
