package com.metric.collect.controller;

import com.metric.collect.entity.PluginAuthentication;
import com.metric.collect.service.PluginAuthenticationService;
import com.metric.collect.service.PluginTokenCache;
import com.metric.shared.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plugin-auth")
public class PluginAuthenticationController {

    @Autowired
    private PluginAuthenticationService service;

    @Autowired
    private PluginTokenCache tokenCache;

    @GetMapping
    public ApiResponse<Page<PluginAuthentication>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.success(service.search(name, pageable));
    }

    @GetMapping("/list")
    public ApiResponse<List<PluginAuthentication>> listAll() {
        return ApiResponse.success(service.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<PluginAuthentication> findById(@PathVariable Long id) {
        return service.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("认证配置不存在"));
    }

    @PostMapping
    public ApiResponse<PluginAuthentication> create(@RequestBody PluginAuthentication auth) {
        applyDefaults(auth);
        return ApiResponse.success(service.save(auth));
    }

    @PutMapping("/{id}")
    public ApiResponse<PluginAuthentication> update(@PathVariable Long id, @RequestBody PluginAuthentication auth) {
        return service.findById(id).map(existing -> {
            auth.setId(id);
            // 清缓存
            tokenCache.clear(id);
            applyDefaults(auth);
            return ApiResponse.success(service.save(auth));
        }).orElse(ApiResponse.error("认证配置不存在"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tokenCache.clear(id);
        service.deleteById(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id, HttpServletRequest request) {
        PluginAuthentication auth = service.findById(id).orElse(null);
        if (auth == null) {
            return ApiResponse.error("认证配置不存在");
        }
        try {
            // 本平台认证不能再次调用 /api/session/login，否则会让当前浏览器会话失效。
            if (auth.getLoginUrl() != null && auth.getLoginUrl().contains("/api/session/login")) {
                String sessionKey = request.getHeader("X-Session-Key");
                if (sessionKey == null || sessionKey.isBlank()) {
                    return ApiResponse.error("未获取到当前平台会话，请从已登录页面测试");
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("token", "********");
                data.put("rawResponse", "当前平台会话已透传，不重复登录");
                return ApiResponse.success(data);
            }
            PluginTokenCache.FetchResult result = tokenCache.fetchTokenForTest(auth);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", result.token);
            data.put("rawResponse", result.rawResponse);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("认证失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/clear-cache")
    public ApiResponse<Void> clearCache(@PathVariable Long id) {
        tokenCache.clear(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long id, HttpServletRequest request) {
        PluginAuthentication auth = service.findById(id).orElse(null);
        if (auth == null) return ApiResponse.error("认证配置不存在");
        if (auth.getLoginUrl() != null && auth.getLoginUrl().contains("/api/session/login")) {
            String sessionKey = request.getHeader("X-Session-Key");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("hasToken", sessionKey != null && !sessionKey.isBlank());
            result.put("token", mask(sessionKey));
            result.put("obtainedAt", sessionKey == null || sessionKey.isBlank() ? null : java.time.LocalDateTime.now());
            result.put("expiresAt", null);
            return ApiResponse.success(result);
        }
        return ApiResponse.success(tokenCache.getStatus(id));
    }

    private String mask(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.length() <= 8) return "********";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    private void applyDefaults(PluginAuthentication auth) {
        if (auth.getLoginMethod() == null || auth.getLoginMethod().isBlank()) {
            auth.setLoginMethod("POST");
        }
        if (auth.getLoginContentType() == null || auth.getLoginContentType().isBlank()) {
            auth.setLoginContentType("application/json");
        }
        if (auth.getTokenHeaderName() == null || auth.getTokenHeaderName().isBlank()) {
            auth.setTokenHeaderName("Authorization");
        }
        if (auth.getTokenValuePrefix() == null) {
            auth.setTokenValuePrefix("Bearer ");
        }
        if (auth.getTtlSeconds() == null) {
            auth.setTtlSeconds(3600);
        }
        if (auth.getEnabled() == null) {
            auth.setEnabled(1);
        }
    }
}
