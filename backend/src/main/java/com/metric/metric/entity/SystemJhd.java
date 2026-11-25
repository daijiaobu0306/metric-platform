package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "system_jhd")
public class SystemJhd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "first_classify", length = 20)
    private String firstClassify;

    @Column(name = "second_classify", length = 20)
    private String secondClassify;

    @Column(name = "business_module", length = 20)
    private String businessModule;

    @Column(name = "check_code", length = 20)
    private String checkCode;

    @Column(name = "check_name", length = 100)
    private String checkName;

    @Column(name = "check_rule", length = 500)
    private String checkRule;

    @Column(name = "check_optimize", length = 400)
    private String checkOptimize;

    @Column(name = "check_freq", length = 10)
    private String checkFreq;

    @Column(name = "abnormal_date", length = 20)
    private String abnormalDate;

    @Column(name = "abnormal_count", length = 20)
    private String abnormalCount;

    @Column(name = "business_owner", length = 100)
    private String businessOwner;

    @Column(name = "current_step", length = 200)
    private String currentStep;

    @Column(name = "check_status", length = 100)
    private String checkStatus;

    @Column(name = "verify_result", length = 100)
    private String verifyResult;

    @Column(name = "order_2month", length = 10)
    private String order2month;

    @Column(name = "unclosed_2month", length = 10)
    private String unclosed2month;

    @Column(name = "develop_time", length = 20)
    private String developTime;

    @Column(name = "create_time", length = 30)
    private String createTime;

    @Column(name = "reserve1", length = 100)
    private String reserve1;

    @Column(name = "reserve2", length = 100)
    private String reserve2;

    @Column(name = "source_file", length = 100)
    private String sourceFile;

    // 新增字段：关单量
    @Column(name = "close_order_count", length = 20)
    private String closeOrderCount;

    // 新增字段：关单率
    @Column(name = "close_order_rate", length = 20)
    private String closeOrderRate;

    // 新增字段：及时关单率
    @Column(name = "timely_close_rate", length = 20)
    private String timelyCloseRate;

    // 新增字段：需整改总量
    @Column(name = "need_rectify_total", length = 20)
    private String needRectifyTotal;

    // 新增字段：整改完成量
    @Column(name = "rectif_complete_count", length = 20)
    private String rectifCompleteCount;

    // 新增字段：整改完成率
    @Column(name = "rectif_complete_rate", length = 20)
    private String rectifCompleteRate;

    // 新增字段：整改及时率
    @Column(name = "rectif_timely_rate", length = 20)
    private String rectifTimelyRate;

    @Column(name = "insert_time")
    private LocalDateTime insertTime = LocalDateTime.now();
}
