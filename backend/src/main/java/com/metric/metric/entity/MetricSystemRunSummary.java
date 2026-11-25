package com.metric.metric.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "metric_system_run_summary")
public class MetricSystemRunSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insert_time", nullable = false)
    private LocalDateTime insertTime;

    @Column(name = "sub_category_code", length = 50, nullable = false)
    private String subCategoryCode;

    @Column(name = "metric_attr", length = 50)
    private String metricAttr;

    @Column(name = "metric_name", length = 100)
    private String metricName;

    @Column(name = "metric_value", precision = 18, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "object_name", length = 200)
    private String objectName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getInsertTime() {
        return insertTime;
    }

    public void setInsertTime(LocalDateTime insertTime) {
        this.insertTime = insertTime;
    }

    public String getSubCategoryCode() {
        return subCategoryCode;
    }

    public void setSubCategoryCode(String subCategoryCode) {
        this.subCategoryCode = subCategoryCode;
    }

    public String getMetricAttr() {
        return metricAttr;
    }

    public void setMetricAttr(String metricAttr) {
        this.metricAttr = metricAttr;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public BigDecimal getMetricValue() {
        return metricValue;
    }

    public void setMetricValue(BigDecimal metricValue) {
        this.metricValue = metricValue;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }
}