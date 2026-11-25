package com.metric.gongdan.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "gongdan", indexes = {
    @Index(name = "idx_gongdan_time", columnList = "gongdan_time"),
    @Index(name = "idx_closed_status", columnList = "closed_status"),
    @Index(name = "idx_system_id", columnList = "system_id")
})
public class Gongdan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gongdan_id", length = 50)
    private String gongdanId;

    @Column(name = "system_id")
    private Long systemId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "metric_id")
    private Long metricId;

    @Column(name = "gongdan_title", nullable = false, length = 200)
    private String gongdanTitle;

    @Column(name = "gongdan_content", columnDefinition = "TEXT")
    private String gongdanContent;

    @Column(name = "gongdan_type", length = 50)
    private String gongdanType;

    @Column(name = "current_step", length = 100)
    private String currentStep;

    @Column(name = "current_handler", length = 50)
    private String currentHandler;

    @Column(name = "closed_status")
    private Integer closedStatus;

    @Column(name = "closed_time")
    private LocalDateTime closedTime;

    @Column(name = "gongdan_time")
    private LocalDateTime gongdanTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "external_status", length = 50)
    private String externalStatus;

    @Column(name = "synced")
    private Integer synced = 0;

    @Column(name = "synced_time")
    private LocalDateTime syncedTime;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "alarm_level")
    private Integer alarmLevel;

    @Column(name = "alarm_priority", length = 20)
    private String alarmPriority;

    @Column(name = "alarm_impact", length = 20)
    private String alarmImpact;

    @Column(name = "alarm_object", length = 200)
    private String alarmObject;

    @Column(name = "alarm_first_time")
    private LocalDateTime alarmFirstTime;

    @Column(name = "alarm_last_time")
    private LocalDateTime alarmLastTime;

    @Column(name = "alarm_count")
    private Integer alarmCount;

    @Column(name = "responsible_person", length = 100)
    private String responsiblePerson;

    @Column(name = "second_responsible", length = 100)
    private String secondResponsible;

    @Column(name = "execute_user", length = 50)
    private String executeUser;

    @Column(name = "external_sync_msg", length = 500)
    private String externalSyncMsg;

    @Column(name = "last_query_time")
    private LocalDateTime lastQueryTime;

    /** 上次推送 BOMC 时的标题快照（重新推送前比对内容是否被编辑） */
    @Column(name = "pushed_title", length = 200)
    private String pushedTitle;

    /** 上次推送 BOMC 时的内容快照 */
    @Column(name = "pushed_content", columnDefinition = "TEXT")
    private String pushedContent;

    /** BOMC 终结动作标记：QUASHED=已撤销，ARCHIVED=已归档，空=无 */
    @Column(name = "bomc_action", length = 20)
    private String bomcAction;

    /** 撤销/归档原因（BOMC opin） */
    @Column(name = "bomc_action_reason", length = 500)
    private String bomcActionReason;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (gongdanTime == null) {
            gongdanTime = LocalDateTime.now();
        }
        if (closedStatus == null) {
            closedStatus = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
