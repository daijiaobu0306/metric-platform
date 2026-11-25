package com.metric.metric.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 健康度历史表
 * 存储每个指标的健康度分数时间序列
 */
@Entity
@Table(name = "metric_health_history",
       uniqueConstraints = @UniqueConstraint(columnNames = {"metric_id", "record_time"}),
       indexes = {
           @Index(name = "idx_health_record_time", columnList = "record_time")
       })
public class MetricHealthHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_id", nullable = false)
    private Long metricId;

    @Column(name = "metric_code", length = 50)
    private String metricCode;

    @Column(name = "metric_name", length = 100)
    private String metricName;

    @Column(name = "category_name", length = 50)
    private String categoryName;

    @Column(name = "sub_category_name", length = 100)
    private String subCategoryName;

    /**
     * 健康度分数（0-100）
     */
    @Column(name = "health_score")
    private Integer healthScore;

    /**
     * 状态：normal/warning/alarm
     */
    @Column(name = "status", length = 20)
    private String status;

    /**
     * 基线值：历史N天均值
     */
    @Column(name = "baseline", precision = 10, scale = 4)
    private BigDecimal baseline;

    /**
     * 正常范围上限
     */
    @Column(name = "upper_bound", precision = 10, scale = 4)
    private BigDecimal upperBound;

    /**
     * 正常范围下限
     */
    @Column(name = "lower_bound", precision = 10, scale = 4)
    private BigDecimal lowerBound;

    /**
     * 当前指标值（用于参考）
     */
    @Column(name = "current_value")
    private Integer currentValue;

    /**
     * 阈值（用于下钻查询）
     */
    @Column(name = "threshold", precision = 10, scale = 2)
    private BigDecimal threshold;

    /**
     * 算分模式：manual=人工基线 / dynamic=动态基线 / exempt=不计分。
     * 旧算法写入的行为 NULL，页面据此做新旧对比（双跑）。
     */
    @Column(name = "mode", length = 20)
    private String mode;

    /** 算分时使用的规则版本；规则调整会在趋势图上形成断点 */
    @Column(name = "rule_version")
    private Integer ruleVersion;

    /** 本次实际使用的基线（人工基线，或动态基线取到的中位数） */
    @Column(name = "baseline_used", precision = 15, scale = 4)
    private BigDecimal baselineUsed;

    /** 偏离度：人工基线=相对偏离比例；动态基线=稳健 z 值 */
    @Column(name = "deviation", precision = 15, scale = 4)
    private BigDecimal deviation;

    /** 动态基线样本数 */
    @Column(name = "sample_count")
    private Integer sampleCount;

    /** 算分过程明细 JSON（输入值、方向、基线、扣分、结论），解释弹窗直接读取 */
    @Column(name = "score_trace", columnDefinition = "json")
    private String scoreTrace;

    /** 规则引擎版本：rule-v1=新算法；legacy=改造前旧算法 */
    @Column(name = "algorithm_version", length = 20)
    private String algorithmVersion;

    /** 数据来源：collected=真实采集 / mock=样例数据 / import=导入 / unknown=无法判定 */
    @Column(name = "data_origin", length = 20)
    private String dataOrigin;

    /**
     * 原始数据时间（用于下钻查询过滤最新批次）
     */
    @Column(name = "data_time")
    private LocalDateTime dataTime;

    /**
     * 记录时间
     */
    @Column(name = "record_time")
    private LocalDateTime recordTime;

    /**
     * 一级分类（系统稽核用）
     */
    @Column(name = "first_classify", length = 100)
    private String firstClassify;

    /**
     * 二级分类（系统稽核用）
     */
    @Column(name = "second_classify", length = 100)
    private String secondClassify;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMetricId() { return metricId; }
    public void setMetricId(Long metricId) { this.metricId = metricId; }

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getSubCategoryName() { return subCategoryName; }
    public void setSubCategoryName(String subCategoryName) { this.subCategoryName = subCategoryName; }

    public Integer getHealthScore() { return healthScore; }
    public void setHealthScore(Integer healthScore) { this.healthScore = healthScore; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getBaseline() { return baseline; }
    public void setBaseline(BigDecimal baseline) { this.baseline = baseline; }

    public BigDecimal getUpperBound() { return upperBound; }
    public void setUpperBound(BigDecimal upperBound) { this.upperBound = upperBound; }

    public BigDecimal getLowerBound() { return lowerBound; }
    public void setLowerBound(BigDecimal lowerBound) { this.lowerBound = lowerBound; }

    public Integer getCurrentValue() { return currentValue; }
    public void setCurrentValue(Integer currentValue) { this.currentValue = currentValue; }

    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public BigDecimal getBaselineUsed() { return baselineUsed; }
    public void setBaselineUsed(BigDecimal baselineUsed) { this.baselineUsed = baselineUsed; }

    public BigDecimal getDeviation() { return deviation; }
    public void setDeviation(BigDecimal deviation) { this.deviation = deviation; }

    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }

    public String getScoreTrace() { return scoreTrace; }
    public void setScoreTrace(String scoreTrace) { this.scoreTrace = scoreTrace; }

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String algorithmVersion) { this.algorithmVersion = algorithmVersion; }

    public String getDataOrigin() { return dataOrigin; }
    public void setDataOrigin(String dataOrigin) { this.dataOrigin = dataOrigin; }

    /** 同 metric_value_history：data_origin 是 NOT NULL，JPA 插入前兜底成 collected */
    @jakarta.persistence.PrePersist
    void defaultDataOrigin() {
        if (dataOrigin == null || dataOrigin.isBlank()) dataOrigin = "collected";
    }

    public LocalDateTime getDataTime() { return dataTime; }
    public void setDataTime(LocalDateTime dataTime) { this.dataTime = dataTime; }

    public LocalDateTime getRecordTime() { return recordTime; }
    public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }

    public String getFirstClassify() { return firstClassify; }
    public void setFirstClassify(String firstClassify) { this.firstClassify = firstClassify; }

    public String getSecondClassify() { return secondClassify; }
    public void setSecondClassify(String secondClassify) { this.secondClassify = secondClassify; }
}