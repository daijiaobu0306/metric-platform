package com.metric.alarm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alarm_channel_config")
public class AlarmChannelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_type", nullable = false, length = 20)
    private String channelType;  // FILE, HTTP_API

    @Column(name = "channel_name", nullable = false, length = 50)
    private String channelName;

    @Column(name = "config_key", nullable = false, unique = true, length = 50)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;  // 文件路径或插件ID

    @Column(name = "template", length = 500)
    private String template;  // 文件内容模板

    @Column(name = "scene", length = 50)
    private String scene;  // 适用场景：system_health, category_health, sub_category_health, metric_health, metric_value, raw_data, issue_overdue, custom

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "enabled", nullable = false)
    private Integer enabled = 1;  // 1=启用, 0=禁用

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (enabled == null) {
            enabled = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
