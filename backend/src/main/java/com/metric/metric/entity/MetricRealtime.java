package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "metric_realtime")
public class MetricRealtime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_id", nullable = false, unique = true)
    private Long metricId;

    @Column(name = "metric_value", precision = 15, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "data_time")
    private LocalDateTime dataTime;

    @Column(name = "is_exceed")
    private Integer isExceed = 0;

    @Column(name = "exceed_count")
    private Integer exceedCount = 0;

    @Column(name = "last_exceed_time")
    private LocalDateTime lastExceedTime;

    @Column(name = "status", length = 20)
    private String status = "normal";

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
