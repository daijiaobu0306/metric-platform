package com.metric.mcp.controller;

import com.metric.mcp.config.McpServerEndpointConfig;
import com.metric.mcp.dto.McpServerStatusView;
import com.metric.mcp.dto.McpTokenView;
import com.metric.mcp.entity.McpCallLog;
import com.metric.mcp.entity.McpServerConfigEntity;
import com.metric.mcp.repository.McpCallLogRepository;
import com.metric.mcp.service.*;
import com.metric.security.entity.SysUser;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「技能管理 → MCP 管理」页签的后端接口。
 *
 * 只服务平台登录用户（走 X-Session-Key），与 MCP 端点的机器身份鉴权是两套。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpAdminController {

    private final McpConfigService configService;
    private final McpTokenService tokenService;
    private final McpCallLogService callLogService;
    private final McpCallLogRepository callLogRepository;
    private final McpPackageService packageService;
    private final CurrentUserProvider users;
    private final AlarmMcpTools alarmTools;
    private final GongdanMcpTools gongdanTools;
    private final IssueMcpTools issueTools;

    /** 工具清单是静态的，构造一次缓存住，避免每次刷页面都重建 schema。 */
    private final Map<String, List<String>> toolNames = new ConcurrentHashMap<>();

    /** 对外访问基地址（含网关前缀）。未配置时按请求推断，适配内网直连与 nginx 反代两种情况。 */
    @Value("${MCP_PUBLIC_BASE_URL:}")
    private String configuredBaseUrl;

    public McpAdminController(McpConfigService configService,
                              McpTokenService tokenService,
                              McpCallLogService callLogService,
                              McpCallLogRepository callLogRepository,
                              McpPackageService packageService,
                              CurrentUserProvider users,
                              AlarmMcpTools alarmTools,
                              GongdanMcpTools gongdanTools,
                              IssueMcpTools issueTools) {
        this.configService = configService;
        this.tokenService = tokenService;
        this.callLogService = callLogService;
        this.callLogRepository = callLogRepository;
        this.packageService = packageService;
        this.users = users;
        this.alarmTools = alarmTools;
        this.gongdanTools = gongdanTools;
        this.issueTools = issueTools;
    }

    // ================================================================ 服务状态与开关

    @GetMapping("/servers")
    public ApiResponse<List<McpServerStatusView>> servers(HttpServletRequest request) {
        String baseUrl = resolveBaseUrl(request);
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        Map<String, LocalDateTime> lastCalls = callLogService.lastCallTimes();

        List<McpServerStatusView> result = new ArrayList<>();
        for (McpServerConfigEntity entity : configService.listAll()) {
            String key = entity.getServerKey();
            List<String> names = toolNamesOf(key);
            result.add(new McpServerStatusView(
                    key,
                    entity.getDisplayName(),
                    entity.getDescription(),
                    entity.getEndpointPath(),
                    baseUrl + McpServerEndpointConfig.endpointOf(key),
                    entity.getEnabled() != null && entity.getEnabled() == 1,
                    names.size(),
                    names,
                    callLogRepository.countByServerKeyAndCreatedAtAfter(key, since),
                    lastCalls.get(key)));
        }
        return ApiResponse.success(result);
    }

    @PutMapping("/servers/{serverKey}/enabled")
    public ApiResponse<Map<String, Object>> toggle(@PathVariable String serverKey,
                                                   @RequestBody Map<String, Object> body) {
        if (!isAdmin()) {
            return ApiResponse.error("仅管理员可开启或关闭 MCP 服务");
        }
        if (!McpServers.isValid(serverKey)) {
            return ApiResponse.error("未知的 MCP 服务：" + serverKey);
        }
        Object raw = body == null ? null : body.get("enabled");
        boolean enabled = Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw));
        McpServerConfigEntity entity = configService.setEnabled(serverKey, enabled, users.operatorName());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverKey", entity.getServerKey());
        data.put("enabled", entity.getEnabled() != null && entity.getEnabled() == 1);
        data.put("updatedBy", entity.getUpdatedBy());
        return ApiResponse.success(data);
    }

    // ================================================================ 令牌

    @GetMapping("/tokens")
    public ApiResponse<List<McpTokenView>> tokens() {
        return ApiResponse.success(tokenService.list().stream().map(McpTokenView::of).toList());
    }

    /**
     * 新建令牌。明文只在此响应中出现一次，平台不保存。
     * scope 省略或含 "*" 表示三个服务都可访问。
     */
    @PostMapping("/tokens")
    public ApiResponse<Map<String, Object>> createToken(@RequestBody Map<String, Object> body) {
        if (!isAdmin()) {
            return ApiResponse.error("仅管理员可创建 MCP 访问令牌");
        }
        try {
            String name = body == null ? null : String.valueOf(body.getOrDefault("tokenName", ""));
            Integer expiresInDays = null;
            if (body != null && body.get("expiresInDays") != null
                    && !String.valueOf(body.get("expiresInDays")).isBlank()) {
                expiresInDays = Integer.valueOf(String.valueOf(body.get("expiresInDays")));
            }
            List<String> scope = new ArrayList<>();
            if (body != null && body.get("scope") instanceof List<?> list) {
                list.forEach(item -> scope.add(String.valueOf(item)));
            }
            var issued = tokenService.create(name, scope, expiresInDays, users.operatorName());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", McpTokenView.of(issued.entity()));
            data.put("plaintext", issued.plaintext());
            data.put("warning", "令牌明文只显示这一次，请立即复制或下载接入包");
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/tokens/{id}")
    public ApiResponse<Void> revokeToken(@PathVariable Long id) {
        if (!isAdmin()) {
            return ApiResponse.error("仅管理员可吊销 MCP 访问令牌");
        }
        try {
            tokenService.revoke(id, users.operatorName());
            return ApiResponse.success();
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ================================================================ 接入包

    /**
     * 生成接入包（zip）。需要传明文令牌：库里只有哈希，历史令牌无法再导出。
     * 因此页面只在「刚创建令牌」时提供下载。
     */
    @PostMapping("/package")
    public ResponseEntity<byte[]> downloadPackage(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        if (!isAdmin()) {
            throw new IllegalArgumentException("仅管理员可下载接入包");
        }
        String serverKey = body == null ? null : String.valueOf(body.get("serverKey"));
        String token = body == null ? null : String.valueOf(body.get("token"));
        if (!McpServers.isValid(serverKey)) {
            throw new IllegalArgumentException("未知的 MCP 服务：" + serverKey);
        }
        if (token == null || token.isBlank() || !token.startsWith("mcp_")) {
            throw new IllegalArgumentException("请先创建令牌，并在创建时下载接入包");
        }
        byte[] zip = packageService.buildPackage(serverKey, resolveBaseUrl(request), token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition",
                        "attachment; filename=\"" + packageService.suggestedFileName(serverKey) + "\"")
                .body(zip);
    }

    // ================================================================ 调用日志

    @GetMapping("/logs")
    public ApiResponse<Page<McpCallLog>> logs(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String serverKey) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Page<McpCallLog> result = (serverKey == null || serverKey.isBlank())
                ? callLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                : callLogRepository.findByServerKeyOrderByCreatedAtDesc(serverKey, pageable);
        return ApiResponse.success(result);
    }

    /** 页面顶部概况：7 天调用总量、失败数、有效令牌数。 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        long total = 0;
        for (String key : McpServers.ALL) {
            total += callLogRepository.countByServerKeyAndCreatedAtAfter(key, since);
        }
        long tokens = tokenService.list().stream()
                .filter(item -> item.getRevokedAt() == null
                        && item.getEnabled() != null && item.getEnabled() == 1)
                .count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callCount7d", total);
        data.put("failed7d", callLogRepository.countFailuresSince(since));
        data.put("activeTokens", tokens);
        data.put("enabledServers", McpServers.ALL.stream().filter(configService::isEnabled).count());
        return ApiResponse.success(data);
    }

    // ================================================================ 内部辅助

    private List<String> toolNamesOf(String serverKey) {        return toolNames.computeIfAbsent(serverKey, key -> switch (key) {
            case McpServers.ALARM -> alarmTools.specifications().stream()
                    .map(spec -> spec.tool().name()).toList();
            case McpServers.GONGDAN -> gongdanTools.specifications().stream()
                    .map(spec -> spec.tool().name()).toList();
            case McpServers.ISSUE -> issueTools.specifications().stream()
                    .map(spec -> spec.tool().name()).toList();
            default -> List.of();
        });
    }

    /**
     * 对外基地址：优先用 MCP_PUBLIC_BASE_URL；否则按请求推断。
     *
     * 关键判断：只有经过 nginx 反代时才需要补 /metric-code 前缀
     * （nginx 的 location 是 /metric-code/mcp/ → backend:18080/mcp/）。
     * 直连后端（本地开发）没有该前缀，补上反而会 404。
     */
    private String resolveBaseUrl(HttpServletRequest request) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return stripTrailingSlash(configuredBaseUrl.trim());
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");
        boolean behindProxy = (proto != null && !proto.isBlank())
                || (forwardedFor != null && !forwardedFor.isBlank())
                || (realIp != null && !realIp.isBlank());
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        } else {
            // nginx 的 $host 不带端口；内网常用非标端口（如 8080），需要补回来
            String port = request.getHeader("X-Forwarded-Port");
            if (port != null && !port.isBlank()
                    && !"80".equals(port) && !"443".equals(port)
                    && host.indexOf(':') < 0) {
                host = host + ":" + port;
            }
        }
        return proto + "://" + host + (behindProxy ? "/metric-code" : "");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 开开关、发令牌、下接入包都只允许管理员：这三个动作等于对外开数据口子。 */
    private boolean isAdmin() {
        return users.currentUser()
                .map(SysUser::getIsAdmin)
                .map(Integer.valueOf(1)::equals)
                .orElse(false);
    }
}
