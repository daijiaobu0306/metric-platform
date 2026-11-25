package com.metric.alarm.service;

import com.metric.alarm.entity.AlarmAuditLog;
import com.metric.alarm.repository.AlarmAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlarmAuditService {

    private static final Logger log = LoggerFactory.getLogger(AlarmAuditService.class);

    @Autowired
    private AlarmAuditLogRepository alarmAuditLogRepository;

    // 实体类型常量
    public static final String ENTITY_TYPE_ALARM = "alarm";
    public static final String ENTITY_TYPE_ALARM_STRATEGY = "alarm_strategy";
    public static final String ENTITY_TYPE_ALARM_CHANNEL = "alarm_channel";
    public static final String ENTITY_TYPE_SHIELD_RULE = "shield_rule";
    public static final String ENTITY_TYPE_RAW_DATA_ALARM = "raw_data_alarm";

    // 操作类型常量 - 告警操作
    public static final String OP_SHIELD = "SHIELD";
    public static final String OP_UNSHIELD = "UNSHIELD";
    public static final String OP_PROCESS = "PROCESS";
    public static final String OP_CANCEL_PROCESS = "CANCEL_PROCESS";
    public static final String OP_TAG_TO_ISSUE = "TAG_TO_ISSUE";
    public static final String OP_TAG_TO_GONGDAN = "TAG_TO_GONGDAN";
    public static final String OP_BATCH_PROCESS = "BATCH_PROCESS";
    public static final String OP_BATCH_DELETE = "BATCH_DELETE";
    public static final String OP_THRESHOLD_UPDATE = "THRESHOLD_UPDATE";

    // 操作类型常量 - 策略操作
    public static final String OP_STRATEGY_CREATE = "STRATEGY_CREATE";
    public static final String OP_STRATEGY_UPDATE = "STRATEGY_UPDATE";
    public static final String OP_STRATEGY_DELETE = "STRATEGY_DELETE";
    public static final String OP_STRATEGY_ENABLE = "STRATEGY_ENABLE";
    public static final String OP_STRATEGY_DISABLE = "STRATEGY_DISABLE";

    // 操作类型常量 - 通道操作
    public static final String OP_CHANNEL_CREATE = "CHANNEL_CREATE";
    public static final String OP_CHANNEL_UPDATE = "CHANNEL_UPDATE";
    public static final String OP_CHANNEL_DELETE = "CHANNEL_DELETE";
    public static final String OP_CHANNEL_ENABLE = "CHANNEL_ENABLE";
    public static final String OP_CHANNEL_DISABLE = "CHANNEL_DISABLE";

    // 操作类型常量 - 屏蔽规则操作
    public static final String OP_SHIELD_RULE_CREATE = "SHIELD_RULE_CREATE";
    public static final String OP_SHIELD_RULE_UPDATE = "SHIELD_RULE_UPDATE";
    public static final String OP_SHIELD_RULE_DELETE = "SHIELD_RULE_DELETE";

    // 操作类型常量 - 原始数据告警操作
    public static final String OP_RAW_DATA_ALARM_CREATE = "RAW_DATA_ALARM_CREATE";
    public static final String OP_RAW_DATA_ALARM_UPDATE = "RAW_DATA_ALARM_UPDATE";
    public static final String OP_RAW_DATA_ALARM_DELETE = "RAW_DATA_ALARM_DELETE";
    public static final String OP_RAW_DATA_ALARM_TOGGLE = "RAW_DATA_ALARM_TOGGLE";

    /**
     * 异步记录操作日志
     */
    @Async
    public void logAsync(AlarmAuditLog auditLog) {
        try {
            alarmAuditLogRepository.save(auditLog);
            // 同时输出到后端日志
            log.info("[AlarmAudit] {} - {}:{} - {} - {}",
                    auditLog.getOperationType(),
                    auditLog.getEntityType(),
                    auditLog.getEntityId(),
                    auditLog.getOperator(),
                    auditLog.getChangeContent());
        } catch (Exception e) {
            log.error("记录告警操作日志失败", e);
        }
    }

    /**
     * 同步记录操作日志
     */
    public AlarmAuditLog log(AlarmAuditLog auditLog) {
        AlarmAuditLog saved = alarmAuditLogRepository.save(auditLog);
        log.info("[AlarmAudit] {} - {}:{} - {} - {}",
                auditLog.getOperationType(),
                auditLog.getEntityType(),
                auditLog.getEntityId(),
                auditLog.getOperator(),
                auditLog.getChangeContent());
        return saved;
    }

    /**
     * 记录告警操作
     */
    public void logAlarmOperation(String operationType, Long alarmId, String operator,
                                   String oldValue, String newValue, String changeContent, String extraInfo) {
        AlarmAuditLog auditLog = new AlarmAuditLog();
        auditLog.setEntityType(ENTITY_TYPE_ALARM);
        auditLog.setEntityId(alarmId);
        auditLog.setOperationType(operationType);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setChangeContent(changeContent);
        auditLog.setExtraInfo(extraInfo);
        logAsync(auditLog);
    }

    /**
     * 记录策略操作
     */
    public void logStrategyOperation(String operationType, Long strategyId, String operator,
                                      String oldValue, String newValue, String changeContent, String extraInfo) {
        AlarmAuditLog auditLog = new AlarmAuditLog();
        auditLog.setEntityType(ENTITY_TYPE_ALARM_STRATEGY);
        auditLog.setEntityId(strategyId);
        auditLog.setOperationType(operationType);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setChangeContent(changeContent);
        auditLog.setExtraInfo(extraInfo);
        logAsync(auditLog);
    }

    /**
     * 记录通道操作
     */
    public void logChannelOperation(String operationType, Long channelId, String operator,
                                     String oldValue, String newValue, String changeContent, String extraInfo) {
        AlarmAuditLog auditLog = new AlarmAuditLog();
        auditLog.setEntityType(ENTITY_TYPE_ALARM_CHANNEL);
        auditLog.setEntityId(channelId);
        auditLog.setOperationType(operationType);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setChangeContent(changeContent);
        auditLog.setExtraInfo(extraInfo);
        logAsync(auditLog);
    }

    /**
     * 记录屏蔽规则操作
     */
    public void logShieldRuleOperation(String operationType, Long ruleId, String operator,
                                        String oldValue, String newValue, String changeContent, String extraInfo) {
        AlarmAuditLog auditLog = new AlarmAuditLog();
        auditLog.setEntityType(ENTITY_TYPE_SHIELD_RULE);
        auditLog.setEntityId(ruleId);
        auditLog.setOperationType(operationType);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setChangeContent(changeContent);
        auditLog.setExtraInfo(extraInfo);
        logAsync(auditLog);
    }

    /**
     * 记录原始数据告警操作
     */
    public void logRawDataAlarmOperation(String operationType, Long configId, String operator,
                                          String oldValue, String newValue, String changeContent, String extraInfo) {
        AlarmAuditLog auditLog = new AlarmAuditLog();
        auditLog.setEntityType(ENTITY_TYPE_RAW_DATA_ALARM);
        auditLog.setEntityId(configId);
        auditLog.setOperationType(operationType);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setChangeContent(changeContent);
        auditLog.setExtraInfo(extraInfo);
        logAsync(auditLog);
    }

    /**
     * 分页查询日志
     */
    public Page<AlarmAuditLog> findLogs(String entityType, String operationType,
                                          LocalDateTime startTime, LocalDateTime endTime,
                                          int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return alarmAuditLogRepository.findByConditions(entityType, operationType, startTime, endTime, pageable);
    }

    /**
     * 根据实体类型查询日志
     */
    public Page<AlarmAuditLog> findByEntityType(String entityType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return alarmAuditLogRepository.findByEntityTypeOrderByCreateTimeDesc(entityType, pageable);
    }

    /**
     * 根据实体ID查询日志
     */
    public Page<AlarmAuditLog> findByEntityId(String entityType, Long entityId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return alarmAuditLogRepository.findByEntityTypeAndEntityIdOrderByCreateTimeDesc(entityType, entityId, pageable);
    }

    public void batchDelete(List<Long> ids) {
        alarmAuditLogRepository.deleteAllById(ids);
    }
}
