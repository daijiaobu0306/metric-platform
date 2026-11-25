-- =============================================
-- 脚本技能化 MVP - 脚本元信息表
-- =============================================
CREATE TABLE IF NOT EXISTS script_meta (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '脚本名称',
    description TEXT COMMENT '脚本描述/用途',
    author VARCHAR(50) COMMENT '作者',
    version VARCHAR(20) DEFAULT '1.0' COMMENT '版本号',
    language VARCHAR(20) DEFAULT 'python' COMMENT '语言',
    file_path VARCHAR(500) COMMENT '脚本文件路径（如 /data/scripts/1/script.py）',
    file_size BIGINT COMMENT '文件大小(字节)',
    trigger_type VARCHAR(20) DEFAULT 'TIMER' COMMENT '触发类型: TIMER',
    cron_expression VARCHAR(50) COMMENT 'Cron表达式，如 0 0 9 * * ?',
    next_run_time TIMESTAMP NULL COMMENT '下次执行时间',
    is_running CHAR(1) DEFAULT 'N' COMMENT '是否正在运行: Y/N',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED',
    last_execution_time TIMESTAMP NULL COMMENT '最后执行时间',
    last_execution_status VARCHAR(20) COMMENT '最后执行状态: SUCCESS/FAILED/RUNNING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_trigger_type_status (trigger_type, status),
    INDEX idx_next_run_time (next_run_time),
    INDEX idx_is_running (is_running)
) COMMENT '脚本元信息表';

-- =============================================
-- 脚本执行日志表
-- =============================================
CREATE TABLE IF NOT EXISTS script_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    script_id BIGINT NOT NULL COMMENT '关联脚本ID',
    start_time TIMESTAMP NOT NULL COMMENT '开始时间',
    end_time TIMESTAMP NULL COMMENT '结束时间',
    duration_ms BIGINT COMMENT '执行耗时(毫秒)',
    status VARCHAR(20) NOT NULL COMMENT '状态: RUNNING/SUCCESS/FAILED',
    output TEXT COMMENT '脚本输出日志',
    error_message TEXT COMMENT '错误信息',
    executor VARCHAR(50) DEFAULT 'SYSTEM' COMMENT '触发者: SYSTEM/USER/API',
    trigger_reason VARCHAR(200) COMMENT '触发原因描述',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (script_id) REFERENCES script_meta(id) ON DELETE CASCADE,
    INDEX idx_script_id (script_id),
    INDEX idx_status (status),
    INDEX idx_start_time (start_time)
) COMMENT '脚本执行日志表';
