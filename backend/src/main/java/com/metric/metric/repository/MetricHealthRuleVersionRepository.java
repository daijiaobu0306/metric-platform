package com.metric.metric.repository;

import com.metric.metric.entity.MetricHealthRuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetricHealthRuleVersionRepository extends JpaRepository<MetricHealthRuleVersion, Long> {

    List<MetricHealthRuleVersion> findTop100ByOrderByChangedAtDesc();

    List<MetricHealthRuleVersion> findTop50ByMetricIdOrderByChangedAtDesc(Long metricId);
}
