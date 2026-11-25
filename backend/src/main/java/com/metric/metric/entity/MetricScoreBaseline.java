package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标评分基准。
 *
 * <p>与告警彻底解耦：本表只服务于健康度算分，不参与任何告警判定。
 * 告警阈值唯一来源是 {@code alarm_strategy}。
 *
 * <p>{@code scoreMode} 预留两种算分方式：
 * <ul>
 *   <li>{@code threshold} —— 按基准值扣分（当前算法：100 −(值−基准)×系数），由
 *       {@code metric_item.alarm_threshold} 迁移而来</li>
 *   <li>{@code trend} —— 按历史偏离度算分（待算法专题实现，届时本表可整体替换）</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "metric_score_baseline")
public class MetricScoreBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 指标ID，一指标一行 */
    @Column(name = "metric_id", nullable = false, unique = true)
    private Long metricId;

    /** threshold = 按基准值扣分；trend = 按历史偏离（待实现） */
    @Column(name = "score_mode", nullable = false, length = 20)
    private String scoreMode = "threshold";

    /** 评分基准值 */
    @Column(name = "baseline_value", precision = 15, scale = 4)
    private BigDecimal baselineValue;

    /** 基准比较方向：&gt; / &lt; / &gt;= / &lt;= / = */
    @Column(name = "baseline_condition", length = 20)
    private String baselineCondition;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
