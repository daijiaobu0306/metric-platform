package com.metric.metric.service;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Compatibility facade over the common metric-code/category observation reader. */
@Service
public class ExternalCustomerMetricService {
    private final CollectedMetricService collected;
    public ExternalCustomerMetricService(CollectedMetricService collected) { this.collected=collected; }
    public static boolean supports(com.metric.metric.entity.MetricItem metric) { return "external_customer".equals(metric.getDataSource()); }
    public record Observation(BigDecimal value,LocalDateTime dataTime) {}
    public Observation latest(Long metricId) {
        var value=collected.latest(metricId);
        return value==null?null:new Observation(value.value(),value.dataTime());
    }
}
