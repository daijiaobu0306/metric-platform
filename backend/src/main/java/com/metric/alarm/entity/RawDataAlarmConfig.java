package com.metric.alarm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "raw_data_alarm_config")
public class RawDataAlarmConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_name", length = 50, nullable = false)
    private String tableName;

    @Column(name = "metric_field", length = 50, nullable = false)
    private String metricField;

    @Column(name = "filter_field", length = 50)
    private String filterField;

    @Column(name = "filter_value", length = 100)
    private String filterValue;

    @Column(name = "source_id_field", length = 50)
    private String sourceIdField;

    @Column(name = "agg_method", length = 20)
    private String aggMethod = "NONE";  // NONE/AVG/SUM/COUNT/MAX/MIN

    // ==================== 新字段（统一告警服务使用）====================
    @Column(name = "global_threshold", precision = 15, scale = 4)
    private BigDecimal globalThreshold;

    @Column(name = "global_condition", length = 10)
    private String globalCondition = ">";

    @Column(name = "dimension_thresholds", columnDefinition = "JSON")
    private String dimensionThresholds;  // JSON: [{"filterValue":"xxx","threshold":123,"condition":">"}]

    @Column(name = "object_thresholds", columnDefinition = "JSON")
    private String objectThresholds;  // JSON: [{"sourceId":123,"threshold":456,"condition":">"}]

    // ==================== 旧字段（向后兼容，废弃中）====================
    @Column(name = "alarm_threshold", precision = 15, scale = 4)
    private BigDecimal alarmThreshold;  // @Deprecated, use globalThreshold instead

    @Column(name = "threshold_multiplier", precision = 5, scale = 2)
    private BigDecimal thresholdMultiplier;  // @Deprecated

    @Column(name = "time_window_minutes")
    private Integer timeWindowMinutes = 60;  // @Deprecated

    // ==================== 通用字段 ====================
    @Column(name = "alarm_status")
    private Integer alarmStatus = 1;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    // API只返回strategyId；关联实体仅供内部扫描，避免序列化Hibernate代理导致列表500。
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_id", insertable = false, updatable = false)
    private AlarmStrategy strategy;

    // ==================== 向后兼容的 getter 方法 ====================

    /**
     * @Deprecated use getGlobalThreshold() instead
     */
    public BigDecimal getThresholdMultiplier() {
        return this.thresholdMultiplier;
    }

    /**
     * @Deprecated use getGlobalThreshold() instead
     */
    public void setThresholdMultiplier(BigDecimal thresholdMultiplier) {
        this.thresholdMultiplier = thresholdMultiplier;
    }

    /**
     * @Deprecated use getAggMethod() instead
     */
    public String getCalcMethod() {
        return this.aggMethod;
    }

    /**
     * @Deprecated use setAggMethod() instead
     */
    public void setCalcMethod(String calcMethod) {
        this.aggMethod = calcMethod;
    }

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
