package com.metric.metric.repository;

import com.metric.metric.entity.MetricData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricDataRepository extends JpaRepository<MetricData, Long> {

    Page<MetricData> findByMetricId(Long metricId, Pageable pageable);

    @Query("SELECT m FROM MetricData m WHERE m.metricId = :metricId AND m.collectTime BETWEEN :startTime AND :endTime ORDER BY m.collectTime DESC")
    List<MetricData> findByMetricIdAndCollectTimeBetween(@Param("metricId") Long metricId,
                                                          @Param("startTime") LocalDateTime startTime,
                                                          @Param("endTime") LocalDateTime endTime);

    @Query("SELECT m FROM MetricData m WHERE m.metricId = :metricId ORDER BY m.collectTime DESC")
    List<MetricData> findLatestByMetricId(@Param("metricId") Long metricId, Pageable pageable);
}
