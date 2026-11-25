package com.metric.audit.service;

import com.metric.audit.entity.EntityAuditLog;
import com.metric.audit.repository.EntityAuditLogRepository;
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
public class EntityAuditService {

    private static final Logger log = LoggerFactory.getLogger(EntityAuditService.class);

    @Autowired
    private EntityAuditLogRepository entityAuditLogRepository;

    // 实体类型常量
    public static final String ENTITY_GONGDAN = "GONGDAN";
    public static final String ENTITY_ISSUE = "ISSUE";

    // 操作类型常量
    public static final String OP_CREATE = "CREATE";
    public static final String OP_UPDATE = "UPDATE";
    public static final String OP_DELETE = "DELETE";
    public static final String OP_CLOSE = "CLOSE";
    public static final String OP_RESOLVE_STATUS_CHANGE = "RESOLVE_STATUS_CHANGE";

    @Async
    public void logAsync(EntityAuditLog auditLog) {
        try {
            entityAuditLogRepository.save(auditLog);
            log.info("[EntityAudit] {} - {}:{} - {} - {}",
                    auditLog.getOperationType(),
                    auditLog.getEntityType(),
                    auditLog.getEntityId(),
                    auditLog.getOperator(),
                    auditLog.getChangeContent());
        } catch (Exception e) {
            log.error("记录实体操作日志失败", e);
        }
    }

    public EntityAuditLog log(EntityAuditLog auditLog) {
        EntityAuditLog saved = entityAuditLogRepository.save(auditLog);
        log.info("[EntityAudit] {} - {}:{} - {} - {}",
                auditLog.getOperationType(),
                auditLog.getEntityType(),
                auditLog.getEntityId(),
                auditLog.getOperator(),
                auditLog.getChangeContent());
        return saved;
    }

    public void logGongdanOperation(String operationType, Long gongdanId, String operator,
                                     String oldValue, String newValue, String changeContent) {
        EntityAuditLog auditLog = new EntityAuditLog();
        auditLog.setEntityType(ENTITY_GONGDAN);
        auditLog.setEntityId(gongdanId);
        auditLog.setOperationType(operationType);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setChangeContent(changeContent);
        logAsync(auditLog);
    }

    public void logIssueOperation(String operationType, Long issueId, String operator,
                                   String oldValue, String newValue, String changeContent) {
        EntityAuditLog auditLog = new EntityAuditLog();
        auditLog.setEntityType(ENTITY_ISSUE);
        auditLog.setEntityId(issueId);
        auditLog.setOperationType(operationType);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setChangeContent(changeContent);
        logAsync(auditLog);
    }

    public Page<EntityAuditLog> findLogs(String entityType, Long entityId, String operationType,
                                         LocalDateTime startTime, LocalDateTime endTime,
                                         int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return entityAuditLogRepository.findByConditions(entityType, entityId, operationType, startTime, endTime, pageable);
    }

    public Page<EntityAuditLog> findByEntityType(String entityType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return entityAuditLogRepository.findByEntityTypeOrderByCreateTimeDesc(entityType, pageable);
    }

    public Page<EntityAuditLog> findByEntityId(String entityType, Long entityId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return entityAuditLogRepository.findByEntityTypeAndEntityIdOrderByCreateTimeDesc(entityType, entityId, pageable);
    }

    public void batchDelete(List<Long> ids) {
        entityAuditLogRepository.deleteAllById(ids);
    }
}