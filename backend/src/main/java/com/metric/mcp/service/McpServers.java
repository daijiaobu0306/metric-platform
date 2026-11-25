package com.metric.mcp.service;

import java.util.List;

/**
 * 三个 MCP 服务的固定标识。与 mcp_server_config.server_key、端点路径一一对应。
 */
public final class McpServers {

    public static final String ALARM = "alarm";
    public static final String GONGDAN = "gongdan";
    public static final String ISSUE = "issue";

    public static final List<String> ALL = List.of(ALARM, GONGDAN, ISSUE);

    private McpServers() {
    }

    public static boolean isValid(String key) {
        return ALL.contains(key);
    }

    /** 从请求路径解析服务标识：/mcp/alarm → alarm；无法识别返回 null。 */
    public static String fromPath(String uri) {
        if (uri == null) {
            return null;
        }
        for (String key : ALL) {
            if (uri.endsWith("/mcp/" + key)) {
                return key;
            }
        }
        return null;
    }
}
