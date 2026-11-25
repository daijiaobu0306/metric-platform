package com.metric.collect.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inner_query", indexes = {
    @Index(name = "idx_data_date", columnList = "data_date"),
    @Index(name = "idx_business_name", columnList = "business_name")
})
public class InnerQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate; // 数据批次日期

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName; // 业务名称

    @Column(name = "step_name", length = 200)
    private String stepName; // 步骤名称

    @Column(name = "step_type", length = 50)
    private String stepType; // 步骤类型: 页面加载/查询时长

    @Column(name = "access_count")
    private Long accessCount; // 访问量

    @Column(name = "avg_duration_sec", precision = 10, scale = 4)
    private BigDecimal avgDurationSec; // 平均时长(秒)

    @Column(name = "insert_time")
    private LocalDateTime insertTime = LocalDateTime.now(); // 入库时间
}
