package com.metric.metric.repository;

import com.metric.metric.entity.MetricHealthRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricHealthRuleRepository extends JpaRepository<MetricHealthRule, Long> {

    Optional<MetricHealthRule> findByMetricId(Long metricId);

    List<MetricHealthRule> findByMetricIdIn(Collection<Long> metricIds);

    List<MetricHealthRule> findByStatus(Integer status);
}
