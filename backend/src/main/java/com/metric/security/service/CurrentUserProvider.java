package com.metric.security.service;

import com.metric.security.entity.SysUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 当前登录用户解析器（统一取代散落各处的硬编码 "system"）
 *
 * 用法：
 *   @Autowired private CurrentUserProvider currentUserProvider;
 *   String operator = currentUserProvider.operatorName();  // "管理员" 或 "zhou"
 *   Optional<SysUser> u = currentUserProvider.currentUser();
 *
 * 行为约定：
 *   - operatorName()：返回 displayName（优先），缺失/空白时回落 username；完全无登录态时回落 "system"
 *   - currentUser()：登录态返回 SysUser；未登录 / Scheduled / Import 场景返回 Optional.empty()
 *
 * 设计要点：
 *   - SecurityContextHolder 主语是 SysUser（由 SessionKeyAuthenticationFilter 写入），与计划一致
 *   - 兜底 "system" 兼容定时任务、Excel 导入等无登录上下文路径
 *   - 任何异常都返回 "system"，不让操作日志路径因为 SecurityContext 异常被打断
 */
@Component
public class CurrentUserProvider {

    public static final String FALLBACK = "system";

    /** 取操作人显示名；返回 displayName（优先）→ username → "system" */
    public String operatorName() {
        Optional<SysUser> opt = currentUser();
        if (opt.isEmpty()) return FALLBACK;
        SysUser u = opt.get();
        String dn = u.getDisplayName();
        if (dn != null && !dn.isBlank()) return dn;
        String un = u.getUsername();
        return (un != null && !un.isBlank()) ? un : FALLBACK;
    }

    /** 取当前登录 SysUser；未登录 / 调度任务 / 导入路径返回 Optional.empty() */
    public Optional<SysUser> currentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return Optional.empty();
            Object p = auth.getPrincipal();
            if (p instanceof SysUser u) {
                return Optional.of(u);
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
