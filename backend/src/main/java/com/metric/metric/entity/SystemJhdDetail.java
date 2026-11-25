package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "system_jhd_detail")
public class SystemJhdDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "first_level_category", length = 100)
    private String firstLevelCategory;

    @Column(name = "second_level_category", length = 100)
    private String secondLevelCategory;

    @Column(name = "ws_id", length = 100)
    private String wsId;

    @Column(name = "audit_id", length = 100)
    private String auditId;

    @Column(name = "audit_name", length = 100)
    private String auditName;

    @Column(name = "business_supervisor_name", length = 100)
    private String businessSupervisorName;

    @Column(name = "deal_person", length = 100)
    private String dealPerson;

    @Column(name = "deal_person_name", length = 100)
    private String dealPersonName;

    @Column(name = "status", length = 100)
    private String status;

    @Column(name = "if_ok", length = 100)
    private String ifOk;

    @Column(name = "create_date", length = 50)
    private String createDate;

    @Column(name = "modify_date", length = 50)
    private String modifyDate;

    @Column(name = "limit_date", length = 50)
    private String limitDate;

    @Column(name = "if_limit_ok", length = 100)
    private String ifLimitOk;

    @Column(name = "remark", length = 3000)
    private String remark;

    @Column(name = "update_time", length = 100)
    private String updateTime;

    @Column(name = "is_need_rectify", length = 100)
    private String isNeedRectify;

    @Column(name = "rectify_reason", length = 500)
    private String rectifyReason;

    @Column(name = "rectify_status", length = 100)
    private String rectifyStatus;

    @Column(name = "fix_situation", length = 100)
    private String fixSituation;

    @Column(name = "fix_count", length = 100)
    private String fixCount;

    @Column(name = "rectify_deadline", length = 100)
    private String rectifyDeadline;

    @Column(name = "rectify_deadline_sj", length = 100)
    private String rectifyDeadlineSj;

    @Column(name = "is_overdue", length = 100)
    private String isOverdue;

    @Column(name = "rsrv_str1", length = 100)
    private String rsvrStr1;

    @Column(name = "rsrv_str2", length = 100)
    private String rsvrStr2;

    @Column(name = "rsrv_str3", length = 500)
    private String rsvrStr3;

    @Column(name = "rsrv_str4", length = 500)
    private String rsvrStr4;

    @Column(name = "rsrv_str5", length = 500)
    private String rsvrStr5;

    @Column(name = "rsrv_str6", length = 500)
    private String rsvrStr6;

    @Column(name = "source_file", length = 100)
    private String sourceFile;

    @Column(name = "insert_time")
    private LocalDateTime insertTime = LocalDateTime.now();
}