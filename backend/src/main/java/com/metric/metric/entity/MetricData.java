package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "metric_data", indexes = {
    @Index(name = "idx_metric_id", columnList = "metric_id"),
    @Index(name = "idx_collect_time", columnList = "collect_time")
})
public class MetricData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_id", nullable = false)
    private Long metricId;

    @Column(name = "metric_value", nullable = false, precision = 10, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "collect_time", nullable = false)
    private LocalDateTime collectTime;

    @Column(name = "health_score")
    private Integer healthScore;

    @Column(name = "is_anomaly")
    private Integer isAnomaly;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        if (isAnomaly == null) {
            isAnomaly = 0;
        }
    }
}
