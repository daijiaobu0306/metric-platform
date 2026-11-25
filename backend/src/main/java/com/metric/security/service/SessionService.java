package com.metric.security.service;

import com.metric.security.entity.SysMenu;
import com.metric.security.entity.SysRole;
import com.metric.security.entity.SysRoleMenu;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.SysUserRole;
import com.metric.security.entity.UserSession;
import com.metric.security.repository.*;
import com.metric.shared.util.DurationCeil;
import com.metric.config.repository.SystemConfigRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 集中所有"建会话 / 计算权限 / 拼装登录响应"逻辑，给三条登录入口共用：
 *   1. 自身登录       — SessionController.login
 *   2. 访问链接 SSO   — SsoAccessLinkController.access-link
 *   3. OIDC SSO       — Oauth2LoginSuccessHandler (P3 引入)
 *
 * 抽取前这三处各自重复了：
 *   - 失效该 displayName 的旧活跃 user_session
 *   - 生成 UUID sessionKey 并 INSERT
 *   - 计算 admin/角色→菜单的 permissions 列表
 *   - 拼装 6 字段响应（sessionKey / displayName / idleTimeout / isAdmin / permissions + username）
 *
 * 本类只做纯 Java 抽取，无行为变化。P2 引入 Spring Security 后 SessionKeyAuthenticationFilter
 * 会复用本类的 computeAuthorities() 来填充 SecurityContextHolder。
 */
@Service
public class SessionService {

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private SysUserRepository sysUserRepository;

    @Autowired
    private SysUserRoleRepository userRoleRepository;

    @Autowired
    private SysRoleMenuRepository roleMenuRepository;

    @Autowired
    private SysMenuRepository menuRepository;

    @Autowired
    private SysRoleRepository sysRoleRepository;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    /** 会话来源常量：自身登录 */
    public static final String SOURCE_LOCAL = "local";
    /** 会话来源常量：访问链接 SSO */
    public static final String SOURCE_ACCESS_LINK = "sso_access_link";
    /** 会话来源常量：OIDC SSO */
    public static final String SOURCE_OIDC = "sso_oidc";

    /** 默认角色名（V40 迁移创建） */
    private static final String DEFAULT_ROLE_NAME = "普通用户";

    /**
     * 登录落地页兜底权限：账号无角色 / 角色无菜单时仍能进入系统。
     * 只包含只读的运营观测页，不含任何管理入口，避免兜底变成越权。
     */
    private static final List<String> DEFAULT_LANDING_ROUTES = List.of("/observation");

    /** "普通用户"角色 id 缓存（同 SsoAccessLinkController 既有写法） */
    private volatile Long cachedNormalUserRoleId = null;

    /**
     * 配置键：是否强制「同一账号只允许一个活跃会话」。
     *
     * <p>历史行为是无条件单会话：任何人用同一账号登录都会把别人顶下线。
     * 平台目前是团队共用一个 admin 账号（也常多开标签页），这种互踢会让人莫名被踢回登录页，
     * 所以默认改为<b>允许多会话</b>；确有单点登录要求时，在 system_config 里加一行
     * {@code session_single_active = true} 即可恢复旧行为。
     */
    private static final String CONFIG_SINGLE_SESSION = "session_single_active";

    // =================================================================
    // 1. 创建会话（三入口共用）
    // =================================================================

    /**
     * 创建新会话。
     * 默认保留该账号其它活跃会话（多标签/多人共用账号不再互踢）；
     * 仅当 system_config.session_single_active=true 时才失效旧会话。
     * 返回 sessionKey（UUID）。
     */
    public String createSession(SysUser user, String source, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 仅在显式开启单会话模式时，失效该用户的旧活跃会话
        if (isSingleSessionEnforced()) {
            List<UserSession> staleSessions = sessionRepository.findByIsActiveOrderByLoginTimeDesc(1);
            for (UserSession stale : staleSessions) {
                if (user.getId().equals(stale.getUserId()) && stale.getIsActive() == 1) {
                    stale.setIsActive(0);
                    stale.setLogoutTime(now);
                    stale.setDurationMinutes(DurationCeil.ceilMinutes(Duration.between(stale.getLoginTime(), now)));
                    sessionRepository.save(stale);
                }
            }
        }

        // 2. 创建新会话
        String sessionKey = UUID.randomUUID().toString();
        UserSession session = new UserSession();
        session.setSessionKey(sessionKey);
        session.setUserId(user.getId());
        session.setUsername(user.getUsername());
        session.setClientIp(extractClientIp(request));
        String ua = request.getHeader("User-Agent");
        session.setUserAgent(ua != null ? ua : "");
        session.setLoginTime(now);
        session.setLastHeartbeat(now);
        session.setDurationMinutes(0);
        session.setIsActive(1);
        session.setSource(source);   // V42 字段
        sessionRepository.save(session);

        return sessionKey;
    }

    // =================================================================
    // 2. 计算用户菜单权限路径列表（admin → 全部菜单；其他 → 角色→菜单聚合）
    // =================================================================

