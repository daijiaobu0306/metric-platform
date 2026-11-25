package com.metric.metric.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 指标大类健康度历史表
 * 存储每个指标大类的健康度分数时间序列
 */
@Entity
@Table(name = "category_health_history",
       uniqueConstraints = @UniqueConstraint(columnNames = {"category_name", "record_time"}),
       indexes = {
           @Index(name = "idx_category_health_record_time", columnList = "record_time")
       })
public class CategoryHealthHistory {

    @Column(name = "weight_trace", columnDefinition = "json")
    private String weightTrace;

    public String getWeightTrace() {
        return weightTrace;
    }

    public void setWeightTrace(String weightTrace) {
        this.weightTrace = weightTrace;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_name", length = 50, nullable = false)
    private String categoryName;

    /**
     * 健康度分数（0-100）
     */
    @Column(name = "health_score")
    private Integer healthScore;

    /**
     * 状态：normal/warning/alarm
     */
    @Column(name = "status", length = 20)
    private String status;

    /**
     * 正常指标数
     */
    @Column(name = "normal_count")
    private Integer normalCount;

    /**
     * 预警指标数
     */
    @Column(name = "warning_count")
    private Integer warningCount;

    /**
     * 告警指标数
     */
    @Column(name = "alarm_count")
    private Integer alarmCount;

    /**
     * 记录时间
     */
    @Column(name = "record_time")
    private LocalDateTime recordTime;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public Integer getHealthScore() { return healthScore; }
    public void setHealthScore(Integer healthScore) { this.healthScore = healthScore; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getNormalCount() { return normalCount; }
    public void setNormalCount(Integer normalCount) { this.normalCount = normalCount; }

    public Integer getWarningCount() { return warningCount; }
    public void setWarningCount(Integer warningCount) { this.warningCount = warningCount; }

    public Integer getAlarmCount() { return alarmCount; }
    public void setAlarmCount(Integer alarmCount) { this.alarmCount = alarmCount; }

    public LocalDateTime getRecordTime() { return recordTime; }
    public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }
}