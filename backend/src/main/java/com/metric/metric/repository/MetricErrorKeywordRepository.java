package com.metric.metric.repository;

import com.metric.metric.entity.MetricErrorKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MetricErrorKeywordRepository extends JpaRepository<MetricErrorKeyword, Long> {
    List<MetricErrorKeyword> findByMetricId(Long metricId);
    void deleteByMetricId(Long metricId);
}