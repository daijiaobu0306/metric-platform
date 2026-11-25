package com.metric.metric.service;
import com.metric.metric.entity.*;
import com.metric.metric.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ExternalCustomerMonitorTest {
    MetricMonitorService monitor; ExternalCustomerMetricService values;
    MetricRealtimeRepository realtime; MetricItem metric; MetricRealtime current;
    BusinessValueMetricService businessValues;
    MetricCategory category;
    @BeforeEach void setup() {
        monitor=new MetricMonitorService(); values=mock(ExternalCustomerMetricService.class);
        ReflectionTestUtils.setField(monitor,"collectedMetrics",mock(CollectedMetricService.class));
        var strategies=mock(com.metric.alarm.service.AlarmStrategyQueryService.class);
        when(strategies.loadSnapshot()).thenReturn(new com.metric.alarm.service.AlarmStrategyQueryService.MetricThresholdSnapshot(java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),java.util.Map.of()));
        ReflectionTestUtils.setField(monitor,"alarmStrategyQueryService",strategies);
        realtime=mock(MetricRealtimeRepository.class); var configs=mock(MetricMonitorConfigRepository.class);
        ReflectionTestUtils.setField(monitor,"externalCustomerValues",values);
        businessValues=mock(BusinessValueMetricService.class);
        ReflectionTestUtils.setField(monitor,"businessValueMetrics",businessValues);
        ReflectionTestUtils.setField(monitor,"metricRealtimeRepository",realtime);
        ReflectionTestUtils.setField(monitor,"metricMonitorConfigRepository",configs);
        var categories=mock(MetricCategoryRepository.class);
        ReflectionTestUtils.setField(monitor,"metricCategoryRepository",categories);
        category=new MetricCategory();category.setCategoryCode("external_customer");
        when(categories.findById(17L)).thenReturn(Optional.of(category));
        metric=new MetricItem();metric.setId(236L);metric.setCategoryId(17L);metric.setMetricCode("payCreditAvgTime");metric.setDataSource("CRM");
        current=new MetricRealtime();current.setMetricId(236L);current.setMetricValue(new BigDecimal("8"));
        when(realtime.findByMetricId(236L)).thenReturn(Optional.of(current));
        var config=new MetricMonitorConfig();config.setAlarmStatus(0);config.setGongdanStatus(0);
        when(configs.findByMetricId(236L)).thenReturn(Optional.of(config));
    }
    @Test void usesRawValueAndBusinessTimeTogetherWithoutRecalculation() {
        var time=LocalDateTime.of(2026,10,18,0,0);
        when(values.latest(236L)).thenReturn(new ExternalCustomerMetricService.Observation(new BigDecimal("5.1234"),time));
        assertTrue(monitor.processMetricItem(metric));
        assertEquals(new BigDecimal("5.1234"),current.getMetricValue()); assertEquals(time,current.getDataTime());
        verify(values,times(1)).latest(236L);verify(realtime).save(current);
    }
    @Test void absentSourceDoesNotOverwriteExistingValue() {
        when(values.latest(236L)).thenReturn(null);
        assertFalse(monitor.processMetricItem(metric));assertEquals(new BigDecimal("8"),current.getMetricValue());
        verify(realtime,never()).save(any());
    }
    @Test void businessValueScanKeepsCollectedValueAndOriginalTimeInsteadOfOldAggregate() {
        category.setCategoryCode("businessValue"); metric.setDataSource("business_value");
        var time=LocalDateTime.of(2026,10,18,14,5,42);
        when(businessValues.latest(236L)).thenReturn(new BusinessValueMetricService.Observation(new BigDecimal("120"),time));
        assertTrue(monitor.processMetricItem(metric)); assertTrue(monitor.processMetricItem(metric));
        assertEquals(new BigDecimal("120"),current.getMetricValue()); assertEquals(time,current.getDataTime());
        assertEquals("待配置",current.getStatus());
        verify(values,never()).latest(any()); verify(realtime,times(2)).save(current);
    }
}
