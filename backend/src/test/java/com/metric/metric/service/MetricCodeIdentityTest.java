package com.metric.metric.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MetricCodeIdentityTest {
    @Test void onlyApprovedBusinessCodeGetsAnAlias() {
        assertEquals("importantAlarmRatio",MetricCodeIdentity.canonical("businessValue","importantAlarmRate"));
        assertEquals("importantAlarmRate",MetricCodeIdentity.canonical("system_run","importantAlarmRate"));
        assertEquals("ImportantAlarmRate",MetricCodeIdentity.canonical("businessValue","ImportantAlarmRate"));
        assertEquals("importantAlarmRatio",MetricCodeIdentity.canonical("businessValue","importantAlarmRatio"));
        assertEquals("unknown",MetricCodeIdentity.canonical("businessValue","unknown"));
    }

    private Map<String,Object> record(String code) {
        var row=new HashMap<String,Object>();
        row.put("metric_code",code);row.put("metric_value","31.2345");row.put("data_time","2026-11-02T09:00:00");
        return row;
    }

    private CollectedMetricService intake(JdbcTemplate jdbc) {
        when(jdbc.queryForList(contains("FROM metric_item m JOIN metric_category c"),eq("businessValue")))
            .thenReturn(List.of(Map.of("metricId",280L,"metricCode","importantAlarmRatio","metricName","重要告警占比","unit","%")));
        return new CollectedMetricService(jdbc);
    }

    @Test void intakeAcceptsApprovedLegacyCodeAndPreservesRawIdentityAndValue() {
        var jdbc=mock(JdbcTemplate.class);var intake=intake(jdbc);var old=record("importantAlarmRate");
        intake.validate("businessValue",List.of(old));
        assertEquals("importantAlarmRate",old.get("metric_code"));
        assertEquals("31.2345",old.get("metric_value"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void oldAndNewCodeInTheSameTimestampCannotBypassDuplicateGuard() {
        var jdbc=mock(JdbcTemplate.class);var intake=intake(jdbc);
        assertThrows(IllegalArgumentException.class,()->intake.validate("businessValue",List.of(record("importantAlarmRate"),record("importantAlarmRatio"))));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void anotherCategoryCannotUseTheBusinessRename() {
        var jdbc=mock(JdbcTemplate.class);var intake=new CollectedMetricService(jdbc);
        when(jdbc.queryForList(contains("FROM metric_item m JOIN metric_category c"),eq("external_customer")))
            .thenReturn(List.of(Map.of("metricId",280L,"metricCode","importantAlarmRatio")));
        assertThrows(IllegalArgumentException.class,()->intake.validate("external_customer",List.of(record("importantAlarmRate"))));
    }
}
