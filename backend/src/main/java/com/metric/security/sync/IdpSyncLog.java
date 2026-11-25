package com.metric.security.sync;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * IdP 用户/角色同步日志（P4/V42）
 *  - 每次 @Scheduled 触发或 admin 手动触发都写一行
 *  - status: RUNNING / SUCCESS / FAILED
 *  - trigger_type: scheduled / manual
 */
@Data
@Entity
@Table(name = "idp_sync_log")
public class IdpSyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "users_synced")
    private Integer usersSynced = 0;

    @Column(name = "roles_synced")
    private Integer rolesSynced = 0;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "trigger_type", length = 20)
    private String triggerType;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) createTime = LocalDateTime.now();
        if (startedAt == null) startedAt = createTime;
    }
}