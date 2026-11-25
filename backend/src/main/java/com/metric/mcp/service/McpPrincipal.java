package com.metric.mcp.service;

/**
 * 通过 MCP 鉴权链后携带的机器身份。仅存在于单次请求内，不进入任何业务缓存。
 */
public record McpPrincipal(Long tokenId, String tokenName) {
}
