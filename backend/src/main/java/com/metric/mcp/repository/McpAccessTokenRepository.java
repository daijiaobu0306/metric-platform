package com.metric.mcp.repository;

import com.metric.mcp.entity.McpAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface McpAccessTokenRepository extends JpaRepository<McpAccessToken, Long> {

    Optional<McpAccessToken> findByTokenHash(String tokenHash);

    List<McpAccessToken> findAllByOrderByCreatedAtDesc();

    /**
     * 只更新使用痕迹，不动其它列。
     * 用独立 update 而不是 save(实体)，避免并发调用时的整行覆盖。
     */
    @Modifying
    @Transactional
    @Query("update McpAccessToken t set t.lastUsedAt = :now, t.callCount = t.callCount + 1 where t.id = :id")
    int touch(@Param("id") Long id, @Param("now") LocalDateTime now);
}
