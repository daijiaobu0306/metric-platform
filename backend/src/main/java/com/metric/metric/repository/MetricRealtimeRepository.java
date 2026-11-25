package com.metric.metric.repository;

import com.metric.metric.entity.MetricRealtime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MetricRealtimeRepository extends JpaRepository<MetricRealtime, Long> {

    Optional<MetricRealtime> findByMetricId(Long metricId);

    List<MetricRealtime> findByStatus(String status);

    List<MetricRealtime> findByIsExceed(Integer isExceed);

    List<MetricRealtime> findByMetricIdIn(List<Long> metricIds);
}
