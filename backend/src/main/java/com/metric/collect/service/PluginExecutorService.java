package com.metric.collect.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import com.metric.shared.dto.PluginExecutionResult;
import com.metric.shared.dto.PluginHeaderConfig;
import com.metric.shared.dto.PluginParamConfig;
import com.metric.shared.dto.PluginResponseParamConfig;
import com.metric.gongdan.entity.Gongdan;
import com.metric.collect.entity.PluginAuthentication;
import com.metric.collect.entity.PluginConfig;
import com.metric.collect.entity.PluginExecutionLog;
import com.metric.gongdan.repository.GongdanRepository;
import com.metric.collect.repository.PluginAuthenticationRepository;
import com.metric.collect.repository.PluginConfigRepository;
import com.metric.collect.repository.PluginExecutionLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class PluginExecutorService {

    @Autowired
    private PluginConfigRepository pluginConfigRepository;

    @Autowired
    private PluginExecutionLogRepository executionLogRepository;

    @Autowired
    private GongdanRepository gongdanRepository;

    @Autowired
    private PluginAuthenticationRepository authenticationRepository;

    @Autowired
    private PluginTokenCache tokenCache;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_REPLACE_DEPTH = 10;

    public PluginExecutionResult execute(Long pluginId, String businessType, Long businessId,
                                          Map<String, Object> customParams) {
        PluginConfig config = pluginConfigRepository.findById(pluginId)
                .orElse(null);
        if (config == null) {
            return PluginExecutionResult.failure("插件不存在");
        }

        try {
            Map<String, Object> dataSource = buildDataSource(businessType, businessId, customParams);

            // 前置认证：如果插件关联了 auth 配置，先调认证拿 token 注入 dataSource
            if (config.getAuthId() != null) {
                PluginAuthentication auth = authenticationRepository.findById(config.getAuthId()).orElse(null);
                if (auth != null) {
                    // 本平台不能再次调用 /api/session/login：该接口会让同一用户的
                    // 浏览器旧会话失效，调试完成后前端会被 401 拦截并跳回登录页。
                    // 对本平台认证配置直接透传当前请求的 X-Session-Key。
                    String token = isPlatformSessionAuth(auth)
                            ? currentPlatformSessionKey()
                            : tokenCache.getOrFetch(auth);
                    if (token == null || token.isBlank()) {
                        throw new IllegalStateException(isPlatformSessionAuth(auth)
                                ? "未获取到当前平台会话，请从平台页面发起调试"
                                : "动态认证未获取到 token");
                    }
                    // 注入到 dataSource：plugin header 模板里可写 ${auth.token}
                    dataSource.put("auth.token", token);
                    // 同时在 dataSource 下再放一份带前缀的值，方便 plugin header 模板直接用
                    dataSource.put("auth.tokenHeader", auth.getTokenHeaderName() == null ? "Authorization" : auth.getTokenHeaderName());
                    dataSource.put("auth.tokenPrefix", auth.getTokenValuePrefix() == null ? "Bearer " : auth.getTokenValuePrefix());
                }
            }

            Map<String, String> headers = resolveHeaders(config, dataSource, config.getAuthId() != null
                    ? authenticationRepository.findById(config.getAuthId()).orElse(null)
                    : null);
            Map<String, Object> params = resolveParams(config, dataSource);

            String url = replaceVariables(config.getRequestUrl(), dataSource);
            String method = config.getRequestMethod();

            String response = executeHttpRequest(url, method, headers, params);

            Map<String, Object> responseData = parseResponse(config, response);

            saveExecutionLog(config.getId(), businessType, businessId,
                    objectMapper.writeValueAsString(params), response, "SUCCESS", null);

            updatePluginDebugStatus(config.getId(), "SUCCESS", response);

            return PluginExecutionResult.success(responseData, response);
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            saveExecutionLog(config.getId(), businessType, businessId,
                    null, null, "FAILED", errorMsg);
            updatePluginDebugStatus(config.getId(), "FAILED", errorMsg);
            return PluginExecutionResult.failure(errorMsg);
        }
    }

    public PluginExecutionResult debugExecute(Long pluginId, Map<String, Object> testParams) {
        return execute(pluginId, "DEBUG", null, testParams);
    }

    private boolean isPlatformSessionAuth(PluginAuthentication auth) {
        String loginUrl = auth.getLoginUrl();
        return loginUrl != null && loginUrl.contains("/api/session/login");
    }

    private String currentPlatformSessionKey() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getHeader("X-Session-Key");
        }
        return null;
    }

    private Map<String, Object> buildDataSource(String businessType, Long businessId,
                                                   Map<String, Object> customParams) {
        Map<String, Object> dataSource = new HashMap<>();

        if (customParams != null) {
            dataSource.putAll(customParams);
        }

        if ("gongdan".equals(businessType) && businessId != null) {
            Gongdan gongdan = gongdanRepository.findById(businessId).orElse(null);
            if (gongdan != null) {
                dataSource.put("gongdan", gongdan);
            }
        }

        return dataSource;
    }

    private Map<String, String> resolveHeaders(PluginConfig config, Map<String, Object> dataSource,
                                                PluginAuthentication auth)
            throws Exception {
        Map<String, String> headers = new HashMap<>();
        if (config.getRequestHeaders() != null && !config.getRequestHeaders().isEmpty()) {
            JsonNode root = objectMapper.readTree(config.getRequestHeaders());
            if (root.isArray()) {
                List<PluginHeaderConfig> headerList = objectMapper.convertValue(root,
                        new TypeReference<List<PluginHeaderConfig>>() {});
                for (PluginHeaderConfig header : headerList) {
                    String value = replaceVariables(header.getValue(), dataSource);
                    headers.put(header.getName(), value);
                }
            } else if (root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    String value = entry.getValue().isNull() ? "" : entry.getValue().asText();
                    headers.put(entry.getKey(), replaceVariables(value, dataSource));
                });
            } else {
                throw new IllegalArgumentException("请求头必须是 JSON 对象或数组");
            }
        }

        // 自动注入认证 header：如果 plugin 没显式配该 header，则用 auth 配置的 tokenHeaderName + tokenValuePrefix + token
        if (auth != null) {
            String headerName = auth.getTokenHeaderName() == null ? "Authorization" : auth.getTokenHeaderName();
            String prefix = auth.getTokenValuePrefix() == null ? "Bearer " : auth.getTokenValuePrefix();
            String token = dataSource.get("auth.token") == null ? null : dataSource.get("auth.token").toString();
            if (token != null && !headers.containsKey(headerName)) {
                headers.put(headerName, prefix + token);
            }
        }
        return headers;
    }

    private Map<String, Object> resolveParams(PluginConfig config, Map<String, Object> dataSource)
            throws Exception {
        Map<String, Object> params = new HashMap<>();
        if (config.getRequestParams() == null || config.getRequestParams().isEmpty()) {
            return params;
        }

        JsonNode root = objectMapper.readTree(config.getRequestParams());
        if (root.isObject()) {
            root.fields().forEachRemaining(entry -> {
                JsonNode valueNode = entry.getValue();
                Object value = valueNode.isNull() ? null : objectMapper.convertValue(valueNode, Object.class);
                if (value instanceof String) value = replaceVariables((String) value, dataSource);
                if (value != null) params.put(entry.getKey(), value);
            });
            return params;
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("请求参数必须是 JSON 对象或数组");
        }
        List<PluginParamConfig> paramList = objectMapper.convertValue(root,
                new TypeReference<List<PluginParamConfig>>() {});

        for (PluginParamConfig param : paramList) {
            Object value;
            if (param.getSource() != null && !param.getSource().isEmpty()) {
                value = resolveVariableValue(param.getSource(), dataSource);
            } else if (param.getDefaultValue() != null) {
                value = replaceVariables(param.getDefaultValue(), dataSource);
            } else {
                value = null;
            }

            if (value != null) {
                params.put(param.getName(), value);
            }
        }
        return params;
    }

    public String replaceVariables(String template, Map<String, Object> dataSource) {
        if (template == null || !template.contains("${")) {
            return template;
        }

        String result = template;
        int depth = 0;
        boolean replaced = true;

        while (replaced && depth < MAX_REPLACE_DEPTH) {
            replaced = false;
            int start = result.indexOf("${");
            while (start != -1) {
                int end = result.indexOf("}", start);
                if (end == -1) break;

                String variable = result.substring(start + 2, end);
                Object value = resolveVariableValue(variable, dataSource);
                if (value != null) {
                    result = result.substring(0, start) + value.toString() + result.substring(end + 1);
                    replaced = true;
                } else {
                    start = result.indexOf("${", start + 1);
                }
            }
            depth++;
        }
        return result;
    }

    private Object resolveVariableValue(String variable, Map<String, Object> dataSource) {
        if (variable == null || variable.isEmpty()) {
            return null;
        }

        // 认证流程会把 token 以 "auth.token" 作为扁平 key 注入。
        // 必须先按完整 key 查找，否则下面按对象路径解析时会把它当成
        // dataSource.auth.token，导致 ${auth.token} 无法替换。
        if (dataSource.containsKey(variable)) {
            return dataSource.get(variable);
        }

        String[] parts = variable.split("\\.");
        if (parts.length < 2) {
            return dataSource.get(variable);
        }

        String prefix = parts[0];
        String fieldPath = variable.substring(prefix.length() + 1);

        Object obj = dataSource.get(prefix);
        if (obj == null) {
            return null;
        }

        return getNestedValue(obj, fieldPath);
    }

    private Object getNestedValue(Object obj, String fieldPath) {
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            String[] parts = fieldPath.split("\\.");
            Object result = map.get(parts[0]);
            for (int i = 1; i < parts.length && result != null; i++) {
                if (result instanceof Map) {
                    result = ((Map<?, ?>) result).get(parts[i]);
                } else {
                    return null;
                }
            }
            return result;
        }

        try {
            var fieldName = fieldPath.replaceAll("([A-Z])", "_$1").toLowerCase();
            var method = obj.getClass().getMethod("get" + capitalize(fieldPath));
            return method.invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String executeHttpRequest(String url, String method,
                                       Map<String, String> headers, Map<String, Object> params) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            httpHeaders.set(entry.getKey(), entry.getValue());
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(params, httpHeaders);

        ResponseEntity<String> response;
        switch (method.toUpperCase()) {
            case "POST":
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                break;
            case "PUT":
                response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
                break;
            case "DELETE":
                response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
                break;
            default:
                response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        }

        return response.getBody();
    }

    private Map<String, Object> parseResponse(PluginConfig config, String response)
            throws Exception {
        Map<String, Object> result = new HashMap<>();

        if (config.getResponseParams() == null || config.getResponseParams().isEmpty()) {
            result.put("_raw", response);
            return result;
        }

        List<PluginResponseParamConfig> responseParams = objectMapper.readValue(
                config.getResponseParams(),
                new TypeReference<List<PluginResponseParamConfig>>() {});

        for (PluginResponseParamConfig param : responseParams) {
            try {
                Object value = JsonPath.read(response, param.getPath());
                result.put(param.getName(), value);
            } catch (Exception e) {
                result.put(param.getName(), null);
            }
        }

        return result;
    }

    private void saveExecutionLog(Long pluginId, String businessType, Long businessId,
                                  String requestData, String responseData,
                                  String status, String errorMessage) {
        PluginExecutionLog log = new PluginExecutionLog();
        log.setPluginId(pluginId);
        log.setBusinessType(businessType);
        log.setBusinessId(businessId);
        log.setRequestData(requestData);
        log.setResponseData(responseData);
        log.setExecStatus(status);
        log.setErrorMessage(errorMessage);
        log.setExecTime(LocalDateTime.now());
        executionLogRepository.save(log);
    }

    private void updatePluginDebugStatus(Long pluginId, String status, String response) {
        pluginConfigRepository.findById(pluginId).ifPresent(config -> {
            config.setDebugStatus(status);
            config.setDebugResponse(response != null && response.length() > 2000
                    ? response.substring(0, 2000) : response);
            config.setLastDebugTime(LocalDateTime.now());
            pluginConfigRepository.save(config);
        });
    }
}
