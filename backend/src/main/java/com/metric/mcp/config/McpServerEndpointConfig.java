package com.metric.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.mcp.service.AlarmMcpTools;
import com.metric.mcp.service.GongdanMcpTools;
import com.metric.mcp.service.IssueMcpTools;
import com.metric.mcp.service.McpServers;
import com.metric.mcp.support.McpToolBuilder;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 三个 MCP 服务的装配：一个服务 = 一个 Streamable HTTP Servlet + 一个 McpSyncServer。
 *
 * 关键点（都来自 2026-11-19 的技术验证）：
 *   1. 用 ServletRegistrationBean 精确挂载 /mcp/xxx，Servlet 精确匹配优先于 "/"，
 *      不会与现有 DispatcherServlet / @RestController 抢路由；
 *   2. keepAliveInterval 必须显式设置。SDK 默认 30 分钟，而 nginx 的 proxy_read_timeout
 *      默认 300 秒，会先把 SSE 长连接掐断，表现为「客户端连上无消息、随后断开」；
 *   3. 三个 provider 同类型，注入一律用 @Qualifier，避免按类型注入产生歧义；
 *   4. 端点是否对外可用由 McpSecurityConfig 按 mcp_server_config.enabled 判定，
 *      Servlet 始终注册（关闭时由安全链返回 404，不暴露服务存在）。
 */
@Configuration
public class McpServerEndpointConfig {

    public static final String ENDPOINT_ALARM = "/mcp/alarm";
    public static final String ENDPOINT_GONGDAN = "/mcp/gongdan";
    public static final String ENDPOINT_ISSUE = "/mcp/issue";

    /** 服务标识 → 后端端点路径。对外地址还需拼上网关前缀（如 /metric-code）。 */
    public static String endpointOf(String serverKey) {
        return switch (serverKey) {
            case McpServers.ALARM -> ENDPOINT_ALARM;
            case McpServers.GONGDAN -> ENDPOINT_GONGDAN;
            case McpServers.ISSUE -> ENDPOINT_ISSUE;
            default -> throw new IllegalArgumentException("未知的 MCP 服务：" + serverKey);
        };
    }

    /**
     * 心跳间隔：nginx 侧 proxy_read_timeout 配 300s，这里取 20s 留足余量。
     * 可用环境变量 MCP_KEEP_ALIVE_SECONDS 覆盖。
     */
    @Value("${mcp.keep-alive-seconds:20}")
    private long keepAliveSeconds;

    /**
     * MCP 协议序列化用自己的 ObjectMapper，不复用 Spring 的业务 ObjectMapper：
     * 业务侧配置（JavaTime、命名策略等）会改变协议字段形态，容易和客户端不兼容。
     */
    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(new ObjectMapper());
    }

    // ================================================================ 告警

    @Bean
    public HttpServletStreamableServerTransportProvider alarmTransport(McpJsonMapper mcpJsonMapper) {
        return transport(ENDPOINT_ALARM, mcpJsonMapper);
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> alarmMcpServlet(
            @Qualifier("alarmTransport") HttpServletStreamableServerTransportProvider transport) {
        return registration("mcpAlarmServlet", ENDPOINT_ALARM, transport);
    }

    @Bean
    public McpSyncServer alarmMcpServer(@Qualifier("alarmTransport") HttpServletStreamableServerTransportProvider transport,
                                        McpJsonMapper mcpJsonMapper,
                                        AlarmMcpTools tools) {
        return server(McpServers.ALARM, transport, mcpJsonMapper, tools.specifications());
    }

    // ================================================================ 工单

    @Bean
    public HttpServletStreamableServerTransportProvider gongdanTransport(McpJsonMapper mcpJsonMapper) {
        return transport(ENDPOINT_GONGDAN, mcpJsonMapper);
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> gongdanMcpServlet(
            @Qualifier("gongdanTransport") HttpServletStreamableServerTransportProvider transport) {
        return registration("mcpGongdanServlet", ENDPOINT_GONGDAN, transport);
    }

    @Bean
    public McpSyncServer gongdanMcpServer(@Qualifier("gongdanTransport") HttpServletStreamableServerTransportProvider transport,
                                          McpJsonMapper mcpJsonMapper,
                                          GongdanMcpTools tools) {
        return server(McpServers.GONGDAN, transport, mcpJsonMapper, tools.specifications());
    }

    // ================================================================ 问题

    @Bean
    public HttpServletStreamableServerTransportProvider issueTransport(McpJsonMapper mcpJsonMapper) {
        return transport(ENDPOINT_ISSUE, mcpJsonMapper);
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> issueMcpServlet(
            @Qualifier("issueTransport") HttpServletStreamableServerTransportProvider transport) {
        return registration("mcpIssueServlet", ENDPOINT_ISSUE, transport);
    }

    @Bean
    public McpSyncServer issueMcpServer(@Qualifier("issueTransport") HttpServletStreamableServerTransportProvider transport,
                                        McpJsonMapper mcpJsonMapper,
                                        IssueMcpTools tools) {
        return server(McpServers.ISSUE, transport, mcpJsonMapper, tools.specifications());
    }

    // ================================================================ 共用构造

    private HttpServletStreamableServerTransportProvider transport(String endpoint, McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(endpoint)
                .keepAliveInterval(Duration.ofSeconds(Math.max(5, keepAliveSeconds)))
                // 把安全链解析出的机器身份与来源 IP 带进 transport context，
                // 工具 handler 通过 exchange.transportContext() 取用（handler 签名里没有 HttpServletRequest）
                .contextExtractor(request -> {
                    java.util.Map<String, Object> context = new java.util.HashMap<>();
                    Object principal = request.getAttribute(McpToolBuilder.CTX_PRINCIPAL);
                    if (principal != null) {
                        context.put(McpToolBuilder.CTX_PRINCIPAL, principal);
                    }
                    Object clientIp = request.getAttribute(McpToolBuilder.CTX_CLIENT_IP);
                    if (clientIp != null) {
                        context.put(McpToolBuilder.CTX_CLIENT_IP, clientIp);
                    }
                    return io.modelcontextprotocol.common.McpTransportContext.create(context);
                })
                .build();
    }

    private ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration(
            String name, String endpoint, HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, endpoint);
        registration.setName(name);
        registration.setAsyncSupported(true);
        return registration;
    }

    private McpSyncServer server(String serverKey,
                                 HttpServletStreamableServerTransportProvider transport,
                                 McpJsonMapper jsonMapper,
                                 List<McpServerFeatures.SyncToolSpecification> specs) {
        return McpServer.sync(transport)
                .jsonMapper(jsonMapper)
                .serverInfo("metric-code-" + serverKey, "1.0.0")
                .instructions(McpToolBuilder.instructions(serverKey))
                .tools(specs)
                .build();
    }
}
