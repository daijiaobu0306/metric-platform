package com.metric.alarm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alarm_analysis_config")
public class AlarmAnalysisConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cron_expression", length = 50)
    private String cronExpression = "0 0 2 * * ?";

    @Column(name = "analysis_enabled")
    private Integer analysisEnabled = 1;

    @Column(name = "lookback_days")
    private Integer lookbackDays = 7;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
