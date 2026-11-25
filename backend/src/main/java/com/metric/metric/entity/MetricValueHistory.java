package com.metric.metric.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标值历史表
 * 存储每个指标小类的指标值时间序列
 */
@Entity
@Table(name = "metric_value_history",
       uniqueConstraints = @UniqueConstraint(columnNames = {"metric_id", "data_time"}),
       indexes = {
           @Index(name = "idx_metric_value_record_time", columnList = "record_time"),
           @Index(name = "idx_metric_value_data_time", columnList = "data_time")
       })
public class MetricValueHistory {

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
     * 指标值：超限业务数量
     */
    @Column(name = "metric_value")
    private Integer metricValue;

    @Column(name = "threshold", precision = 10, scale = 4)
    private BigDecimal threshold;

    @Column(name = "unit", length = 20)
    private String unit;

    /**
     * 记录时间：数据入库时间
     */
    @Column(name = "record_time")
    private LocalDateTime recordTime;

    /**
     * 数据时间：数据批次对应的时间
     */
    @Column(name = "data_time")
    private LocalDateTime dataTime;

    /**
     * 本批次参与计算的业务总数
     */
    @Column(name = "business_count")
    private Integer businessCount;

    /**
     * 本批次超限业务数量
     */
    @Column(name = "over_limit_count")
    private Integer overLimitCount;

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

    /** 数据来源：collected=真实采集 / mock=样例数据 / import=导入 / unknown=无法判定。动态基线只用 collected（不足时可按配置兜底样例数据） */
    @Column(name = "data_origin", length = 20)
    private String dataOrigin;

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

    public Integer getMetricValue() { return metricValue; }
    public void setMetricValue(Integer metricValue) { this.metricValue = metricValue; }

    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public LocalDateTime getRecordTime() { return recordTime; }
    public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }

    public LocalDateTime getDataTime() { return dataTime; }
    public void setDataTime(LocalDateTime dataTime) { this.dataTime = dataTime; }

    public Integer getBusinessCount() { return businessCount; }
    public void setBusinessCount(Integer businessCount) { this.businessCount = businessCount; }

    public Integer getOverLimitCount() { return overLimitCount; }
    public void setOverLimitCount(Integer overLimitCount) { this.overLimitCount = overLimitCount; }

    public String getFirstClassify() { return firstClassify; }
    public void setFirstClassify(String firstClassify) { this.firstClassify = firstClassify; }

    public String getSecondClassify() { return secondClassify; }
    public void setSecondClassify(String secondClassify) { this.secondClassify = secondClassify; }

    public String getDataOrigin() { return dataOrigin; }
    public void setDataOrigin(String dataOrigin) { this.dataOrigin = dataOrigin; }

    /**
     * 写入前兜底数据来源。
     *
     * <p>2026-11-17 P2：集成 mock 模块已下线，正常运行只会写入真实采集值；
     * 列是 NOT NULL（P0 迁移加了默认值 'collected'），但 JPA 显式插入 NULL 会绕过默认值，
     * 所以这里统一兜底成 collected，导入类场景由调用方显式覆盖成 import。
     */
    @PrePersist
    void defaultDataOrigin() {
        if (dataOrigin == null || dataOrigin.isBlank()) dataOrigin = "collected";
    }
}