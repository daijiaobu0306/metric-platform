package com.metric.collect.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.metric.collect.entity.PluginAuthentication;
import com.metric.shared.dto.PluginHeaderConfig;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件认证 token 内存缓存。
 *
 * 设计：
 *   - key = pluginAuthenticationId（同一份认证配置共享同一 token）
 *   - value = CachedToken{token, expiresAt}
 *   - 命中：expiresAt > now() → 返回缓存；否则重新认证
 *   - ttlSeconds=0 表示禁用缓存，每次都重新认证
 *   - 失败不写入缓存（下次调用会再尝试）
 *   - 提供 clear(id) / clearAll() 给 admin 调试用
 */
@Component
public class PluginTokenCache {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, CachedToken> cache = new ConcurrentHashMap<>();

    public Map<String, Object> getStatus(Long authId) {
        CachedToken cached = cache.get(authId);
        Map<String, Object> result = new HashMap<>();
        result.put("hasToken", cached != null);
        result.put("token", cached == null ? null : mask(cached.token));
        result.put("obtainedAt", cached == null ? null : cached.obtainedAt);
        result.put("expiresAt", cached == null ? null : cached.expiresAt);
        return result;
    }

    private String mask(String token) {
        if (token == null || token.length() <= 8) return "********";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    /**
     * 获取 token（命中缓存则直接返回；否则调认证接口）。
     * 抛出异常表示认证失败。
     */
    public synchronized String getOrFetch(PluginAuthentication auth) throws Exception {
        if (auth.getEnabled() == null || auth.getEnabled() != 1) {
            throw new IllegalStateException("认证配置已被禁用: " + auth.getName());
        }

        int ttl = auth.getTtlSeconds() == null ? 3600 : auth.getTtlSeconds();

        // ttl > 0 时才走缓存
        if (ttl > 0) {
            CachedToken cached = cache.get(auth.getId());
            if (cached != null && cached.expiresAt.isAfter(LocalDateTime.now())) {
                return cached.token;
            }
        }

        // 调用认证接口
        String token = fetchToken(auth);
        if (ttl > 0) {
        cache.put(auth.getId(), new CachedToken(token, LocalDateTime.now(), LocalDateTime.now().plusSeconds(ttl)));
        }
        return token;
    }

    /**
     * 真实调用认证接口（不读缓存），用于前端"测试认证"按钮。
     */
    public FetchResult fetchTokenForTest(PluginAuthentication auth) throws Exception {
        if (auth.getEnabled() == null || auth.getEnabled() != 1) {
            throw new IllegalStateException("认证配置已被禁用: " + auth.getName());
        }
        String rawResponse = doHttpCall(auth, null);
        Object value = JsonPath.read(rawResponse, auth.getTokenPath());
        if (value == null) {
            throw new IllegalStateException("无法从响应中按路径 " + auth.getTokenPath() + " 提取 token");
        }
        return new FetchResult(value.toString(), rawResponse);
    }

    private String fetchToken(PluginAuthentication auth) throws Exception {
        String rawResponse = doHttpCall(auth, null);
        Object value;
        try {
            value = JsonPath.read(rawResponse, auth.getTokenPath());
        } catch (Exception e) {
            throw new IllegalStateException(
                "认证失败：无法按路径 [" + auth.getTokenPath() + "] 提取 token。响应原文: "
                + truncate(rawResponse, 200));
        }
        if (value == null) {
            throw new IllegalStateException(
                "认证失败：路径 [" + auth.getTokenPath() + "] 取值为 null。响应原文: "
                + truncate(rawResponse, 200));
        }
        return value.toString();
    }

    /**
     * 复用 PluginExecutorService 的变量替换能力需要 dataSource。
     * 这里简化：loginBody / loginHeaders 中的 ${var} 由调用方在 dataSource 注入后再传过来。
     * 但当前阶段调用方传的 dataSource 通常为 null 或简单 map，因此做一个轻量替换：
     *   - 支持 ${auth.user} / ${auth.pwd} 这类内置变量（从 loginBody 中静态识别）
     *   - 若调用方传 dataSource，先用它做 replaceVariables
     */
    private String doHttpCall(PluginAuthentication auth, Map<String, Object> dataSource) throws Exception {
        // 1. headers
        HttpHeaders httpHeaders = new HttpHeaders();
        if (auth.getLoginContentType() != null) {
            try {
                httpHeaders.setContentType(MediaType.parseMediaType(auth.getLoginContentType()));
            } catch (Exception ignore) {
                httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            }
        } else {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        }
        if (auth.getLoginHeaders() != null && !auth.getLoginHeaders().isEmpty()) {
            try {
                List<PluginHeaderConfig> headerList = objectMapper.readValue(
                        auth.getLoginHeaders(),
                        new TypeReference<List<PluginHeaderConfig>>() {});
                for (PluginHeaderConfig h : headerList) {
                    String value = h.getValue();
                    if (dataSource != null) {
                        value = replaceSimple(value, dataSource);
                    }
                    if (value != null) {
                        httpHeaders.set(h.getName(), value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("login_headers 解析失败: " + e.getMessage());
            }
        }

        // 2. body
        String body = auth.getLoginBody();
        if (body != null && dataSource != null) {
            body = replaceSimple(body, dataSource);
        }

        HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(auth.getLoginMethod() == null ? "POST" : auth.getLoginMethod().toUpperCase());
        } catch (Exception e) {
            method = HttpMethod.POST;
        }

        ResponseEntity<String> response = restTemplate.exchange(
                auth.getLoginUrl(), method, entity, String.class);
        return response.getBody();
    }

    private String replaceSimple(String template, Map<String, Object> dataSource) {
        if (template == null || !template.contains("${")) return template;
        String result = template;
        int depth = 0;
        boolean replaced = true;
        while (replaced && depth < 10) {
            replaced = false;
            int start = result.indexOf("${");
            while (start != -1) {
                int end = result.indexOf("}", start);
                if (end == -1) break;
                String variable = result.substring(start + 2, end);
                Object value = dataSource.get(variable);
                if (value != null) {
                    result = result.substring(0, start) + value + result.substring(end + 1);
                    replaced = true;
                } else {
                    start = result.indexOf("${", start + 1);
                }
            }
            depth++;
        }
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public void clear(Long authId) {
        cache.remove(authId);
    }

    public void clearAll() {
        cache.clear();
    }

    private static class CachedToken {
        final String token;
        final LocalDateTime expiresAt;

        final LocalDateTime obtainedAt;

        CachedToken(String token, LocalDateTime obtainedAt, LocalDateTime expiresAt) {
            this.token = token;
            this.obtainedAt = obtainedAt;
            this.expiresAt = expiresAt;
        }
    }

    public static class FetchResult {
        public final String token;
        public final String rawResponse;

        public FetchResult(String token, String rawResponse) {
            this.token = token;
            this.rawResponse = rawResponse;
        }
    }
}
