package com.metric.observe.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 健康度快照表基类
 *
 * 5 张 health_snapshot_* 表（1h/6h/1d/7d/30d）共享相同字段，
 * 通过 {@link MappedSuperclass} 让子类只声明 @Entity + @Table 即可，
 * 字段定义统一在此维护。
 *
 * 层级区分：
 * - metric 层：metricId 有值，categoryName/subcategoryName 也填
 * - subcategory 层：categoryName + subcategoryName 有值
 * - category 层：categoryName 有值
 * - system 层：systemId 有值
 */
@Data
@MappedSuperclass
public abstract class HealthSnapshotBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 层级: metric / subcategory / category / system */
    @Column(name = "level_type", nullable = false, length = 20)
    private String levelType;

    /** 指标ID（level_type=metric 时使用） */
    @Column(name = "metric_id")
    private Long metricId;

    /** 指标小类名（level_type=subcategory 时使用） */
    @Column(name = "subcategory_name", length = 100)
    private String subcategoryName;

    /** 指标大类名（level_type=category/subcategory 时使用） */
    @Column(name = "category_name", length = 50)
    private String categoryName;

    /** 系统ID（level_type=system 时使用） */
    @Column(name = "system_id")
    private Long systemId;

    /** 快照代表的时间点 */
    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    /** 健康度分数（0-100） */
    @Column(name = "health_score", nullable = false)
    private Integer healthScore;

    /** 状态: normal / warning / alarm */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 正常指标数 */
    @Column(name = "normal_count")
    private Integer normalCount;

    /** 预警指标数 */
    @Column(name = "warning_count")
    private Integer warningCount;

    /** 告警指标数 */
    @Column(name = "alarm_count")
    private Integer alarmCount;

    /** 由多少条明细聚合而成 */
    @Column(name = "aggregate_count")
    private Integer aggregateCount;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
