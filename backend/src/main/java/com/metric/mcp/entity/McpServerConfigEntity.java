package com.metric.mcp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 服务开关与端点配置（一服务一行：alarm / gongdan / issue）。
 *
 * 关闭时端点返回 404，不暴露服务存在；开关状态由「技能管理 → MCP 管理」维护。
 */
@Data
@Entity
@Table(name = "mcp_server_config")
public class McpServerConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_key", nullable = false, length = 32, unique = true)
    private String serverKey;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "endpoint_path", nullable = false, length = 64)
    private String endpointPath;

    /** 1=开启，0=关闭。用 Integer 而非 Boolean，与库里 INT 列对齐（避免 TINYINT/BIT 校验问题）。 */
    @Column(name = "enabled", nullable = false)
    private Integer enabled = 0;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
