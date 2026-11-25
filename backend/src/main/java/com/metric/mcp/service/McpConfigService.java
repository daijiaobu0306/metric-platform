package com.metric.mcp.service;

import com.metric.mcp.entity.McpServerConfigEntity;
import com.metric.mcp.repository.McpServerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务开关。
 *
 * 鉴权链每个请求都要判断开关，所以加一层 5 秒短缓存：
 * 开关变更时主动失效，保证「点完立即生效」，同时避免每个 MCP 请求都打一次库。
 */
@Service
public class McpConfigService {

    private static final Logger log = LoggerFactory.getLogger(McpConfigService.class);
    /** 缓存有效期：开关变更会主动清空，这里的 TTL 只用于兜底多实例场景。 */
    private static final long CACHE_TTL_MS = 5_000L;

    private final McpServerConfigRepository repository;

    private volatile Map<String, Boolean> enabledCache;
    private volatile long cacheAt = 0L;

    public McpConfigService(McpServerConfigRepository repository) {
        this.repository = repository;
    }

    public List<McpServerConfigEntity> listAll() {
        return repository.findAllByOrderByIdAsc();
    }

    public McpServerConfigEntity getByKey(String serverKey) {
        return repository.findByServerKey(serverKey).orElse(null);
    }

    public boolean isEnabled(String serverKey) {
        if (serverKey == null) {
            return false;
        }
        Map<String, Boolean> snapshot = enabledCache;
        if (snapshot == null || System.currentTimeMillis() - cacheAt > CACHE_TTL_MS) {
            snapshot = reloadCache();
        }
        return Boolean.TRUE.equals(snapshot.get(serverKey));
    }

    @Transactional
    public McpServerConfigEntity setEnabled(String serverKey, boolean enabled, String operator) {
        McpServerConfigEntity entity = repository.findByServerKey(serverKey)
                .orElseThrow(() -> new IllegalArgumentException("未知的 MCP 服务：" + serverKey));
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedBy(operator);
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        invalidateCache();
        log.info("MCP 服务开关变更：{} → {}（操作人 {}）", serverKey, enabled ? "开启" : "关闭", operator);
        return entity;
    }

    /** 供部署/排查时确认当前生效状态。 */
    public Map<String, Boolean> snapshot() {
        return new HashMap<>(reloadCache());
    }

    private Map<String, Boolean> reloadCache() {
        Map<String, Boolean> map = new HashMap<>();
        for (McpServerConfigEntity entity : repository.findAllByOrderByIdAsc()) {
            map.put(entity.getServerKey(), entity.getEnabled() != null && entity.getEnabled() == 1);
        }
        enabledCache = map;
        cacheAt = System.currentTimeMillis();
        return map;
    }

    private void invalidateCache() {
        enabledCache = null;
        cacheAt = 0L;
    }
}
