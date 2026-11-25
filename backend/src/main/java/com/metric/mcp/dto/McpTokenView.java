package com.metric.mcp.dto;

import com.metric.mcp.entity.McpAccessToken;

import java.time.LocalDateTime;

/**
 * 令牌视图：不含明文与哈希，只给展示前缀。
 */
public record McpTokenView(
        Long id,
        String tokenName,
        String tokenPrefix,
        String serverScope,
        boolean enabled,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        Long callCount,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String statusText
) {

    public static McpTokenView of(McpAccessToken token) {
        String status;
        if (token.getRevokedAt() != null) {
            status = "已吊销";
        } else if (token.getEnabled() == null || token.getEnabled() != 1) {
            status = "已停用";
        } else if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            status = "已过期";
        } else {
            status = "有效";
        }
        return new McpTokenView(
                token.getId(),
                token.getTokenName(),
                token.getTokenPrefix(),
                token.getServerScope(),
                token.getEnabled() != null && token.getEnabled() == 1,
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.getCallCount(),
                token.getCreatedBy(),
                token.getCreatedAt(),
                token.getRevokedAt(),
                status);
    }
}
