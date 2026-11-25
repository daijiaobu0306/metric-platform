package com.metric.metric.repository;

import com.metric.metric.entity.MetricMonitorConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MetricMonitorConfigRepository extends JpaRepository<MetricMonitorConfig, Long> {

    Optional<MetricMonitorConfig> findByMetricId(Long metricId);

    List<MetricMonitorConfig> findByAlarmStatus(Integer alarmStatus);

    List<MetricMonitorConfig> findByGongdanStatus(Integer gongdanStatus);

    List<MetricMonitorConfig> findByMetricIdIn(List<Long> metricIds);
}
