package com.metric.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Separate machine identity: never grants user-management or business API access. */
@Configuration
public class CollectionWorkerSecurityConfig {
    @Bean @Order(1)
    public SecurityFilterChain collectionWorkerChain(HttpSecurity http,
            @Value("${COLLECTION_WORKER_TOKEN:}") String token) throws Exception {
        var filter=new OncePerRequestFilter() {
            protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                    throws ServletException,IOException {
                String supplied=request.getHeader("Authorization");
                if(token.length()<32 || supplied==null || !supplied.startsWith("Bearer ")
                        || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.substring(7).getBytes(StandardCharsets.UTF_8))) {
                    response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"message\":\"Worker身份认证失败或服务未启用\"}");return;
                }
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "collection-worker",null,List.of(new SimpleGrantedAuthority("ROLE_COLLECTION_WORKER"))));
                try { chain.doFilter(request,response); }
                finally { SecurityContextHolder.clearContext(); }
            }
        };
        return http.securityMatcher("/api/collection/worker/**").csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a.anyRequest().hasRole("COLLECTION_WORKER")).build();
    }
}
