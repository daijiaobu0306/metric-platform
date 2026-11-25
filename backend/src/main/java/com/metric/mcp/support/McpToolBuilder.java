package com.metric.mcp.support;

import com.metric.mcp.service.McpPrincipal;
import com.metric.mcp.service.McpServers;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 把「业务查询」包装成 MCP 工具定义：统一注入身份、审计与错误处理。
 *
 * 身份与客户端 IP 由安全链写入 request attribute，再经 transport context
 * 传到工具 handler（MCP handler 签名里没有 HttpServletRequest）。
 */
@Component
public class McpToolBuilder {

    public static final String CTX_PRINCIPAL = "mcpPrincipal";
    public static final String CTX_CLIENT_IP = "mcpClientIp";

    private final McpToolSupport support;

    public McpToolBuilder(McpToolSupport support) {
        this.support = support;
    }

    public McpServerFeatures.SyncToolSpecification tool(String serverKey,
                                                        String name,
                                                        String title,
                                                        String description,
                                                        Map<String, Object> properties,
                                                        List<String> required,
                                                        BiFunction<McpSyncServerExchange, Map<String, Object>, McpToolSupport.ToolOutcome> body) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(new McpSchema.JsonSchema("object", properties, required, false, null, null))
                .build();

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            McpPrincipal principal = principalOf(exchange);
            String clientIp = clientIpOf(exchange);
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            return support.invoke(serverKey, name, principal, clientIp, args, () -> body.apply(exchange, args));
        });
    }

    private McpPrincipal principalOf(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext() == null ? null
                : exchange.transportContext().get(CTX_PRINCIPAL);
        return value instanceof McpPrincipal principal ? principal : null;
    }

    private String clientIpOf(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext() == null ? null
                : exchange.transportContext().get(CTX_CLIENT_IP);
        return value == null ? null : String.valueOf(value);
    }

    // ------------------------------------------------------------------ schema 构造辅助

    public static Map<String, Object> prop(String type, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("description", description);
        return map;
    }

    public static Map<String, Object> enumProp(String description, List<String> values) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "string");
        map.put("description", description + "，可选值：" + String.join(" / ", values));
        map.put("enum", values);
        return map;
    }

    /** 便于按顺序构造 properties（HashMap 顺序不稳定，会影响模型对参数的阅读顺序）。 */
    public static Map<String, Object> props(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    public static List<String> required(String... names) {
        return new ArrayList<>(List.of(names));
    }

    /** 统一的 serverInfo 描述，避免三个服务各写一套。 */
    public static String instructions(String serverKey) {
        return switch (serverKey) {
            case McpServers.ALARM -> "指标体系平台·告警只读服务。可查询告警记录、告警对象与告警维度统计。"
                    + "建议先用 alarm_list_systems / alarm_list_categories 把系统名、大类名解析成 ID，再查明细。所有数据只读，无法修改。";
            case McpServers.GONGDAN -> "指标体系平台·工单只读服务。可查询工单记录、BOMC 推送状态与处理环节。"
                    + "建议先用 gongdan_search 按标题或对象关键词定位，再用 gongdan_get 看单条详情。所有数据只读，无法修改。";
            case McpServers.ISSUE -> "指标体系平台·问题只读服务。可查询问题登记、跟踪与解决情况。"
                    + "建议先用 issue_search 按关键词或状态筛选，再用 issue_get 看单条详情。所有数据只读，无法修改。";
            default -> "指标体系平台只读 MCP 服务";
        };
    }
}
