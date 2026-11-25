package com.metric.alarm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alarm", indexes = {
    @Index(name = "idx_alarm_time", columnList = "alarm_time"),
    @Index(name = "idx_process_status", columnList = "process_status"),
    @Index(name = "idx_system_id", columnList = "system_id")
})
public class Alarm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "system_id")
    private Long systemId;

    @Column(name = "system_name", length = 100)
    private String systemName;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "metric_id")
    private Long metricId;

    @Column(name = "alarm_level")
    private Integer alarmLevel;

    @Column(name = "alarm_type", length = 50)
    private String alarmType;

    @Column(name = "metric_value", precision = 15, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "threshold_value", precision = 15, scale = 4)
    private BigDecimal thresholdValue;

    @Column(name = "health_score")
    private Integer healthScore;

    @Column(name = "alarm_content", columnDefinition = "TEXT")
    private String alarmContent;

    @Column(name = "alarm_time")
    private LocalDateTime alarmTime;

    @Column(name = "process_status")
    private Integer processStatus;

    @Column(name = "process_time")
    private LocalDateTime processTime;

    @Column(name = "process_user", length = 50)
    private String processUser;

    @Column(name = "shield_status")
    private Integer shieldStatus;

    @Column(name = "shield_end_time")
    private LocalDateTime shieldEndTime;

    @Column(name = "shield_time")
    private LocalDateTime shieldTime;

    @Column(name = "shield_unshield_time")
    private LocalDateTime shieldUnshieldTime;

    @Column(name = "shield_unshield_type", length = 20)
    private String shieldUnshieldType;

    @Column(name = "tag_status")
    private Integer tagStatus;

    @Column(name = "tag_time")
    private LocalDateTime tagTime;

    @Column(name = "related_issue_id")
    private Long relatedIssueId;

    @Column(name = "related_gongdan_id")
    private Long relatedGongdanId;

    @Column(name = "responsible_person", length = 50)
    private String responsiblePerson;

    // ========== 关联原始数据表的字段 ==========
    // 原始数据表名（如 inner_query, inner_step_response, inner_button_count, inner_business_duration）
    @Column(name = "source_table", length = 50)
    private String sourceTable;

    // 原始数据记录ID
    @Column(name = "source_id")
    private Long sourceId;

    // 指标大类名称（如"对内部人员"）
    @Column(name = "category_name", length = 50)
    private String categoryName;

    // 指标小类名称（如"查询类"）
    @Column(name = "sub_category_name", length = 100)
    private String subCategoryName;

    // 指标名称
    @Column(name = "metric_name", length = 200)
    private String metricName;

    // 业务名称
    @Column(name = "business_name", length = 200)
    private String businessName;

    // 步骤名称
    @Column(name = "step_name", length = 200)
    private String stepName;

    // 访问量
    @Column(name = "access_count")
    private Long accessCount;

    // 指标单位
    @Column(name = "unit", length = 20)
    private String unit;

    // 指标定义
    @Column(name = "metric_definition", length = 500)
    private String metricDefinition;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (alarmTime == null) {
            alarmTime = LocalDateTime.now();
        }
        if (processStatus == null) {
            processStatus = 0;
        }
        if (shieldStatus == null) {
            shieldStatus = 0;
        }
        if (tagStatus == null) {
            tagStatus = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