    public List<String> computePermissions(SysUser user) {
        if (user.getIsAdmin() != null && user.getIsAdmin() == 1) {
            return menuRepository.findByStatusOrderBySortOrderAsc(1)
                    .stream().map(SysMenu::getRoutePath).collect(Collectors.toList());
        }
        List<Long> roleIds = userRoleRepository.findByUserId(user.getId())
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        Set<Long> menuIds = new HashSet<>();
        for (Long roleId : roleIds) {
            if (sysRoleRepository.findById(roleId).map(r -> Integer.valueOf(1).equals(r.getStatus())).orElse(false) == false) continue;
            roleMenuRepository.findByRoleId(roleId)
                    .forEach(rm -> menuIds.add(rm.getMenuId()));
        }
        List<String> permissions = menuRepository.findAllById(menuIds)
                .stream().filter(m -> Integer.valueOf(1).equals(m.getStatus()))
                .map(SysMenu::getRoutePath).collect(Collectors.toList());

        // 兜底：账号没有角色、角色被停用、或角色尚未绑定任何菜单时，至少给一个落地页。
        // 否则前端路由守卫会在「无权限 → 跳 /observation → 仍无权限」之间无限重定向，
        // 直接卡死浏览器主线程，表现为登录后页面永远无响应。
        if (permissions.isEmpty()) {
            return new ArrayList<>(DEFAULT_LANDING_ROUTES);
        }
        return permissions;
    }

    // =================================================================
    // 3. 拼装 6 字段登录响应（保持与 SessionController.login 字段顺序一致）
    // =================================================================

    public Map<String, Object> buildLoginResponse(SysUser user, String sessionKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionKey", sessionKey);
        result.put("displayName", resolveDisplayName(user));
        result.put("idleTimeout", getIdleTimeout());
        result.put("isAdmin", user.getIsAdmin() != null && user.getIsAdmin() == 1);
        result.put("permissions", computePermissions(user));
        return result;
    }

    /**
     * 7 字段扩展版（含 username）—— 供 /api/sso/me 等需要 username 的接口使用
     */
    public Map<String, Object> buildFullProfileResponse(SysUser user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionKey", "");
        result.put("username", user.getUsername());
        result.put("displayName", resolveDisplayName(user));
        result.put("idleTimeout", getIdleTimeout());
        result.put("isAdmin", user.getIsAdmin() != null && user.getIsAdmin() == 1);
        result.put("permissions", computePermissions(user));
        return result;
    }

    // =================================================================
    // 4. 自动建账号（OIDC 懒创建 + access-link 自动建 复用）
    // =================================================================

    /**
     * 创建一个新的 SysUser（username/password/displayName 必填），并分配"普通用户"角色。
     * 不负责设置 externalId / source / email —— 调用方自行填。
     */
    public SysUser createLocalUser(String username, String displayName,
                                   String bcryptPassword, String source) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(bcryptPassword);
        u.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : username);
        u.setSource(source);
        SysUser saved = sysUserRepository.save(u);

        // 分配"普通用户"角色
        SysUserRole ur = new SysUserRole();
        ur.setUserId(saved.getId());
        ur.setRoleId(resolveNormalUserRoleId());
        userRoleRepository.save(ur);

        return saved;
    }

    public Long resolveNormalUserRoleId() {
        Long cached = cachedNormalUserRoleId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedNormalUserRoleId != null) {
                return cachedNormalUserRoleId;
            }
            // 不依赖 Flyway history 判断 seed 是否存在：数据库可能来自 dump/baseline，
            // history 显示已执行但基础角色实际缺失。缺失时在业务入口幂等补齐，
            // 避免新增用户和 SSO 同步再次因基础角色缺失返回 500。
            SysRole role = sysRoleRepository.findByRoleName(DEFAULT_ROLE_NAME)
                    .orElseGet(() -> {
                        SysRole fallback = new SysRole();
                        fallback.setRoleName(DEFAULT_ROLE_NAME);
                        fallback.setDescription("首次登录基础角色，仅可访问运营观测");
                        fallback.setStatus(1);
                        return sysRoleRepository.save(fallback);
                    });
            Long roleId = role.getId();
            // 补齐基础菜单绑定：部分环境（dump/baseline 导入）角色存在但 sys_role_menu 为空，
            // 新账号自动分配该角色后没有任何菜单，登录后会卡在无权限页之间来回跳。
            ensureRoleHasLandingMenu(roleId);
            cachedNormalUserRoleId = roleId;
            return roleId;
        }
    }

    /** 幂等：确保指定角色至少绑定了只读落地页菜单（/observation）。 */
    private void ensureRoleHasLandingMenu(Long roleId) {
        SysMenu menu = menuRepository.findByRoutePath("/observation").orElse(null);
        if (menu == null) return;
        boolean bound = roleMenuRepository.findByRoleId(roleId).stream()
                .anyMatch(rm -> menu.getId().equals(rm.getMenuId()));
        if (bound) return;
        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(roleId);
        rm.setMenuId(menu.getId());
        roleMenuRepository.save(rm);
    }

    // =================================================================
    // 工具
    // =================================================================

    private String resolveDisplayName(SysUser user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName()
                : user.getUsername();
    }

    public String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip;
        return request.getRemoteAddr();
    }

    public int getIdleTimeout() {
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

    /** 是否强制单会话（system_config.session_single_active=true 时生效，默认关闭） */
    private boolean isSingleSessionEnforced() {
        return systemConfigRepository.findByConfigKey(CONFIG_SINGLE_SESSION)
                .map(cfg -> "true".equalsIgnoreCase(String.valueOf(cfg.getConfigValue()).trim()))
                .orElse(false);
    }

    public boolean isAdmin(SysUser user) {
        return user != null && user.getIsAdmin() != null && user.getIsAdmin() == 1;
    }

    /** 生成不可用的 BCrypt 密码哈希（OIDC 同步用户无密码登录需求） */
    public String encodeUnusablePassword() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode(java.util.UUID.randomUUID().toString());
    }
}
