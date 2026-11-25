package com.metric.collect.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inner_button_count", indexes = {
    @Index(name = "idx_sub_category", columnList = "sub_category"),
    @Index(name = "idx_business_name", columnList = "business_name")
})
public class InnerButtonCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate; // 数据批次日期

    @Column(name = "sub_category", nullable = false, length = 20)
    private String subCategory; // crm=单业务, chbn=融合业务

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName;

    @Column(name = "button_count")
    private Integer buttonCount; // 办理按钮总数(步骤数)

    @Column(name = "insert_time")
    private LocalDateTime insertTime = LocalDateTime.now();
}
