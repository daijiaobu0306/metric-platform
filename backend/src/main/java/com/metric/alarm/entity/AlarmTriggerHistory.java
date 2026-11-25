package com.metric.alarm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alarm_trigger_history")
public class AlarmTriggerHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(name = "metric_id")
    private Long metricId;

    @Column(name = "source_table", length = 50)
    private String sourceTable;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "trigger_value", precision = 15, scale = 4, nullable = false)
    private BigDecimal triggerValue;

    @Column(name = "threshold_value", precision = 15, scale = 4, nullable = false)
    private BigDecimal thresholdValue;

    @Column(name = "trigger_time", nullable = false)
    private LocalDateTime triggerTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }
}
