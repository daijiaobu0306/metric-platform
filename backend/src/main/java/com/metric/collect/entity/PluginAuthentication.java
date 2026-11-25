package com.metric.collect.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "plugin_authentication")
public class PluginAuthentication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "login_url", nullable = false, length = 500)
    private String loginUrl;

    @Column(name = "login_method", length = 10)
    private String loginMethod = "POST";

    @Column(name = "login_content_type", length = 50)
    private String loginContentType = "application/json";

    @Column(name = "login_body", columnDefinition = "TEXT")
    private String loginBody;

    @Column(name = "login_headers", columnDefinition = "TEXT")
    private String loginHeaders;

    @Column(name = "token_path", nullable = false, length = 200)
    private String tokenPath;

    @Column(name = "token_header_name", length = 100)
    private String tokenHeaderName = "Authorization";

    @Column(name = "token_value_prefix", length = 50)
    private String tokenValuePrefix = "Bearer ";

    @Column(name = "ttl_seconds")
    private Integer ttlSeconds = 3600;

    @Column(name = "enabled")
    private Integer enabled = 1;

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
