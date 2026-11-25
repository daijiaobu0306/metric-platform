package com.metric.collect.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inner_business_duration", indexes = {
    @Index(name = "idx_sub_category", columnList = "sub_category"),
    @Index(name = "idx_business_name", columnList = "business_name")
})
public class InnerBusinessDuration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate; // 数据批次日期

    @Column(name = "sub_category", length = 20)
    private String subCategory; // query=查询类, crm=单业务, chbn=融合业务

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName;

    @Column(name = "access_count")
    private Long accessCount;

    @Column(name = "full_duration_sec")
    private Integer fullDurationSec; // 全程办理时长(秒)

    @Column(name = "full_interval_sec")
    private Integer fullIntervalSec; // 全程操作间隔时长(秒)

    @Column(name = "full_service_sec")
    private Integer fullServiceSec; // 全程服务耗时(秒)

    @Column(name = "failure_count")
    private Integer failureCount;

    @Column(name = "insert_time")
    private LocalDateTime insertTime = LocalDateTime.now();
}
