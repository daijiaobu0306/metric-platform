package com.metric.mcp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 工具调用审计。只记调用元信息，不记返回正文，避免把业务数据再落一份。
 */
@Data
@Entity
@Table(name = "mcp_call_log")
public class McpCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_key", nullable = false, length = 32)
    private String serverKey;

    @Column(name = "token_id")
    private Long tokenId;

    /** 冗余令牌名：令牌被删后仍能追溯是谁调的。 */
    @Column(name = "token_name", length = 64)
    private String tokenName;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "args_digest", length = 500)
    private String argsDigest;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "success", nullable = false)
    private Integer success = 1;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
