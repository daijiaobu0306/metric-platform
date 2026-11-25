package com.metric.security.filter;

import com.metric.security.entity.SysMenu;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.UserSession;
import com.metric.security.repository.SysMenuRepository;
import com.metric.security.repository.SysRoleMenuRepository;
import com.metric.security.repository.SysRoleRepository;
import com.metric.security.repository.SysUserRepository;
import com.metric.security.repository.SysUserRoleRepository;
import com.metric.config.repository.SystemConfigRepository;
import com.metric.security.repository.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把前端 sessionStorage.sessionKey 映射到 Spring Security 的 SecurityContextHolder。
 *
 *   1. 从 X-Session-Key header 或 sessionKey query 参数取值
 *   2. 带本平台 sessionKey 时优先解析本地用户；仅没有 key 时保留已有 OIDC 认证
 *   3. user_session 表中查：is_active=1 且未过 idle 超时
 *   4. 通过 s.user_id 查找启用的本地用户
 *   5. 计算 authorities：admin → 全部菜单；其它 → 角色→菜单聚合
 *   6. 写入 SecurityContextHolder
 *
 * 与 OIDC 的协作：OAuth2 回调没有本平台 sessionKey，沿用 Spring 内部认证；
 * 回调进入 SPA 后的 API 请求带 sessionKey，必须切换为本地 SysUser principal。
 */
@Component
public class SessionKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Session-Key";
    public static final String QUERY_PARAM = "sessionKey";

    private final UserSessionRepository sessionRepository;
    private final SysUserRepository sysUserRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleMenuRepository roleMenuRepository;
    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;
    private final SystemConfigRepository systemConfigRepository;

    public SessionKeyAuthenticationFilter(UserSessionRepository sessionRepository,
                                          SysUserRepository sysUserRepository,
                                          SysUserRoleRepository userRoleRepository,
                                          SysRoleMenuRepository roleMenuRepository,
                                          SysRoleRepository roleRepository,
                                          SysMenuRepository menuRepository,
                                          SystemConfigRepository systemConfigRepository) {
        this.sessionRepository = sessionRepository;
        this.sysUserRepository = sysUserRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleMenuRepository = roleMenuRepository;
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.systemConfigRepository = systemConfigRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // 1. 取 sessionKey（header 优先，query 兜底）
        String sessionKey = req.getHeader(HEADER);
        if (sessionKey == null || sessionKey.isBlank()) {
            sessionKey = req.getParameter(QUERY_PARAM);
        }
        if (sessionKey == null || sessionKey.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        // OIDC 的 HttpSession 可能还在，但带 sessionKey 的 API 必须使用本平台会话。
        // 失效的本平台 key 也不能借 OAuth2 Cookie 绕过本平台会话校验。
        SecurityContextHolder.clearContext();

        // 2. 校验 user_session
        Optional<UserSession> opt = sessionRepository.findBySessionKey(sessionKey);
        if (opt.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }
        UserSession s = opt.get();
        if (s.getIsActive() == null || s.getIsActive() != 1) {
            chain.doFilter(req, res);
            return;
        }
        // 4. idle 超时校验
        if (s.getLastHeartbeat() != null) {
            int idleTimeout = readIdleTimeoutMinutes();
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(idleTimeout);
            if (s.getLastHeartbeat().isBefore(threshold)) {
                s.setIsActive(0);
                s.setLogoutTime(LocalDateTime.now());
                sessionRepository.save(s);
                chain.doFilter(req, res);
                return;
            }
        }

        // 会话必须绑定真实用户 ID；旧会话在迁移后失效。
        if (s.getUserId() == null) {
            chain.doFilter(req, res);
            return;
        }
        Optional<SysUser> userOpt = sysUserRepository.findById(s.getUserId());
        if (userOpt.isEmpty() || !Integer.valueOf(1).equals(userOpt.get().getStatus())) {
            chain.doFilter(req, res);
            return;
        }
        SysUser user = userOpt.get();

        // 6. 构造 Authentication
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user,                    // principal = SysUser（controller 端可强转拿到 user 信息）
                "N/A",                   // credentials（不在 SecurityContext 里放真实密码）
                computeAuthorities(user)
        );
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));

        // 6.5 把当前会话登录时间挂到 request 属性上，供 controller 端取用
        //      （用于通知系统按 loginTime 过滤，避免上一会话的历史 toast 弹给下一用户）
        if (s.getLoginTime() != null) {
            req.setAttribute("currentSessionLoginTime", s.getLoginTime());
        }
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(req, res);
    }

    private List<SimpleGrantedAuthority> computeAuthorities(SysUser user) {
        // 与 SessionService.computePermissions 同源；为简化在 filter 内联实现
        if (user.getIsAdmin() != null && user.getIsAdmin() == 1) {
            return menuRepository.findByStatusOrderBySortOrderAsc(1)
                    .stream().map(SysMenu::getRoutePath)
                    .map(SessionKeyAuthenticationFilter::toAuthority)
                    .collect(Collectors.toList());
        }
        Set<Long> menuIds = new HashSet<>();
        for (var ur : userRoleRepository.findByUserId(user.getId())) {
            // 停用角色不再提供权限。
            if (roleRepository.findById(ur.getRoleId())
                    .map(r -> !Integer.valueOf(1).equals(r.getStatus())).orElse(true)) continue;
            for (var rm : roleMenuRepository.findByRoleId(ur.getRoleId())) {
                menuIds.add(rm.getMenuId());
            }
        }
        return menuRepository.findAllById(menuIds)
                .stream().filter(m -> Integer.valueOf(1).equals(m.getStatus())).map(SysMenu::getRoutePath)
                .map(SessionKeyAuthenticationFilter::toAuthority)
                .collect(Collectors.toList());
    }

    private static SimpleGrantedAuthority toAuthority(String routePath) {
        // "/observation" -> "ROLE_/_observation"（保留路径语义，且符合 SimpleGrantedAuthority 字符要求）
        return new SimpleGrantedAuthority("ROLE_" + routePath.replace("/", "_").replace("-", "_"));
    }

    private int readIdleTimeoutMinutes() {
        return systemConfigRepository.findByConfigKey("session_idle_timeout")
                .map(cfg -> {
                    try {
                        return Integer.parseInt(cfg.getConfigValue());
                    } catch (NumberFormatException e) {
                        return 20;
                    }
                })
                .orElse(20);
    }
}
