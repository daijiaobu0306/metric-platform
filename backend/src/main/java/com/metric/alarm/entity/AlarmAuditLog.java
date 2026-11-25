package com.metric.alarm.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alarm_audit_log", indexes = {
    @Index(name = "idx_alarm_audit_entity", columnList = "entity_type, entity_id"),
    @Index(name = "idx_alarm_audit_operation", columnList = "operation_type"),
    @Index(name = "idx_alarm_audit_create_time", columnList = "create_time")
})
public class AlarmAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType; // alarm, alarm_strategy, alarm_channel, shield_rule

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType; // SHIELD, UNSHIELD, PROCESS, CANCEL_PROCESS, TAG_TO_ISSUE, CREATE, UPDATE, DELETE, ENABLE, DISABLE

    @Column(name = "operator", length = 50)
    private String operator = "system";

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "change_content", length = 500)
    private String changeContent;

    @Column(name = "extra_info", columnDefinition = "TEXT")
    private String extraInfo; // 额外信息，如告警内容、策略名称等

    @Column(name = "create_time")
    private LocalDateTime createTime = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }

    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }

    public String getChangeContent() { return changeContent; }
    public void setChangeContent(String changeContent) { this.changeContent = changeContent; }

    public String getExtraInfo() { return extraInfo; }
    public void setExtraInfo(String extraInfo) { this.extraInfo = extraInfo; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}