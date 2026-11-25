package com.metric.security.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.UserSession;
import com.metric.security.repository.SysUserRepository;
import com.metric.security.repository.UserSessionRepository;
import com.metric.security.service.SessionService;
import com.metric.security.service.SsoProviderAvailabilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 统一认证 SSO 接口（P3 引入 OAuth2，2026-07 由 OIDC 切换为纯 OAuth2 authorization_code）。
 *
 * 端点说明：
 *   - GET /api/sso/login-url?target=...  —— 前端取统一认证登录入口；把 target 暂存到 HttpSession
 *                                          供 Oauth2LoginSuccessHandler 读回（防 target 在 IdP 回跳时丢失）
 *   - GET /api/sso/post-login?error=...  —— Spring Security OAuth2 完成后中转页；error 非空时跳登录页，
 *                                          否则跳 /sso/callback（带 sessionKey）
 *   - GET /api/sso/me                   —— 返回当前会话的本地 SysUser 形态（与 /api/session/login 同 shape）
 *   - GET /api/sso/front-logout         —— 前端登出：标记 user_session.is_active=0 + 失效 HttpSession + 跳登录页
 *
 * 注：真实的 OAuth2 授权码流程由 Spring Security 的
 *     org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter 处理
 *     （路径 /oauth2/authorization/sso，permitAll），不需本 controller 介入。
 */
@RestController
@RequestMapping("/api/sso")
public class OidcSsoController {

    /** OIDC 流程中暂存 target 的 session 属性名 */
    public static final String SESSION_ATTR_STATE_TARGET = "STATE_TARGET";

    /** Silent SSO 标记，Oauth2LoginFailureHandler 据此跳过 error toast */
    public static final String SESSION_ATTR_SILENT_SSO = "SILENT_SSO";

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private SysUserRepository sysUserRepository;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SsoProviderAvailabilityService ssoAvailability;

    @Value("${app.sso.frontend-base-url:http://localhost:28080}")
    private String frontendBaseUrl;

    @Value("${app.sso.frontend-callback-path:/metric-code/sso/callback}")
    private String frontendCallbackPath;

    @Value("${app.sso.frontend-login-path:/metric-code/login}")
    private String frontendLoginPath;

    @Value("${app.sso.default-target:/metric-code/}")
    private String defaultTarget;

