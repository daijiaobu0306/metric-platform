package com.metric.alarm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "shield_rule")
public class ShieldRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shield_type", nullable = false, length = 20)
    private String shieldType;  // SYSTEM, CATEGORY, METRIC

    @Column(name = "target_id", nullable = false)
    private Long targetId;  // systemId or categoryId or metricId

    @Column(name = "target_name", length = 200)
    private String targetName;  // 目标名称（冗余存储便于显示）

    @Column(name = "shield_end_time")
    private LocalDateTime shieldEndTime;  // null表示永久屏蔽

    @Column(name = "shield_time", nullable = false)
    private LocalDateTime shieldTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        shieldTime = LocalDateTime.now();
    }
}
