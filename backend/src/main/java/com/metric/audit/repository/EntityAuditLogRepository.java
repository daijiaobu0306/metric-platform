package com.metric.audit.repository;

import com.metric.audit.entity.EntityAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface EntityAuditLogRepository extends JpaRepository<EntityAuditLog, Long> {

    Page<EntityAuditLog> findByEntityTypeOrderByCreateTimeDesc(String entityType, Pageable pageable);

    Page<EntityAuditLog> findByEntityTypeAndEntityIdOrderByCreateTimeDesc(String entityType, Long entityId, Pageable pageable);

    Page<EntityAuditLog> findByCreateTimeBetweenOrderByCreateTimeDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    @Query("SELECT e FROM EntityAuditLog e WHERE " +
           "(:entityType IS NULL OR e.entityType = :entityType) AND " +
           "(:entityId IS NULL OR e.entityId = :entityId) AND " +
           "(:operationType IS NULL OR e.operationType = :operationType) AND " +
           "(:startTime IS NULL OR e.createTime >= :startTime) AND " +
           "(:endTime IS NULL OR e.createTime <= :endTime) " +
           "ORDER BY e.createTime DESC")
    Page<EntityAuditLog> findByConditions(
            @Param("entityType") String entityType,
            @Param("entityId") Long entityId,
            @Param("operationType") String operationType,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);
}