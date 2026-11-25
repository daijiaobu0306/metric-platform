package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.config.service.ConfigService;
import com.metric.metric.entity.MetricItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SatisfactionAlarmValueServiceTest {
    JdbcTemplate jdbc;
    ConfigService config;
    SatisfactionAlarmValueService service;
    MetricItem metric;

    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        config = mock(ConfigService.class);
        service = new SatisfactionAlarmValueService(jdbc, new ObjectMapper(), config);
        metric = new MetricItem(); metric.setMetricCode("SAT_FINAL_R003");
        metric.setDataSource("satisfaction_report");
        when(config.getConfigValue(anyString(), anyString())).thenReturn("{}");
    }

    void policy(String json) {
        when(config.getConfigValue(eq(SatisfactionAlarmValueService.policyKey(metric.getMetricCode())), anyString()))
                .thenReturn(json);
    }

    void daily(String date, String value) {
        when(jdbc.queryForList(startsWith("SELECT id,business_date"))).thenReturn(List.of(
                Map.of("id", "latest", "business_date", Date.valueOf(date))));
        var row = new HashMap<String, Object>();
        row.put("metric_value", value == null ? null : new BigDecimal(value));
        when(jdbc.queryForList(startsWith("SELECT metric_value"), eq("latest"), eq(metric.getMetricCode())))
                .thenReturn(List.of(row));
    }

    List<SatisfactionAlarmValueService.Observation> read(String threshold, String condition) {
        return service.observations(metric, new BigDecimal(threshold), condition);
    }

    @Test void percentagePointsAndInclusiveBoundariesFollowSource() {
        daily("2026-10-08", "85");
        assertTrue(read("85", "<=").get(0).breached());
        assertFalse(read("85", "<").get(0).breached());
        daily("2026-10-08", "85.0001");
        assertFalse(read("85", "<=").get(0).breached());
        daily("2026-10-08", "0");
        assertTrue(read("5", "<=").get(0).breached());
    }

    @Test void latestMissingDoesNotFallBackToOlderValueOrZero() {
        daily("2026-10-08", null);
        assertNull(read("85", "<=").get(0).value());
        assertFalse(read("85", "<=").get(0).breached());
        verify(jdbc, times(2)).queryForList(startsWith("SELECT metric_value"), eq("latest"), eq(metric.getMetricCode()));
    }

    @Test void countThresholdIsStrictAndUsesBusinessDate() {
        daily("2026-10-08", "5");
        assertFalse(read("5", ">").get(0).breached());
        daily("2026-10-08", "6");
        var value = read("5", ">").get(0);
        assertTrue(value.breached()); assertEquals(LocalDate.of(2026, 10, 8), value.businessDate());
    }

    @Test void monthEdgesUse300WhileOtherDaysUseConfigured210() {
        policy("{\"workdayOnly\":true,\"edgeThreshold\":300}");
        when(config.getConfigValue(eq("satisfaction.alarm.workday-calendar"), anyString())).thenReturn(
                "{\"2026-03-02\":true,\"2026-03-04\":true,\"2026-03-30\":true,\"2026-02-28\":true}");
        for (String date : List.of("2026-03-02", "2026-03-30")) {
            daily(date, "300"); assertFalse(read("210", ">").get(0).breached());
            daily(date, "301"); assertTrue(read("210", ">").get(0).breached());
        }
        for (String date : List.of("2026-03-04", "2026-02-28")) {
            daily(date, "211"); assertTrue(read("210", ">").get(0).breached());
        }
    }

    @Test void officialHolidayAndAdjustedWeekendOverrideWeekday() {
        policy("{\"workdayOnly\":true}");
        when(config.getConfigValue(eq("satisfaction.alarm.workday-calendar"), anyString())).thenReturn(
                "{\"2026-10-01\":false,\"2026-10-10\":true}");
        daily("2026-10-01", "1000"); assertFalse(read("160", ">").get(0).breached());
        daily("2026-10-10", "161"); assertTrue(read("160", ">").get(0).breached());
        daily("2027-01-04", "1000"); assertFalse(read("160", ">").get(0).breached());
    }

    @Test void dispatchCountAppliesOnHolidays() {
        daily("2026-10-01", "71"); assertTrue(read("70", ">").get(0).breached());
    }

    @Test void monthlyLimitNeedsCompleteNonNullDailyCoverage() {
        policy("{\"monthlyThreshold\":6000}"); daily("2026-10-08", "100");
        when(jdbc.queryForList(startsWith("SELECT COUNT"), eq(metric.getMetricCode()), eq(Date.valueOf("2026-10-01")), eq(Date.valueOf("2026-10-08")), eq("latest"), eq(Date.valueOf("2026-10-08"))))
                .thenReturn(List.of(Map.of("days", 7L, "total", new BigDecimal("7000"))));
        assertEquals(1, read("210", ">").size());
        when(jdbc.queryForList(startsWith("SELECT COUNT"), eq(metric.getMetricCode()), eq(Date.valueOf("2026-10-01")), eq(Date.valueOf("2026-10-08")), eq("latest"), eq(Date.valueOf("2026-10-08"))))
                .thenReturn(List.of(Map.of("days", 8L, "total", new BigDecimal("6000"))));
        assertFalse(read("210", ">").get(1).breached());
        when(jdbc.queryForList(startsWith("SELECT COUNT"), eq(metric.getMetricCode()), eq(Date.valueOf("2026-10-01")), eq(Date.valueOf("2026-10-08")), eq("latest"), eq(Date.valueOf("2026-10-08"))))
                .thenReturn(List.of(Map.of("days", 8L, "total", new BigDecimal("6001"))));
        assertTrue(read("210", ">").get(1).breached());
    }

    @Test void invalidPolicyFailsInsteadOfUsingAnUnqualifiedThreshold() {
        daily("2026-10-08", "1000"); policy("broken");
        assertThrows(IllegalArgumentException.class, () -> read("210", ">"));
    }
}
