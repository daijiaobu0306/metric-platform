package com.metric.mcp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.mcp.service.McpCallLogService;
import com.metric.mcp.service.McpPrincipal;
import com.metric.mcp.service.McpTokenService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP 工具的统一外壳：参数解析、时间窗口与分页收敛、JSON 输出、调用审计。
 *
 * 收敛口径（需求明确要求「数据收敛」）：
 *   - 分页：默认 20、上限 50，超限直接报错让调用方收窄条件，不静默截断；
 *   - 时间：默认近 7 天、上限 180 天；
 *   - 工具只做只读查询，任何写操作都不在此层开放。
 */
@Component
public class McpToolSupport {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpToolSupport.class);

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_DAYS = 7;
    public static final int MAX_DAYS = 180;

    private final ObjectMapper objectMapper;
    private final McpCallLogService callLogService;
    private final McpTokenService tokenService;

    public McpToolSupport(ObjectMapper objectMapper,
                          McpCallLogService callLogService,
                          McpTokenService tokenService) {
        this.objectMapper = objectMapper;
        this.callLogService = callLogService;
        this.tokenService = tokenService;
    }

    // ------------------------------------------------------------------ 调用外壳

    public McpSchema.CallToolResult invoke(String serverKey,
                                           String toolName,
                                           McpPrincipal principal,
                                           String clientIp,
                                           Map<String, Object> args,
                                           Supplier<ToolOutcome> body) {
        long start = System.currentTimeMillis();
        try {
            ToolOutcome outcome = body.get();
            long cost = System.currentTimeMillis() - start;
            audit(serverKey, principal, toolName, args, outcome.count(), cost, null, clientIp);
            return ok(outcome);
        } catch (IllegalArgumentException e) {
            // 参数类错误：按 MCP 约定返回 isError=true 的正常结果，让模型自己纠正参数
            long cost = System.currentTimeMillis() - start;
            audit(serverKey, principal, toolName, args, null, cost, e, clientIp);
            return error(e.getMessage());
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            audit(serverKey, principal, toolName, args, null, cost, e, clientIp);
            // 工具异常必须留堆栈：MCP 客户端只会看到一句话，排障得靠这里
            log.error("MCP 工具执行失败：server={} tool={}", serverKey, toolName, e);
            return error("查询失败：" + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void audit(String serverKey, McpPrincipal principal, String toolName, Map<String, Object> args,
                       Integer count, long cost, Throwable error, String clientIp) {
        callLogService.recordAsync(serverKey, principal, toolName, digest(args), count, cost, error, clientIp);
        if (principal != null) {
            tokenService.touchAsync(principal.tokenId());
        }
    }

    private McpSchema.CallToolResult ok(ToolOutcome outcome) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json(outcome.payload()))))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult error(String message) {
        String payload = "{\"error\":\"" + escape(message == null ? "未知错误" : message) + "\"}";
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(payload)))
                .isError(true)
                .build();
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("结果序列化失败", e);
        }
    }

    private String digest(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    // ------------------------------------------------------------------ 参数读取

    public static String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static Integer intVal(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 需为整数，实际为：" + text);
        }
    }

    public static Long longVal(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 需为整数，实际为：" + text);
        }
    }

    /** 分页：limit 默认 20、上限 50；offset 默认 0。超限报错而不是静默截断。 */
    public static Page window(Map<String, Object> args) {
        Integer limit = intVal(args, "limit");
        Integer offset = intVal(args, "offset");
        int safeLimit = limit == null ? DEFAULT_LIMIT : limit;
        int safeOffset = offset == null ? 0 : offset;
        if (safeLimit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        if (safeLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 上限为 " + MAX_LIMIT + "，请缩小范围或分页查询");
        }
        if (safeOffset < 0) {
            throw new IllegalArgumentException("offset 不能为负数");
        }
        return new Page(safeLimit, safeOffset);
    }

    /** 时间窗口：days 默认 7、上限 180。 */
    public static TimeWindow timeWindow(Map<String, Object> args) {
        Integer days = intVal(args, "days");
        int safeDays = days == null ? DEFAULT_DAYS : days;
        if (safeDays <= 0) {
            throw new IllegalArgumentException("days 必须大于 0");
        }
        if (safeDays > MAX_DAYS) {
            throw new IllegalArgumentException("days 上限为 " + MAX_DAYS + " 天");
        }
        LocalDateTime end = LocalDateTime.now();
        return new TimeWindow(end.minusDays(safeDays), end, safeDays);
    }

    public record Page(int limit, int offset) {
    }

    /**
     * 时间窗口。
     *
     * 用 java.sql.Timestamp 而不是 LocalDateTime 作为查询参数：
     * Hibernate 6 对原生查询的命名参数做类型推断时，LocalDateTime 会触发
     * 「Cannot invoke Object.hashCode() because pk is null」，换成 JDBC 原生时间类型即可。
     */
    public record TimeWindow(LocalDateTime start, LocalDateTime end, int days) {
        public java.sql.Timestamp startParam() {
            return java.sql.Timestamp.valueOf(start);
        }

        public java.sql.Timestamp endParam() {
            return java.sql.Timestamp.valueOf(end);
        }
    }

    /** 工具返回：payload 为已收敛的对象，count 用于审计与「是否还有更多」判断。 */
    public record ToolOutcome(Object payload, int count) {
    }
}
