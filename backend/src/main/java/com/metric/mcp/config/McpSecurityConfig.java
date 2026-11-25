package com.metric.mcp.config;

import com.metric.mcp.service.McpConfigService;
import com.metric.mcp.service.McpPrincipal;
import com.metric.mcp.service.McpServers;
import com.metric.mcp.service.McpTokenService;
import com.metric.mcp.support.McpToolBuilder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * MCP 端点的独立机器身份认证链。
 *
 * 与平台登录态（X-Session-Key）完全分离：MCP 客户端是无人值守的机器身份，
 * 用 Bearer Token，不依赖会话空闲超时、也不绑浏览器 origin。
 *
 * 判定顺序（刻意如此）：
 *   1. 先验令牌 —— 未授权方无论服务开没开，都只看到 401，探测不到端点是否存在；
 *   2. 再验开关 —— 令牌有效但服务已关闭，返回 404（不暴露「这里有个 MCP」）。
 */
@Configuration
public class McpSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain mcpSecurityChain(HttpSecurity http,
                                                McpTokenService tokenService,
                                                McpConfigService configService) throws Exception {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                // 浏览器类客户端（MCP Inspector）会先发预检
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    response.setHeader("Access-Control-Allow-Origin", "*");
                    response.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
                    response.setHeader("Access-Control-Allow-Headers",
                            "Authorization, Content-Type, Accept, Mcp-Session-Id, Last-Event-ID, MCP-Protocol-Version");
                    response.setHeader("Access-Control-Expose-Headers", "Mcp-Session-Id");
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                String serverKey = McpServers.fromPath(request.getRequestURI());
                if (serverKey == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }

                String authorization = request.getHeader("Authorization");
                String bearer = authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring(7).trim() : null;
                McpPrincipal principal = tokenService.authenticate(bearer, serverKey);
                if (principal == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate", "Bearer realm=\"metric-code-mcp\"");
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,\"message\":\"MCP token 缺失或无效\"}}");
                    return;
                }

                if (!configService.isEnabled(serverKey)) {
                    // 关闭时按「不存在」处理，不暴露服务
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }

                request.setAttribute(McpToolBuilder.CTX_PRINCIPAL, principal);
                request.setAttribute(McpToolBuilder.CTX_CLIENT_IP, clientIp(request));

                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "mcp:" + principal.tokenName(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_MCP_CLIENT"))));
                try {
                    chain.doFilter(request, response);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        };

        return http
                .securityMatcher("/mcp/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("MCP_CLIENT"))
                .build();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
