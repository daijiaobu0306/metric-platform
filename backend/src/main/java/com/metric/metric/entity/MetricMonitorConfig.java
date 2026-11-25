package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "metric_monitor_config")
public class MetricMonitorConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_id", nullable = false, unique = true)
    private Long metricId;

    @Column(name = "alarm_window_minutes")
    private Integer alarmWindowMinutes = 5;

    @Column(name = "alarm_threshold", precision = 15, scale = 4)
    private BigDecimal alarmThreshold;

    @Column(name = "alarm_condition", length = 20)
    private String alarmCondition = ">";

    @Column(name = "alarm_status")
    private Integer alarmStatus = 1;

    @Column(name = "gongdan_window_minutes")
    private Integer gongdanWindowMinutes = 30;

    @Column(name = "gongdan_threshold", precision = 15, scale = 4)
    private BigDecimal gongdanThreshold;

    @Column(name = "gongdan_condition", length = 20)
    private String gongdanCondition = ">";

    @Column(name = "consecutive_count")
    private Integer consecutiveCount = 3;

    @Column(name = "gongdan_status")
    private Integer gongdanStatus = 1;

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
