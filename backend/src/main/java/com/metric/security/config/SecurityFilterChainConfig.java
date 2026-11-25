package com.metric.security.config;

import com.metric.security.filter.SessionKeyAuthenticationEntryPoint;
import com.metric.security.filter.SessionKeyAuthenticationFilter;
import com.metric.security.handler.Oauth2LoginFailureHandler;
import com.metric.security.handler.Oauth2LoginSuccessHandler;
import com.metric.security.service.OidcSsoUserService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Spring Security 6 过滤器链配置（P2 引入，P3 起挂 OAuth2 统一认证）。
 *
 * 设计要点：
 *   - CSRF 关闭：API-only，使用 X-Session-Key header 鉴权（不依赖 cookie 携带会话）
 *   - session policy = IF_REQUIRED：OAuth2 流程内部用得到 HttpSession，自身登录走 user_session 表
 *   - 把 SessionKeyAuthenticationFilter 放在 UsernamePasswordAuthenticationFilter 之前
 *   - /api/** 的入口点（entry point）返回 JSON 401，让前端 axios 拦截器统一处理
 *   - permitAll 白名单 = 三登录入口 + 静态资源 + OAuth2 端点
 *   - OAuth2 走 Spring Security oauth2Login()，挂自定义 OidcSsoUserService / Success / Failure
 *   - 自定义 OAuth2AuthorizationRequestResolver：支持 ?prompt=none silent SSO
 *
 * 公开路径解释：
 *   /api/session/login|init|heartbeat|end|config  —— 自身登录与会话管理
 *   /api/sso/access-link                          —— 访问链接 SSO（信任 username，由 nginx 限流兜底）
 *   /api/sso/login-url|silent-entry|post-login|me|front-logout —— OAuth2 SSO 入口/中转/登出
 *   /oauth2/authorization/sso                     —— Spring Security OAuth2 触发点
 *   /login/oauth2/code/**                         —— OAuth2 回调路径
 *   /uploads/**                                   —— 业务文件
 *   /actuator/**, /error, /favicon.ico
 *
 * 任何不在白名单的 /api/** 都要求通过 SessionKeyAuthenticationFilter 认证。
 */
@Configuration
@EnableWebSecurity
public class SecurityFilterChainConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            SessionKeyAuthenticationFilter sessionKeyFilter,
            SessionKeyAuthenticationEntryPoint entryPoint,
            OidcSsoUserService oidcSsoUserService,
            Oauth2LoginSuccessHandler oauth2SuccessHandler,
            Oauth2LoginFailureHandler oauth2FailureHandler,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver
    ) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})  // 委派给 WebMvcConfig
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())  // 登出走 /api/sso/front-logout 自己实现

            .addFilterBefore(sessionKeyFilter, UsernamePasswordAuthenticationFilter.class)

            .authorizeHttpRequests(a -> a
                // 自身登录 + 会话管理
                .requestMatchers(
                        "/api/session/login",
                        "/api/session/init",
                        "/api/session/heartbeat",
                        "/api/session/end",
                        "/api/session/config"
                ).permitAll()

                // 三种 SSO 入口
                .requestMatchers(
                        "/api/sso/access-link",
                        "/api/sso/availability",
                        "/api/sso/login-url",
                        "/api/sso/silent-entry",
                        "/api/sso/post-login",
                        "/api/sso/me",
                        "/api/sso/front-logout"
                ).permitAll()

                // 登录页在未登录状态下要读取平台名称 / Logo 等品牌配置，
                // 否则 GET /api/platform/config 会 401，登录页一进来就弹「Request failed with status code 401」。
                // 只放行只读的 GET，保存仍要求鉴权。
                .requestMatchers(HttpMethod.GET, "/api/platform/config", "/api/platform/config/**").permitAll()

                // 免登录专屏分享：控制器会用独立的分享密钥和短期访客会话再次校验，
                // 不复用 X-Session-Key，也不开放现有 dashboard workspace 接口。
                .requestMatchers("/api/dashboard-share/s/**").permitAll()

                // OAuth2 触发与回调
                .requestMatchers(
                        "/oauth2/authorization/sso",
                        "/login/oauth2/code/**"
                ).permitAll()

                // 公开静态 / 健康检查
                .requestMatchers("/actuator/**", "/error", "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()

                // 其余全部要鉴权
                .anyRequest().authenticated()
            )

            .oauth2Login(o -> o
                    .authorizationEndpoint(a -> a
                            .authorizationRequestResolver(authorizationRequestResolver))
                    .userInfoEndpoint(u -> u.userService(oidcSsoUserService))
                    .successHandler(oauth2SuccessHandler)
                    .failureHandler(oauth2FailureHandler)
            )

            .exceptionHandling(e -> e
                    .defaultAuthenticationEntryPointFor(
                            entryPoint,
                            new AntPathRequestMatcher("/api/**")));

        return http.build();
    }

    /**
     * 自定义 OAuth2AuthorizationRequestResolver：
     *   - 默认行为不变（client_id / redirect_uri / scope / state 等）
     *   - 把触发请求 query 上的 prompt=none 透传到 IdP authorize URL
     *     （用于 silent SSO：Usermanager「产品服务」图标点击直达本平台，IdP 已有 session 时不弹登录页）
     */
    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(builder -> {
            HttpServletRequest req = currentRequest();
            if (req == null) return;
            String prompt = req.getParameter("prompt");
            if (prompt != null && !prompt.isBlank()) {
                builder.additionalParameters(params -> params.put("prompt", prompt));
            }
        });
        return resolver;
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
    }
}
