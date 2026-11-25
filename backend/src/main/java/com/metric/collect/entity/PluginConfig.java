package com.metric.collect.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "plugin_config", indexes = {
    @Index(name = "idx_plugin_config_enabled", columnList = "enabled"),
    @Index(name = "idx_plugin_config_trigger_mode", columnList = "trigger_mode")
})
public class PluginConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "name_en", length = 100)
    private String nameEn;

    @Column(name = "plugin_type", length = 50)
    private String pluginType = "HTTP_API";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "request_url", length = 500)
    private String requestUrl;

    @Column(name = "request_method", length = 20)
    private String requestMethod;

    @Column(name = "request_headers", columnDefinition = "TEXT")
    private String requestHeaders;

    @Column(name = "request_params", columnDefinition = "TEXT")
    private String requestParams;

    @Column(name = "response_params", columnDefinition = "TEXT")
    private String responseParams;

    @Column(name = "trigger_mode", length = 50)
    private String triggerMode = "MANUAL";

    @Column(name = "timer_cron", length = 50)
    private String timerCron;

    @Column(name = "debug_status", length = 20)
    private String debugStatus = "PENDING";

    @Column(name = "debug_response", columnDefinition = "TEXT")
    private String debugResponse;

    @Column(name = "last_debug_time")
    private LocalDateTime lastDebugTime;

    @Column(name = "enabled")
    private Integer enabled = 1;

    @Column(name = "auth_id")
    private Long authId;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
