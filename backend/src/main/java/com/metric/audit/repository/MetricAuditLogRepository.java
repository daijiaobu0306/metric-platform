package com.metric.audit.repository;

import com.metric.audit.entity.MetricAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MetricAuditLogRepository extends JpaRepository<MetricAuditLog, Long> {
    Page<MetricAuditLog> findByEntityTypeOrderByCreateTimeDesc(String entityType, Pageable pageable);
    Page<MetricAuditLog> findByOperationTypeOrderByCreateTimeDesc(String operationType, Pageable pageable);
    Page<MetricAuditLog> findAllByOrderByCreateTimeDesc(Pageable pageable);
}
