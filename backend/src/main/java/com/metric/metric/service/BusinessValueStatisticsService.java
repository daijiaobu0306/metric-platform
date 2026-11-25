package com.metric.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class BusinessValueStatisticsService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final String CATEGORY_CODES = """
        ('external_customer','metric_g2','satisfaction','system_run',
         'metric_g1','businessValue','resourceInput','dataQuality')
        """;

    private final JdbcTemplate jdbc;
    private final MetricObservationReadService observations;
    private final MetricPeriodQueryService definitions;

    public BusinessValueStatisticsService(JdbcTemplate jdbc, MetricObservationReadService observations,
                                          MetricPeriodQueryService definitions) {
        this.jdbc = jdbc;
        this.observations = observations;
        this.definitions = definitions;
    }

    public Map<String, Number> statistics(LocalDate startDate, LocalDate endDate) {
        return calculate(range(startDate, endDate));
    }

    public List<Map<String, Object>> report(LocalDate startDate, LocalDate endDate, String subCategory) {
        var range = range(startDate, endDate);
        var ids = observations.scope("业务价值", subCategory);
        if (ids.isEmpty()) return List.of();
        var values = calculate(range);
        String period = range.start().equals(range.end()) ? range.start().toString()
            : range.start() + " 至 " + range.end();
        var rows = definitions.observationCatalog(ids);
        for (var row : rows) {
            var value = values.get(row.get("metricCode"));
            row.put("metricValue", value);
            row.put("dataTime", period);
            row.put("status", value == null ? "无数据" : row.get("alarmThreshold") == null ? "待配置" : "待判定");
        }
        return rows;
    }

    private record DateRange(LocalDate start, LocalDate end, LocalDateTime now) {}

    private static DateRange range(LocalDate startDate, LocalDate endDate) {
        LocalDateTime now = LocalDateTime.now(BEIJING);
        LocalDate startDay = startDate == null ? now.toLocalDate() : startDate;
        LocalDate endDay = endDate == null ? startDay : endDate;
        if (endDay.isBefore(startDay) || endDay.isAfter(now.toLocalDate())) {
            throw new IllegalArgumentException("日期区间无效，结束日期不得早于开始日期或晚于今天");
        }
        return new DateRange(startDay, endDay, now);
    }

    private Map<String, Number> calculate(DateRange range) {
        LocalDateTime start = range.start().atStartOfDay();
        LocalDateTime end = range.end().plusDays(1).atStartOfDay();

        var alarms = jdbc.queryForMap("""
            SELECT COUNT(*) AS total, COUNT(CASE WHEN process_status=1 THEN 1 END) AS processed,
              COUNT(CASE WHEN alarm_level=3 THEN 1 END) AS important
            FROM alarm WHERE alarm_time>=? AND alarm_time<?
            """, start, end);
        var orders = jdbc.queryForMap("""
            SELECT COUNT(*) AS total, COUNT(CASE WHEN closed_status=1 THEN 1 END) AS closed
            FROM gongdan WHERE gongdan_time>=? AND gongdan_time<?
            """, start, end);
        var issues = jdbc.queryForMap("""
            SELECT COUNT(*) AS total, COUNT(CASE WHEN resolve_status='已解决' THEN 1 END) AS solved,
              COUNT(CASE WHEN issue_attribute='老问题' THEN 1 END) AS repeated,
              COUNT(CASE WHEN resolve_status='已解决' AND issue_source<>'人工巡检' THEN 1 END) AS optimized
            FROM issue WHERE register_time>=? AND register_time<?
            """, start, end);
        LocalDateTime reportEnd = end.isAfter(range.now()) ? range.now() : end.minusNanos(1000);
        // Business value report rows are period statistics without a realtime alarm verdict.
        List<Long> candidates = jdbc.queryForList("""
            SELECT m.id FROM metric_realtime rt
            JOIN metric_item m ON m.id=rt.metric_id
            JOIN metric_category c ON c.id=m.category_id
            WHERE rt.data_time>=? AND rt.data_time<=? AND rt.status IN ('告警','工单')
              AND m.status=1 AND c.category_code IN
            """ + CATEGORY_CODES + " AND c.category_code<>'businessValue'", Long.class, start, reportEnd);
        long abnormal = observations.countLatestAbnormal(candidates, start, reportEnd);

        long alarmCount = count(alarms, "total");
        long orderCount = count(orders, "total");
        long issueCount = count(issues, "total");
        Map<String, Number> values = new LinkedHashMap<>();
        values.put("alarmCount", alarmCount);
        values.put("alarmHandleRate", percent(count(alarms, "processed"), alarmCount));
        values.put("importantAlarmRatio", percent(count(alarms, "important"), alarmCount));
        values.put("orderCount", orderCount);
        values.put("orderCloseRate", percent(count(orders, "closed"), orderCount));
        values.put("problemCount", issueCount);
        values.put("problemSolveRate", percent(count(issues, "solved"), issueCount));
        values.put("problemIndexCount", abnormal);
        values.put("optimizeIndexCount", count(issues, "optimized"));
        values.put("repeatProblemCount", count(issues, "repeated"));
        values.put("indexIncludeCount", jdbc.queryForObject("SELECT COUNT(*) FROM metric_item", Long.class));
        values.put("dimensionIncludeCount", jdbc.queryForObject(
            "SELECT COUNT(DISTINCT category_code) FROM metric_category WHERE category_code IN " + CATEGORY_CODES, Long.class));
        return values;
    }

    private static long count(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static BigDecimal percent(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).movePointRight(2)
            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