    /**
     * 1. 前端拿登录入口 URL
     *    - 把 target 暂存到 HttpSession（Oauth2LoginSuccessHandler 会在 OIDC 完成后读回）
     *    - 返 {url: "/oauth2/authorization/sso"}
     *    前端拿到 URL 后直接 window.location.href 跳
     */
    @GetMapping("/login-url")
    public ApiResponse<Map<String, String>> loginUrl(
            @RequestParam(required = false) String target,
            HttpServletRequest request) {
        if (!ssoAvailability.isAvailable()) {
            return ApiResponse.error("统一认证平台未启动，请使用本地账号登录");
        }
        if (target != null && !target.isBlank() && isSafeTarget(target)) {
            request.getSession(true).setAttribute(SESSION_ATTR_STATE_TARGET, target);
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("url", "/oauth2/authorization/sso");
        return ApiResponse.success(result);
    }

    /**
     * 1b. Silent SSO 入口（Usermnanager「产品服务」图标点击直达本平台）：
     *   - target 暂存到 HttpSession（success handler 读回）
     *   - 设 silent_sso=true session 标记 → Oauth2LoginFailureHandler 据此不再弹错误 toast
     *   - 302 → /oauth2/authorization/sso?prompt=none
     *   SecurityFilterChainConfig.authorizationRequestResolver 读取 prompt=none 透传到 IdP authorize
     *   - IdP 有本用户 session：直接发 code，本类 callback 流程接管
     *   - IdP 无 session：报 login_required，failure handler 跳 /login 不带 error（用户可手动登录）
     */
    @GetMapping("/silent-entry")
    public ResponseEntity<Void> silentEntry(
            @RequestParam(required = false) String target,
            HttpServletRequest request) {
        if (!ssoAvailability.isAvailable()) {
            return ResponseEntity.status(302)
                    .location(URI.create(frontendBaseUrl + frontendLoginPath + "?nosso=1"))
                    .build();
        }
        String t = defaultTarget;
        if (target != null && !target.isBlank() && isSafeTarget(target)) {
            t = target;
        }
        request.getSession(true).setAttribute(SESSION_ATTR_STATE_TARGET, t);
        request.getSession(true).setAttribute(SESSION_ATTR_SILENT_SSO, Boolean.TRUE);
        return ResponseEntity.status(302)
                .location(URI.create("/oauth2/authorization/sso?prompt=none"))
                .build();
    }

    /** The login page uses this before starting silent SSO. */
    @GetMapping("/availability")
    public ApiResponse<Map<String, Boolean>> availability() {
        return ApiResponse.success(Map.of("available", ssoAvailability.isAvailable()));
    }

    /**
     * 2. 平台回调后中转页（Oauth2LoginSuccessHandler 已 302 到 /sso/callback?sessionKey=...
     *    本端点主要给 Oauth2LoginFailureHandler 失败时调用，跳登录页带 error）
     */
    @GetMapping("/post-login")
    public ResponseEntity<Void> postLogin(
            @RequestParam(required = false) String error) {
        String url;
        if (error != null && !error.isBlank()) {
            url = frontendBaseUrl + frontendLoginPath
                    + "?error=" + URLEncoder.encode(error, StandardCharsets.UTF_8);
        } else {
            url = frontendBaseUrl + frontendCallbackPath;
        }
        return ResponseEntity.status(302).location(URI.create(url)).build();
    }

    /**
     * 3. 前端拿当前登录用户信息（与 /api/session/login 响应同 shape）
     *    - 已认证（OidcUser 来自 OIDC）→ 查本地 sys_user
     *    - 未认证但带 X-Session-Key → 查 user_session 反查 sys_user
     *    - 都拿不到 → 401（被 Spring Security 拦截）
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@org.springframework.web.bind.annotation.RequestHeader(value = SessionServiceCompat.HEADER, required = false) String sessionKeyHeader,
                                               @org.springframework.web.bind.annotation.RequestParam(value = SessionServiceCompat.QUERY_PARAM, required = false) String sessionKeyParam) {
        // 1. 优先看 OidcUser（OIDC 流程刚完成时可能还在 SecurityContext）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SysUser user = null;
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof OidcUser oidc) {
            String sub = oidc.getSubject();
            user = sysUserRepository.findByExternalId(sub).orElse(null);
        }

        // 2. 兜底：X-Session-Key / sessionKey query 参数
        if (user == null) {
            String sessionKey = sessionKeyHeader != null ? sessionKeyHeader : sessionKeyParam;
            if (sessionKey != null && !sessionKey.isBlank()) {
                Optional<UserSession> opt = sessionRepository.findBySessionKey(sessionKey);
                if (opt.isPresent() && opt.get().getIsActive() != null && opt.get().getIsActive() == 1) {
                    String username = opt.get().getUsername();
                    user = sysUserRepository.findByDisplayName(username).orElse(null);
                    if (user == null) user = sysUserRepository.findByUsername(username).orElse(null);
                }
            }
        }

        if (user == null) {
            return ApiResponse.error(401, "未登录");
        }

        return ApiResponse.success(sessionService.buildFullProfileResponse(user));
    }

    /**
     * 4. 前端登出：
     *    - 标记 user_session.is_active=0
     *    - 失效 HttpSession（清 JSESSIONID）
     *    - 跳登录页
     */
    @GetMapping("/front-logout")
    public ResponseEntity<Void> frontLogout(
            @org.springframework.web.bind.annotation.RequestHeader(value = SessionServiceCompat.HEADER, required = false) String sessionKeyHeader,
            @org.springframework.web.bind.annotation.RequestParam(value = SessionServiceCompat.QUERY_PARAM, required = false) String sessionKeyParam,
            HttpServletRequest request) {
        String sessionKey = sessionKeyHeader != null ? sessionKeyHeader : sessionKeyParam;
        if (sessionKey != null && !sessionKey.isBlank()) {
            sessionRepository.findBySessionKey(sessionKey).ifPresent(s -> {
                s.setIsActive(0);
                s.setLogoutTime(LocalDateTime.now());
                sessionRepository.save(s);
            });
        }
        // 失效 HttpSession（Spring 内部 OIDC 用的 session）
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            try { httpSession.invalidate(); } catch (Exception ignore) {}
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.status(302)
                .location(URI.create(frontendBaseUrl + frontendLoginPath))
                .build();
    }

    private boolean isSafeTarget(String t) {
        if (t == null) return false;
        String trimmed = t.trim();
        return trimmed.startsWith("/") && !trimmed.startsWith("//");
    }

    /** 复用 SessionKeyAuthenticationFilter 的 header / query 常量名 */
    private static final class SessionServiceCompat {
        static final String HEADER = "X-Session-Key";
        static final String QUERY_PARAM = "sessionKey";
    }
}
