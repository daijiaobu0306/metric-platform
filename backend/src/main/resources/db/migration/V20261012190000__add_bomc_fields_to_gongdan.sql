-- BOMC 工单业务字段。历史 V1-V57 保持不变，使用时间版本迁移。
ALTER TABLE gongdan
    ADD COLUMN IF NOT EXISTS alarm_level INT COMMENT '告警级别',
    ADD COLUMN IF NOT EXISTS alarm_priority VARCHAR(20) COMMENT '优先级',
    ADD COLUMN IF NOT EXISTS alarm_impact VARCHAR(20) COMMENT '影响度',
    ADD COLUMN IF NOT EXISTS alarm_object VARCHAR(200) COMMENT '告警对象名称',
    ADD COLUMN IF NOT EXISTS alarm_first_time DATETIME COMMENT '告警第一次发生时间',
    ADD COLUMN IF NOT EXISTS alarm_last_time DATETIME COMMENT '告警最后一次发生时间',
    ADD COLUMN IF NOT EXISTS alarm_count INT COMMENT '告警次数',
    ADD COLUMN IF NOT EXISTS responsible_person VARCHAR(100) COMMENT '主要责任人',
    ADD COLUMN IF NOT EXISTS second_responsible VARCHAR(100) COMMENT '次要责任人',
    ADD COLUMN IF NOT EXISTS execute_user VARCHAR(50) COMMENT 'BOMC告警处理人ID',
    ADD COLUMN IF NOT EXISTS external_sync_msg VARCHAR(500) COMMENT 'BOMC同步结果消息',
    ADD COLUMN IF NOT EXISTS last_query_time DATETIME COMMENT '最近一次BOMC轨迹查询时间';
