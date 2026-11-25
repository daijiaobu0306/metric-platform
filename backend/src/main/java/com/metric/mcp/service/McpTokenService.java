package com.metric.mcp.service;

import com.metric.mcp.entity.McpAccessToken;
import com.metric.mcp.repository.McpAccessTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * MCP 访问令牌：发牌、校验、吊销。
 *
 * 设计：
 *   - 明文形如 mcp_7f3a9c2e... （mcp_ + 32 位 hex），只在创建时返回一次；
 *   - 库里只存 SHA-256，使用常量时间比较，避免时序侧信道；
 *   - 默认永久有效（用户口径：给别人永久使用），仍支持可选有效期；
 *   - 校验每次查库，保证「吊销立即生效」，不做长缓存。
 */
@Service
public class McpTokenService {

    private static final Logger log = LoggerFactory.getLogger(McpTokenService.class);
    private static final String PREFIX = "mcp_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpAccessTokenRepository repository;

    public McpTokenService(McpAccessTokenRepository repository) {
        this.repository = repository;
    }

    /** 创建令牌：返回的明文仅在这一次调用中出现，后续无法再取回。 */
    @Transactional
    public TokenIssueResult create(String tokenName, List<String> scope, Integer expiresInDays, String createdBy) {
        if (tokenName == null || tokenName.isBlank()) {
            throw new IllegalArgumentException("令牌名称不能为空");
        }
        String scopeValue = normalizeScope(scope);

        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String plaintext = PREFIX + HexFormat.of().formatHex(raw);
        String hash = sha256Hex(plaintext);
        String displayPrefix = plaintext.substring(0, 12);

        McpAccessToken entity = new McpAccessToken();
        entity.setTokenName(tokenName.trim());
        entity.setTokenPrefix(displayPrefix);
        entity.setTokenHash(hash);
        entity.setServerScope(scopeValue);
        entity.setEnabled(1);
        entity.setCallCount(0L);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(LocalDateTime.now());
        if (expiresInDays != null && expiresInDays > 0) {
            entity.setExpiresAt(LocalDateTime.now().plusDays(expiresInDays));
        }
        repository.save(entity);

        log.info("MCP 令牌已创建：{}（{}，范围 {}）", entity.getTokenName(), displayPrefix, scopeValue);
        return new TokenIssueResult(entity, plaintext);
    }

    /**
     * 校验 Bearer 令牌。
     *
     * @param bearerToken Authorization: Bearer 后面的部分
     * @param serverKey   当前访问的服务，用于 scope 判定
     * @return 通过则返回机器身份，否则 null（调用方统一回 401，不区分具体原因）
     */
    public McpPrincipal authenticate(String bearerToken, String serverKey) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        String hash = sha256Hex(bearerToken);
        McpAccessToken token = repository.findByTokenHash(hash).orElse(null);
        if (token == null) {
            return null;
        }
        // 常量时间比较：即使 hash 命中也再比一次，避免依赖数据库比较的时序
        if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8),
                token.getTokenHash().getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        if (token.getEnabled() == null || token.getEnabled() != 1 || token.getRevokedAt() != null) {
            return null;
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        if (!scopeAllows(token.getServerScope(), serverKey)) {
            return null;
        }
        return new McpPrincipal(token.getId(), token.getTokenName());
    }

    public List<McpAccessToken> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public void revoke(Long id, String operator) {
        McpAccessToken token = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("令牌不存在：" + id));
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(LocalDateTime.now());
            token.setEnabled(0);
            repository.save(token);
            log.info("MCP 令牌已吊销：{}（{}，操作人 {}）", token.getTokenName(), token.getTokenPrefix(), operator);
        }
    }

    /** 使用痕迹异步落库，不阻塞 MCP 调用本身；失败只记日志，不影响返回。 */
    @Async
    public void touchAsync(Long tokenId) {
        try {
            repository.touch(tokenId, LocalDateTime.now());
        } catch (Exception e) {
            log.debug("MCP 令牌使用痕迹更新失败（忽略）：{}", e.getMessage());
        }
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private static String normalizeScope(List<String> scope) {
        if (scope == null || scope.isEmpty() || scope.contains("*")) {
            return "*";
        }
        List<String> valid = scope.stream()
                .filter(McpServers::isValid)
                .distinct()
                .toList();
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("可访问范围至少要选择一个 MCP 服务");
        }
        return String.join(",", valid);
    }

    private static boolean scopeAllows(String serverScope, String serverKey) {
        if (serverScope == null || serverScope.isBlank() || "*".equals(serverScope.trim())) {
            return true;
        }
        if (serverKey == null) {
            return false;
        }
        for (String part : serverScope.split(",")) {
            if (serverKey.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 创建结果：实体（不含明文）+ 仅此一次的明文。 */
    public record TokenIssueResult(McpAccessToken entity, String plaintext) {
    }
}
