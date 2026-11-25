-- 告警操作审计日志表
CREATE TABLE alarm_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL COMMENT '实体类型：alarm, alarm_strategy, alarm_channel, shield_rule, raw_data_alarm',
    entity_id BIGINT COMMENT '实体ID',
    operation_type VARCHAR(50) NOT NULL COMMENT '操作类型：SHIELD, UNSHIELD, PROCESS, CANCEL_PROCESS, TAG_TO_ISSUE, CREATE, UPDATE, DELETE, ENABLE, DISABLE',
    operator VARCHAR(50) COMMENT '操作人',
    old_value TEXT COMMENT '旧值',
    new_value TEXT COMMENT '新值',
    change_content VARCHAR(500) COMMENT '变更内容摘要',
    extra_info TEXT COMMENT '额外信息',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_entity (entity_type, entity_id),
    INDEX idx_operation (operation_type),
    INDEX idx_create_time (create_time)
) COMMENT '告警操作审计日志';