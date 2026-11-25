package com.metric.collect.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 全局动态通知事件。
 *
 * 前端轮询 /api/notifications/recent 拉取，按 event_type 上色。
 */
@Data
@Entity
@Table(name = "notification_event")
public class NotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DATA_COLLECTION / ALARM / ISSUE / GONGDAN */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", length = 500)
    private String content;

    @Column(name = "related_count")
    private Integer relatedCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}