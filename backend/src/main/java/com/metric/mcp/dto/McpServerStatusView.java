package com.metric.mcp.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「MCP 管理」页面的服务卡片数据。
 */
public record McpServerStatusView(
        String serverKey,
        String displayName,
        String description,
        String endpointPath,
        String publicUrl,
        boolean enabled,
        int toolCount,
        List<String> toolNames,
        long callCount7d,
        LocalDateTime lastCallTime
) {
}
