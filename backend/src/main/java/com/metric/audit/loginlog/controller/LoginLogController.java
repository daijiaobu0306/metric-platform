package com.metric.audit.loginlog.controller;

import com.metric.audit.loginlog.dto.LoginLogDashboard;
import com.metric.audit.loginlog.dto.LoginLogQueryRequest;
import com.metric.audit.loginlog.entity.LoginLog;
import com.metric.audit.loginlog.service.LoginLogService;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 登录日志查询/看板端点（V50/2026-07-10）。
 *
 * <p>admin only：与现有 /user /role 一致（非 admin 返 403）。
 */
@RestController
@RequestMapping("/api/login-log")
public class LoginLogController {

    @Autowired
    private LoginLogService loginLogService;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    @GetMapping
    public ApiResponse<Page<LoginLog>> query(LoginLogQueryRequest q) {
        if (!isAdmin()) {
            return ApiResponse.error(403, "需要管理员权限");
        }
        return ApiResponse.success(loginLogService.query(q));
    }

    @GetMapping("/dashboard")
    public ApiResponse<LoginLogDashboard> dashboard() {
        if (!isAdmin()) {
            return ApiResponse.error(403, "需要管理员权限");
        }
        return ApiResponse.success(loginLogService.getDashboard());
    }

    @GetMapping("/online-count")
    public ApiResponse<Map<String, Long>> onlineCount() {
        if (!isAdmin()) {
            return ApiResponse.error(403, "需要管理员权限");
        }
        return ApiResponse.success(Map.of("count", loginLogService.getOnlineCount()));
    }

    /**
     * 复用 main 现有写法（参考 SessionService.isAdmin 模式）。
     */
    private boolean isAdmin() {
        return currentUserProvider.currentUser()
                .map(u -> u.getIsAdmin() != null && u.getIsAdmin() == 1)
                .orElse(false);
    }
}