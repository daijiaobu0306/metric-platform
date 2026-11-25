package com.metric.audit.loginlog.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

/**
 * 登录日志多维查询请求（2026-07-10）。
 *
 * <p>所有字段可选；为空则不过滤。模糊字段（username / clientIp）走 LIKE %x%。
 */
@Data
public class LoginLogQueryRequest {
    private int page = 0;
    private int size = 20;

    private String username;        // LIKE %username%
    private String source;          // LOCAL / SSO / LOGOUT（精确）
    private String result;          // SUCCESS / FAILED（精确）
    private String status;          // ONLINE / OFFLINE / TIMEOUT（精确）
    private String clientIp;        // LIKE %ip%
    private String browserName;     // 精确
    private String osName;          // 精确
    private String deviceType;      // 精确

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime loginTimeStart;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime loginTimeEnd;
}