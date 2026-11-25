package com.metric.collect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.config.service.ConfigService;
import com.metric.metric.entity.MetricItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Final satisfaction indicators use dated report results, never the legacy mock data. */
@Service
public class SatisfactionAlarmValueService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConfigService config;

    public SatisfactionAlarmValueService(JdbcTemplate jdbc, ObjectMapper mapper, ConfigService config) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.config = config;
    }

    public static boolean supports(MetricItem metric) {
        return "satisfaction_report".equals(metric.getDataSource());
    }

    public static String policyKey(String metricCode) {
        return "satisfaction.alarm.policy." + metricCode;
    }

    public JsonNode alarmPolicy(MetricItem metric) {
        return policy(config.getConfigValue(policyKey(metric.getMetricCode()), "{}"));
    }

    public record Observation(BigDecimal value, BigDecimal threshold, String condition,
                              LocalDate businessDate, String period) {
        public boolean breached() {
            if (value == null || threshold == null) return false;
            int comparison = value.compareTo(threshold);
            return switch (condition) {
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case "=" -> comparison == 0;
                default -> false;
            };
        }
    }

    public List<Observation> observations(MetricItem metric, BigDecimal threshold, String condition) {
        var batches = jdbc.queryForList("SELECT id,business_date FROM satisfaction_report_batch "
                + "ORDER BY business_date DESC,imported_at DESC,id DESC LIMIT 1");
        if (batches.isEmpty()) return List.of();
        var batch = batches.get(0);
        LocalDate date = ((Date) batch.get("business_date")).toLocalDate();
        return observations(metric, threshold, condition, batch.get("id").toString(), date);
    }

    public List<Observation> observations(MetricItem metric, BigDecimal threshold, String condition,
                                          String batchId, LocalDate date) {
        var values = jdbc.queryForList("SELECT metric_value FROM satisfaction_report_result WHERE batch_id=? AND metric_code=?",
                batchId, metric.getMetricCode());
        BigDecimal value = values.isEmpty() ? null : (BigDecimal) values.get(0).get("metric_value");
        JsonNode policy = alarmPolicy(metric);
        BigDecimal dailyThreshold = threshold;
        if (policy.path("workdayOnly").asBoolean() && !isWorkday(date)) dailyThreshold = null;
        if (dailyThreshold != null && policy.has("edgeThreshold")
                && (date.getDayOfMonth() <= 3 || date.getDayOfMonth() >= 30)) {
            dailyThreshold = policy.get("edgeThreshold").decimalValue();
        }
        var result = new ArrayList<Observation>();
        result.add(new Observation(value, dailyThreshold, condition, date, "日"));
        if (policy.has("monthlyThreshold")) {
            // Require one non-null result for every elapsed calendar day. Missing days are not zero.
            // NOT EXISTS selects the latest revision of each day before summing, avoiding double counting.
            var monthly = jdbc.queryForList("""
                    SELECT COUNT(r.metric_value) AS days,SUM(r.metric_value) AS total
                    FROM satisfaction_report_batch b
                    LEFT JOIN satisfaction_report_result r ON r.batch_id=b.id AND r.metric_code=?
                    WHERE b.business_date BETWEEN ? AND ?
                    AND (b.id=? OR (b.business_date<? AND NOT EXISTS (SELECT 1 FROM satisfaction_report_batch newer
                        WHERE newer.business_date=b.business_date
                        AND (newer.imported_at>b.imported_at OR (newer.imported_at=b.imported_at AND newer.id>b.id)))))
                    """, metric.getMetricCode(), Date.valueOf(date.withDayOfMonth(1)), Date.valueOf(date), batchId, Date.valueOf(date));
            if (!monthly.isEmpty() && ((Number) monthly.get(0).get("days")).intValue() == date.getDayOfMonth()) {
                result.add(new Observation((BigDecimal) monthly.get(0).get("total"),
                        policy.get("monthlyThreshold").decimalValue(), ">", date, "月累计"));
            }
        }
        return result;
    }

    private JsonNode policy(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalArgumentException("满意度告警规则JSON无效", e);
        }
    }

    public Boolean workingDay(LocalDate date) {
        JsonNode calendar = policy(config.getConfigValue("satisfaction.alarm.workday-calendar", "{}"));
        if (calendar.has(date.toString())) return calendar.get(date.toString()).asBoolean();
        // An unknown holiday/adjusted workday must not produce an assumed daily alarm.
        return null;
    }

    private boolean isWorkday(LocalDate date) {
        return Boolean.TRUE.equals(workingDay(date));
    }
}
