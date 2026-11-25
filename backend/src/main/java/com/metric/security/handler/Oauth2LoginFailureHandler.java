package com.metric.security.handler;

import com.metric.security.controller.OidcSsoController;
import com.metric.audit.loginlog.service.LoginLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * OAuth2 失败时跳到登录页带 error（Oauth2LoginSuccessHandler 出错时也复用同一个 redirect）。
 *
 * 特殊情况：silent SSO（Usermanager 图标点击）失败时不弹错误 toast，
 * 直接跳登录页让用户手动选择登录方式，避免「自动 silent → 失败 → 弹错误 → 自动 silent → ...」循环。
 */
@Component
public class Oauth2LoginFailureHandler implements AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(Oauth2LoginFailureHandler.class);

    @Autowired
    private LoginLogService loginLogService;

    @Value("${app.sso.frontend-base-url:http://localhost:28080}")
    private String frontendBaseUrl;

    @Value("${app.sso.frontend-login-path:/metric-code/login}")
    private String frontendLoginPath;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        boolean silent = false;
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object flag = session.getAttribute(OidcSsoController.SESSION_ATTR_SILENT_SSO);
            silent = Boolean.TRUE.equals(flag);
            if (silent) {
                session.removeAttribute(OidcSsoController.SESSION_ATTR_SILENT_SSO);
            }
        }

        String errorCode = exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode() : "authentication_failed";

        // 坑：OAuth2AuthenticationException(String) 的单参构造会把字符串当作 errorCode，
        // 而不是 message（内部 new OAuth2Error(code) → description=null → getMessage()=null）。
        // 于是业务侧抛的中文原因全被兜底文案盖住，登录页只能看到「OAuth2 认证失败」。
        // 这里把像是中文业务文案的 errorCode 提回来当详情用。
        String detail = exception.getMessage();
        if ((detail == null || detail.isBlank()) && looksLikeBusinessMessage(errorCode)) {
            detail = errorCode;
        }

        log.warn("SSO authentication failed: code={}, silent={}, message={}", errorCode, silent, detail);

        // LoginLog: 写一条 SSO FAILED 审计行（status=OFFLINE，无真实会话）
        // reasonKey 用 errorCode；sub 解析不到留 null
        String ssoFailureReason = detail != null ? detail : "OAuth2 认证失败";
        try {
            loginLogService.recordSsoLoginFailure(errorCode + ": " + ssoFailureReason, null, request);
        } catch (Exception ignored) {
            // LoginLogService 内部已 log.warn；不让审计失败阻塞 redirect
        }

        String url;
        if (silent && "login_required".equals(errorCode)) {
            // Silent 失败（典型：IdP 无 session → login_required）→ 直接去登录页，不弹错误
            url = frontendBaseUrl + frontendLoginPath + "?nosso=1";
        } else {
            String error = detail != null ? detail : "OAuth2 认证失败";
            url = frontendBaseUrl + frontendLoginPath
                    + "?nosso=1&error=" + URLEncoder.encode(error, StandardCharsets.UTF_8);
        }
        response.sendRedirect(url);
    }

    /**
     * 判断一个字串是「业务提示文案」还是「标准 OAuth2 错误码」。
     * 标准错误码全是短 ASCII（invalid_grant / login_required / invalid_token_response…），
     * 业务侧抛的中文原因含非 ASCII 字符或明显更长。
     */
    private static boolean looksLikeBusinessMessage(String code) {
        if (code == null || code.isBlank()) return false;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) > 0x7F) return true;
        }
        return code.length() > 48;
    }
}
