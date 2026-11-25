package com.metric.mcp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 访问令牌（机器身份，类比大模型 API Key）。
 *
 * 库里只存 SHA-256，明文只在创建时返回一次；吊销后立即可用性由鉴权链每请求查库保证。
 */
@Data
@Entity
@Table(name = "mcp_access_token")
public class McpAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_name", nullable = false, length = 64)
    private String tokenName;

    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** "*" 或逗号分隔的 serverKey 集合（alarm,gongdan,issue）。 */
    @Column(name = "server_scope", nullable = false, length = 128)
    private String serverScope = "*";

    @Column(name = "enabled", nullable = false)
    private Integer enabled = 1;

    /** NULL = 永久有效（用户明确要求「给别人永久使用」）。 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "call_count", nullable = false)
    private Long callCount = 0L;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
