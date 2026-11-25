package com.metric.alarm.repository;

import com.metric.alarm.entity.AlarmAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlarmAuditLogRepository extends JpaRepository<AlarmAuditLog, Long> {

    Page<AlarmAuditLog> findByEntityTypeOrderByCreateTimeDesc(String entityType, Pageable pageable);

    Page<AlarmAuditLog> findByOperationTypeOrderByCreateTimeDesc(String operationType, Pageable pageable);

    Page<AlarmAuditLog> findByEntityTypeAndEntityIdOrderByCreateTimeDesc(String entityType, Long entityId, Pageable pageable);

    Page<AlarmAuditLog> findByCreateTimeBetweenOrderByCreateTimeDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    @Query("SELECT a FROM AlarmAuditLog a WHERE a.entityType = :entityType AND a.createTime BETWEEN :start AND :end ORDER BY a.createTime DESC")
    Page<AlarmAuditLog> findByEntityTypeAndTimeRange(
            @Param("entityType") String entityType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);

    List<AlarmAuditLog> findByEntityTypeAndEntityIdOrderByCreateTimeDesc(String entityType, Long entityId);

    @Query("SELECT a FROM AlarmAuditLog a WHERE " +
           "(:entityType IS NULL OR a.entityType = :entityType) AND " +
           "(:operationType IS NULL OR a.operationType = :operationType) AND " +
           "(:startTime IS NULL OR a.createTime >= :startTime) AND " +
           "(:endTime IS NULL OR a.createTime <= :endTime) " +
           "ORDER BY a.createTime DESC")
    Page<AlarmAuditLog> findByConditions(
            @Param("entityType") String entityType,
            @Param("operationType") String operationType,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);
}