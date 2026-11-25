package com.metric.collect.repository;

import com.metric.collect.entity.PluginExecutionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PluginExecutionLogRepository extends JpaRepository<PluginExecutionLog, Long> {

    Page<PluginExecutionLog> findByPluginId(Long pluginId, Pageable pageable);

    Page<PluginExecutionLog> findByBusinessTypeAndBusinessId(String businessType, Long businessId, Pageable pageable);

    List<PluginExecutionLog> findByPluginIdAndExecTimeAfterOrderByExecTimeDesc(Long pluginId, LocalDateTime after);

    Page<PluginExecutionLog> findByExecStatus(String execStatus, Pageable pageable);
}
