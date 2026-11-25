package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 健康度规则变更审计。
 *
 * <p>白名单勾选、基线修改、档位调整都要留痕：旧值、新值、操作人、原因、时间。
 * 批量白名单变更记一行（before/after 存指标 ID 列表），单指标规则变更记一行。
 */
@Data
@Entity
@Table(name = "metric_health_rule_version", indexes = {
    @Index(name = "idx_health_rule_version_metric", columnList = "metric_id,changed_at")
})
public class MetricHealthRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** rule=单指标规则 / batch=批量白名单 */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "metric_id")
    private Long metricId;

    /** manual / migration */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 50)
    private String operatorName;

    @Column(name = "before_json", columnDefinition = "json")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "json")
    private String afterJson;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    @PrePersist
    void onCreate() {
        if (changedAt == null) changedAt = LocalDateTime.now();
        if (version == null) version = 1;
        if (source == null) source = "manual";
    }
}
