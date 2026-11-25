package com.metric.security.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_session")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_key", length = 64, nullable = false, unique = true)
    private String sessionKey;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 50, nullable = false)
    private String username = "管理员";

    @Column(name = "client_ip", length = 50)
    private String clientIp = "";

    @Column(name = "user_agent", length = 500)
    private String userAgent = "";

    @Column(name = "browser_name", length = 50)
    private String browserName = "";

    @Column(name = "browser_version", length = 20)
    private String browserVersion = "";

    @Column(name = "os_name", length = 50)
    private String osName = "";

    @Column(name = "device_type", length = 20)
    private String deviceType = "desktop";

    @Column(name = "screen_width")
    private Integer screenWidth = 0;

    @Column(name = "screen_height")
    private Integer screenHeight = 0;

    @Column(name = "login_time", nullable = false)
    private LocalDateTime loginTime;

    @Column(name = "last_heartbeat", nullable = false)
    private LocalDateTime lastHeartbeat;

    @Column(name = "duration_minutes")
    private Integer durationMinutes = 0;

    @Column(name = "is_active")
    private Integer isActive = 1;

    @Column(name = "logout_time")
    private LocalDateTime logoutTime;

    // ===== V42 OIDC SSO =====
    @Column(name = "source", length = 50)
    private String source;

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
