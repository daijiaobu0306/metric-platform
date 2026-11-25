-- 插件配置表
CREATE TABLE IF NOT EXISTS plugin_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '接口名称',
    name_en VARCHAR(100) COMMENT '英文名称',
    plugin_type VARCHAR(50) DEFAULT 'HTTP_API' COMMENT '插件类型',
    description VARCHAR(500) COMMENT '描述',
    request_url VARCHAR(500) COMMENT '请求地址',
    request_method VARCHAR(20) COMMENT 'GET/POST/PUT/DELETE',
    request_headers TEXT COMMENT 'JSON: [{name, value, description}]',
    request_params TEXT COMMENT 'JSON: [{name, type, position, required, defaultValue, source, description}]',
    response_params TEXT COMMENT 'JSON: [{name, type, path, description}]',
    trigger_mode VARCHAR(50) DEFAULT 'MANUAL' COMMENT 'MANUAL/TIMER/AUTO',
    timer_cron VARCHAR(50) COMMENT '定时任务Cron表达式',
    debug_status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    debug_response TEXT COMMENT '最近调试响应',
    last_debug_time DATETIME COMMENT '最后调试时间',
    enabled TINYINT DEFAULT 1 COMMENT '启用状态',
    create_time DATETIME,
    update_time DATETIME
) COMMENT '插件配置表';

-- 插件执行日志表
CREATE TABLE IF NOT EXISTS plugin_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plugin_id BIGINT COMMENT '插件ID',
    business_type VARCHAR(50) COMMENT 'gongdan/alarm',
    business_id BIGINT COMMENT '对应工单/告警ID',
    request_data TEXT COMMENT '实际发送的请求数据',
    response_data TEXT COMMENT '响应数据',
    exec_status VARCHAR(20) COMMENT 'SUCCESS/FAILED',
    error_message TEXT COMMENT '错误信息',
    exec_time DATETIME COMMENT '执行时间',
    FOREIGN KEY (plugin_id) REFERENCES plugin_config(id) ON DELETE SET NULL
) COMMENT '插件执行日志表';

-- 为gongdan表添加外部系统字段
ALTER TABLE gongdan ADD COLUMN external_id VARCHAR(100) COMMENT '外部系统工单ID';
ALTER TABLE gongdan ADD COLUMN external_status VARCHAR(50) COMMENT '外部工单状态';
ALTER TABLE gongdan ADD COLUMN synced TINYINT DEFAULT 0 COMMENT '是否已同步';
ALTER TABLE gongdan ADD COLUMN synced_time DATETIME COMMENT '同步时间';

-- 创建索引
CREATE INDEX idx_plugin_config_enabled ON plugin_config(enabled);
CREATE INDEX idx_plugin_config_trigger_mode ON plugin_config(trigger_mode);
CREATE INDEX idx_plugin_execution_log_plugin_id ON plugin_execution_log(plugin_id);
CREATE INDEX idx_plugin_execution_log_business ON plugin_execution_log(business_type, business_id);
CREATE INDEX idx_gongdan_external_id ON gongdan(external_id);
CREATE INDEX idx_gongdan_synced ON gongdan(synced);
