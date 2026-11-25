package com.metric.mcp.repository;

import com.metric.mcp.entity.McpCallLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface McpCallLogRepository extends JpaRepository<McpCallLog, Long> {

    Page<McpCallLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<McpCallLog> findByServerKeyOrderByCreatedAtDesc(String serverKey, Pageable pageable);

    long countByServerKeyAndCreatedAtAfter(String serverKey, LocalDateTime since);

    Page<McpCallLog> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since, Pageable pageable);

    /** 每个服务最近一次调用时间：返回 [serverKey, max(createdAt)]。 */
    @Query("select l.serverKey, max(l.createdAt) from McpCallLog l group by l.serverKey")
    List<Object[]> findLastCallTimeByServer();

    @Query("select count(l) from McpCallLog l where l.success = 0 and l.createdAt > :since")
    long countFailuresSince(@Param("since") LocalDateTime since);
}
