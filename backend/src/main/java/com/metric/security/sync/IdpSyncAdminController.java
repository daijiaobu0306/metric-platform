package com.metric.security.sync;

import com.metric.shared.dto.ApiResponse;
import com.metric.security.entity.SysUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IdP 同步管理接口（P4 引入）
 *   - GET  /api/admin/sso/sync/logs   —— 看最近同步日志
 *   - POST /api/admin/sso/sync         —— 手动触发同步
 *
 * 鉴权：要求 Spring Security 已认证 + isAdmin（admin 才能触发）
 *       SecurityContextHolder 里的 principal 是 SysUser（SessionKeyAuthenticationFilter 写入）
 */
@RestController
@RequestMapping("/api/admin/sso/sync")
public class IdpSyncAdminController {

    @Autowired
    private IdpUserSyncService idpUserSyncService;

    @Autowired
    private IdpSyncLogRepository idpSyncLogRepository;

    @GetMapping("/logs")
    public ApiResponse<List<IdpSyncLog>> logs() {
        return ApiResponse.success(idpSyncLogRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 20,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "startedAt"))
        ).getContent());
    }

    @PostMapping
    public ApiResponse<IdpSyncLog> trigger() {
        if (!isCurrentUserAdmin()) {
            return ApiResponse.error(403, "需要管理员权限");
        }
        IdpSyncLog log = idpUserSyncService.runManualSync();
        return ApiResponse.success(log);
    }

    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        Object p = auth.getPrincipal();
        if (p instanceof SysUser u) {
            return u.getIsAdmin() != null && u.getIsAdmin() == 1;
        }
        return false;
    }
}