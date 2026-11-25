package com.metric.audit.loginlog.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 登录审计日志（V50/2026-07-10）。
 *
 * <p>每次登录/退出事件写一行，含客户端 IP/UA 解析后的浏览器/OS/设备维度，
 * 支持多维查询与统计聚合。
 *
 * <p>字段约定（与 V50 migration 一一对应）：
 * <ul>
 *   <li>{@code source}: LOCAL（本地 BCrypt）/ SSO（统一认证）/ LOGOUT（显式退出）</li>
 *   <li>{@code result}: SUCCESS / FAILED</li>
 *   <li>{@code status}: ONLINE（登录后）/ OFFLINE（显式退出后）/ TIMEOUT（超时扫描退出）</li>
 *   <li>{@code durationMinutes}: logout_time - login_time（分钟），ONLINE 时 NULL</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "login_log")
public class LoginLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "client_ip", length = 50)
    private String clientIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "browser_name", length = 50)
    private String browserName;

    @Column(name = "browser_version", length = 20)
    private String browserVersion;

    @Column(name = "os_name", length = 50)
    private String osName;

    @Column(name = "device_type", length = 20)
    private String deviceType;

    @Column(name = "screen_width")
    private Integer screenWidth;

    @Column(name = "screen_height")
    private Integer screenHeight;

    @Column(name = "login_time", nullable = false)
    private LocalDateTime loginTime;

    @Column(name = "logout_time")
    private LocalDateTime logoutTime;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "fail_reason", length = 200)
    private String failReason;

    @Column(name = "jwt_iat")
    private LocalDateTime jwtIat;

    @Column(name = "jwt_sub", length = 100)
    private String jwtSub;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        if (updateTime == null) updateTime = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}