package com.metric.collect.service;

import com.metric.alarm.entity.Alarm;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.alarm.repository.AlarmStrategyRepository;
import com.metric.alarm.service.AlarmService;
import com.metric.alarm.service.ShieldRuleService;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SatisfactionAlarmScanTest {
    AlertGenerateService scanner;
    AlarmStrategy strategy;
    MetricItem metric;
    SatisfactionAlarmValueService values;
    AlarmRepository alarms;
    AlarmService alarmService;
    ShieldRuleService shielding;

    @BeforeEach void setup() {
        scanner = new AlertGenerateService();
        var effective = new com.metric.alarm.service.EffectiveObservationService();
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var states = new java.util.HashMap<String,String>();
        when(jdbc.queryForObject(anyString(), eq(String.class), anyLong(), anyString()))
                .thenAnswer(call -> states.getOrDefault(call.<String>getArgument(3), "{\"configuration\":\"test-baseline\"}"));
        when(jdbc.update(startsWith("UPDATE alarm_observation_state"), anyString(), anyLong(), anyString()))
                .thenAnswer(call -> { states.put(call.getArgument(3), call.getArgument(1)); return 1; });
        ReflectionTestUtils.setField(effective, "jdbc", jdbc);
        ReflectionTestUtils.setField(effective, "json", new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(scanner, "effectiveObservations", effective);
        var strategies = mock(AlarmStrategyRepository.class);
        var metrics = mock(MetricItemRepository.class);
        var categories = mock(MetricCategoryRepository.class);
        values = mock(SatisfactionAlarmValueService.class);
        alarms = mock(AlarmRepository.class); alarmService = mock(AlarmService.class);
        shielding = mock(ShieldRuleService.class);
        ReflectionTestUtils.setField(scanner, "strategyRepository", strategies);
        ReflectionTestUtils.setField(scanner, "metricItemRepository", metrics);
        ReflectionTestUtils.setField(scanner, "metricCategoryRepository", categories);
        ReflectionTestUtils.setField(scanner, "satisfactionAlarmValues", values);
        ReflectionTestUtils.setField(scanner, "alarmRepository", alarms);
        ReflectionTestUtils.setField(scanner, "alarmService", alarmService);
        ReflectionTestUtils.setField(scanner, "shieldRuleService", shielding);
        strategy = new AlarmStrategy(); strategy.setId(1L); strategy.setStrategyType("metric");
        strategy.setTargetType("metric"); strategy.setTargetId(10L); strategy.setAlarmEnabled(1);
        strategy.setGongdanEnabled(0); strategy.setAlarmThreshold(new BigDecimal("210"));
        strategy.setAlarmCondition(">"); strategy.setAlarmConsecutiveTimes(1);
        strategy.setStrategyName("客服-工单累计量-预警");
        metric = new MetricItem(); metric.setId(10L); metric.setMetricName("工单累计量");
        metric.setMetricCode("SAT_FINAL_R003"); metric.setDataSource("satisfaction_report");
        metric.setCategoryId(12L); metric.setSubCategoryName("客服投诉工单处理流程");
        when(strategies.findByStatusAndStrategyType(1, "metric")).thenReturn(List.of(strategy));
        when(metrics.findById(10L)).thenReturn(Optional.of(metric));
        when(categories.findById(12L)).thenReturn(Optional.empty());
        var exists = new AtomicBoolean();
        when(alarms.existsByStrategyIdAndMetricIdAndProcessStatus(anyLong(), anyLong(), anyInt()))
                .thenAnswer(call -> exists.get());
        java.util.List<Alarm> savedAlarms = new java.util.ArrayList<>();
        when(alarms.findByStrategyIdAndProcessStatus(anyLong(), anyInt())).thenReturn(savedAlarms);
        when(alarmService.save(any())).thenAnswer(call -> { exists.set(true); savedAlarms.add(call.getArgument(0)); return call.getArgument(0); });
    }

    SatisfactionAlarmValueService.Observation observation(String value, String threshold, String period) {
        return new SatisfactionAlarmValueService.Observation(new BigDecimal(value), new BigDecimal(threshold),
                ">", LocalDate.of(2026, 3, 2), period);
    }

    int scan() { return ReflectionTestUtils.invokeMethod(scanner, "scanMetricValueAlarms"); }

    @Test void calendarResolvedThresholdAndSourceDateReachAlarmAndRepeatedScansDeduplicate() {
        when(values.observations(any(), any(), any())).thenReturn(List.of(observation("301", "300", "日")));
        assertEquals(1, scan()); assertEquals(0, scan());
        var saved = ArgumentCaptor.forClass(Alarm.class); verify(alarmService).save(saved.capture());
        assertEquals(new BigDecimal("300"), saved.getValue().getThresholdValue());
        assertEquals(12L, saved.getValue().getCategoryId());
        assertTrue(saved.getValue().getAlarmContent().contains("统计日期=2026-03-02"));
    }

    @Test void monthlyCanTriggerWhenDailyDoesNotAndBothPeriodsDoNotDuplicate() {
        when(values.observations(any(), any(), any())).thenReturn(List.of(
                observation("100", "210", "日"), observation("6001", "6000", "月累计")));
        assertEquals(1, scan());
        var saved = ArgumentCaptor.forClass(Alarm.class); verify(alarmService).save(saved.capture());
        assertEquals(new BigDecimal("6000"), saved.getValue().getThresholdValue());
        assertTrue(saved.getValue().getAlarmContent().contains("口径=月累计"));
    }

    @Test void disabledStrategyAndShieldedMetricDoNotGenerate() {
        strategy.setAlarmEnabled(0); assertEquals(0, scan()); verifyNoInteractions(values);
        strategy.setAlarmEnabled(1);
        when(values.observations(any(), any(), any())).thenReturn(List.of(observation("301", "300", "日")));
        when(shielding.isAlarmShielded(any(), any(), any())).thenReturn(true);
        assertEquals(0, scan()); verifyNoInteractions(alarmService);
    }
}
