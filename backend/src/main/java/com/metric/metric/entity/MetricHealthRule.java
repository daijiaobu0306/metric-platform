package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标健康度规则（一指标一行）。
 *
 * <p>设计口径（2026-11-17 与业务确认，见 doc/健康度算法重构需求与设计.md）：
 * <ul>
 *   <li><b>三态互斥</b>：{@code exempt=1} → 不计分；{@code baseline_status='confirmed'}
 *       且有基线值 → 人工基线；其余 → 动态基线。不需要人再选“模式”。</li>
 *   <li><b>基线按最小指标设置</b>，小类不设基线（小类里次数/耗时/占比混杂）。</li>
 *   <li><b>建议值不参与算分</b>：{@code baseline_status='suggested'} 只用于页面提示，
 *       人工「采纳」后才变 confirmed，避免把未经确认的历史阈值当成正式业务规则。</li>
 *   <li><b>不写死档位</b>：零容忍/分段扣分的档位表存 {@code tiers} JSON，由页面配置。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "metric_health_rule", indexes = {
    @Index(name = "idx_health_rule_exempt", columnList = "exempt,status")
})
public class MetricHealthRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_id", nullable = false)
    private Long metricId;

    /** 1=白名单不计分，不参与任何层聚合 */
    @Column(name = "exempt", nullable = false)
    private Integer exempt;

    @Column(name = "exempt_reason", length = 200)
    private String exemptReason;

    /** lower_better / higher_better / range / zero_tolerance */
    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    /** auto=系统推导；manual=人工确认后锁定 */
    @Column(name = "direction_source", nullable = false, length = 20)
    private String directionSource;

    /** 人工基线；为空走动态基线 */
    @Column(name = "baseline_value", precision = 15, scale = 4)
    private BigDecimal baselineValue;

    /** 宽容区：此范围内不扣分 */
    @Column(name = "tolerance", nullable = false, precision = 15, scale = 4)
    private BigDecimal tolerance;

    /** 扣到 0 分对应的值（人工基线必填） */
    @Column(name = "penalty_value", precision = 15, scale = 4)
    private BigDecimal penaltyValue;

    @Column(name = "range_min", precision = 15, scale = 4)
    private BigDecimal rangeMin;

    @Column(name = "range_max", precision = 15, scale = 4)
    private BigDecimal rangeMax;

    /** 扣分档位表 JSON：[{"max":0,"score":100},...]，由人工配置 */
    @Column(name = "tiers", columnDefinition = "json")
    private String tiers;

    @Column(name = "window_days", nullable = false)
    private Integer windowDays;

    @Column(name = "min_samples", nullable = false)
    private Integer minSamples;

    /** none / workday_weekend / weekday */
    @Column(name = "seasonality", nullable = false, length = 20)
    private String seasonality;

    /** none=无建议 / suggested=建议值待确认（不参与算分） / confirmed=已确认 */
    @Column(name = "baseline_status", nullable = false, length = 20)
    private String baselineStatus;

    @Column(name = "status", nullable = false)
    private Integer status;

    /** 规则版本，每次变更 +1，趋势图按此打断点 */
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    /** 是否白名单 */
    public boolean isExempt() {
        return exempt != null && exempt == 1;
    }

    /** 人工基线是否已确认（建议值不算） */
    public boolean hasConfirmedBaseline() {
        return baselineValue != null && "confirmed".equals(baselineStatus);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
        if (exempt == null) exempt = 0;
        if (direction == null) direction = "lower_better";
        if (directionSource == null) directionSource = "auto";
        if (tolerance == null) tolerance = BigDecimal.ZERO;
        if (windowDays == null) windowDays = 90;
        if (minSamples == null) minSamples = 30;
        if (seasonality == null) seasonality = "none";
        if (baselineStatus == null) baselineStatus = "none";
        if (status == null) status = 1;
        if (version == null) version = 1;
    }

    @PreUpdate
    void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
