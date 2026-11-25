package com.metric.mcp.service;

import com.metric.mcp.entity.McpCallLog;
import com.metric.mcp.repository.McpCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 调用审计。异步写，避免审计拖慢工具响应；写失败只记日志，不影响调用结果。
 */
@Service
public class McpCallLogService {

    private static final Logger log = LoggerFactory.getLogger(McpCallLogService.class);
    /** 参数摘要长度上限，防止把长查询条件整段落库。 */
    private static final int ARGS_DIGEST_MAX = 480;

    private final McpCallLogRepository repository;

    public McpCallLogService(McpCallLogRepository repository) {
        this.repository = repository;
    }

    @Async
    public void recordAsync(String serverKey, McpPrincipal principal, String toolName, String argsDigest,
                            Integer resultCount, long durationMs, Throwable error, String clientIp) {
        try {
            McpCallLog entity = new McpCallLog();
            entity.setServerKey(serverKey);
            if (principal != null) {
                entity.setTokenId(principal.tokenId());
                entity.setTokenName(principal.tokenName());
            }
            entity.setToolName(toolName);
            entity.setArgsDigest(truncate(argsDigest, ARGS_DIGEST_MAX));
            entity.setResultCount(resultCount);
            entity.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            entity.setSuccess(error == null ? 1 : 0);
            entity.setErrorMessage(error == null ? null : truncate(error.getMessage(), 480));
            entity.setClientIp(clientIp);
            entity.setCreatedAt(LocalDateTime.now());
            repository.save(entity);
        } catch (Exception e) {
            log.debug("MCP 调用日志写入失败（忽略）：{}", e.getMessage());
        }
    }

    public Map<String, Object> statistics(String serverKey, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        Map<String, Object> result = new HashMap<>();
        result.put("serverKey", serverKey);
        result.put("days", days);
        result.put("callCount", repository.countByServerKeyAndCreatedAtAfter(serverKey, since));
        result.put("lastCallTime", lastCallTimes().get(serverKey));
        return result;
    }

    public Map<String, LocalDateTime> lastCallTimes() {
        Map<String, LocalDateTime> map = new HashMap<>();
        for (Object[] row : repository.findLastCallTimeByServer()) {
            if (row[0] instanceof String key && row[1] instanceof LocalDateTime time) {
                map.put(key, time);
            }
        }
        return map;
    }

    public List<Object[]> lastCallRows() {
        return repository.findLastCallTimeByServer();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
