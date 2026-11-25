package com.metric.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未通过 Spring Security 鉴权时的统一响应。
 *
 *   - 对 /api/** 返回 401 JSON（前端 axios 拦截器捕获后清 sessionStorage + 跳 /login）
 *   - 对其它路径返回 401（浏览器原生处理）
 */
@Component
public class SessionKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            ApiResponse<Void> body = ApiResponse.error(401, "未登录或会话已过期");
            objectMapper.writeValue(response.getWriter(), body);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}