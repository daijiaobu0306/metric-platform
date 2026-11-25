package com.metric.metric.repository;

import com.metric.metric.entity.MetricScoreBaseline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricScoreBaselineRepository extends JpaRepository<MetricScoreBaseline, Long> {

    Optional<MetricScoreBaseline> findByMetricId(Long metricId);

    List<MetricScoreBaseline> findByMetricIdIn(Collection<Long> metricIds);

    void deleteByMetricId(Long metricId);
}
