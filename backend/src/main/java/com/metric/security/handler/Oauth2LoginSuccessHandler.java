package com.metric.security.handler;

import com.metric.security.controller.OidcSsoController;
import com.metric.security.entity.SysUser;
import com.metric.security.repository.SysUserRepository;
import com.metric.security.service.SessionService;
import com.metric.audit.loginlog.service.LoginLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OAuth2 成功后的统一处理（P3 引入，2026-07 切换自 OIDC）：
 *   1. 从 OAuth2User.attributes 取 sub → sys_user
 *   2. 用 SessionService 建 user_session（写 source='sso_oidc'）
 *   3. 从 HttpSession 读 STATE_TARGET（/api/sso/login-url 时存的）
 *   4. 302 到 {frontendBaseUrl}{frontendCallbackPath}?sessionKey=...&target=...&displayName=...&isAdmin=...
 *
 *   与 SsoAccessLinkController 的 302 形态保持一致，前端 /sso/callback 路由可直接复用。
 */
@Component
public class Oauth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    @Autowired
    private SysUserRepository sysUserRepository;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private LoginLogService loginLogService;

    @Value("${app.sso.frontend-base-url:http://localhost:28080}")
    private String frontendBaseUrl;

    @Value("${app.sso.frontend-callback-path:/metric-code/sso/callback}")
    private String frontendCallbackPath;

    @Value("${app.sso.frontend-login-path:/metric-code/login}")
    private String frontendLoginPath;

    @Value("${app.sso.default-target:/metric-code/}")
    private String defaultTarget;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2User oauth2 = (OAuth2User) authentication.getPrincipal();
            final String sub;
            String subTmp = oauth2.getAttribute("sub");
            if (subTmp == null || subTmp.isBlank()) {
                subTmp = oauth2.getName();
            }
            sub = subTmp;
            SysUser user = sysUserRepository.findByExternalId(sub)
                    .orElseThrow(() -> new IllegalStateException(
                            "OAuth2 成功但本地用户缺失 external_id=" + sub
                                    + " — OidcSsoUserService 应已创建"));

            // 1. 建 user_session
            String sessionKey = sessionService.createSession(user, SessionService.SOURCE_OIDC, request);
            // LoginLog: 同步写一条 SSO SUCCESS ONLINE 审计行
            loginLogService.recordSsoLoginSuccess(user, request);

            // 2. 取 target（login-url 时存的）
            String target = (String) request.getSession().getAttribute(OidcSsoController.SESSION_ATTR_STATE_TARGET);
            if (target == null || target.isBlank()) {
                target = defaultTarget;
            }

            // 3. 拼回调 URL（与 access-link 形态一致）
            //    把 idleTimeout / permissions 一起带上：前端 /sso/callback 拿齐档案就能直接进系统，
            //    不必再等 /api/session/init（该请求一旦挂住会把整个登录卡死）。
            boolean isAdmin = user.getIsAdmin() != null && user.getIsAdmin() == 1;
            StringBuilder callbackUrl = new StringBuilder(frontendBaseUrl + frontendCallbackPath
                    + "?sessionKey=" + sessionKey
                    + "&target=" + URLEncoder.encode(target, StandardCharsets.UTF_8)
                    + "&username=" + URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8)
                    + "&displayName=" + URLEncoder.encode(
                            user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                            StandardCharsets.UTF_8)
                    + "&isAdmin=" + isAdmin
                    + "&idleTimeout=" + sessionService.getIdleTimeout());
            // 管理员前端走全量菜单，不需要 permissions；普通用户的菜单完全由它决定，必须带上
            if (!isAdmin) {
                callbackUrl.append("&permissions=")
                        .append(URLEncoder.encode(permissionsJson(user), StandardCharsets.UTF_8));
            }

            response.sendRedirect(callbackUrl.toString());
        } catch (Exception e) {
            // 出错兜底：跳登录页带 error
            String errorUrl = frontendBaseUrl + frontendLoginPath
                    + "?error=" + URLEncoder.encode("OAuth2 登录失败：" + e.getMessage(), StandardCharsets.UTF_8);
            response.sendRedirect(errorUrl);
        }
    }

    /**
     * 菜单权限序列化成 JSON 数组字符串，拼进回调 URL。
     * 任何异常都返回 "[]" —— 拼 URL 失败绝不能反过来阻断登录。
     */
    private String permissionsJson(SysUser user) {
        try {
            List<String> perms = sessionService.computePermissions(user);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(perms != null ? perms : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }
}