package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SatisfactionReportStateTest {
    @Test void selectedBatchIsEvaluatedWithoutSubstitutingLatestRealtimeData() {
        var values = mock(SatisfactionAlarmValueService.class);
        var service = new ComplaintReportService(mock(JdbcTemplate.class), mock(TransactionTemplate.class),
                new ObjectMapper(), mock(BasePathProvider.class));
        ReflectionTestUtils.setField(service, "alarmValues", values);
        var date = LocalDate.of(2026, 10, 7);
        var row = new HashMap<String, Object>();
        row.put("metricId", 322L); row.put("metricCode", "SAT_FINAL_R003");
        row.put("metricValue", new BigDecimal("211")); row.put("alarmThreshold", new BigDecimal("210"));
        row.put("alarmCondition", ">"); row.put("alarmEnabled", 1);
        row.put("alarmPolicy", "{\"monthlyThreshold\":6000}");
        when(values.observations(any(), any(), any(), eq("historical"), eq(date))).thenReturn(List.of(
                new SatisfactionAlarmValueService.Observation(new BigDecimal("211"),new BigDecimal("210"),">",date,"日")));
        ReflectionTestUtils.invokeMethod(service, "enrichAlarmState", row, "historical", date);
        assertEquals("告警",row.get("status")); assertEquals(1,row.get("isExceed"));
        assertEquals(new BigDecimal("211"),row.get("metricValue"));
        assertEquals(new BigDecimal("6000"),row.get("monthlyThreshold"));
        verify(values, never()).observations(any(),any(),any());
        row.put("metricValue", null);
        ReflectionTestUtils.invokeMethod(service, "enrichAlarmState", row, "historical", date);
        assertEquals("无数据",row.get("status")); assertNull(row.get("isExceed"));
    }

    @Test void invalidCountPolicyIsRejectedBeforeAnyWrite() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(322L))).thenReturn(List.of(Map.of(
                "metric_code","SAT_FINAL_R003","alarm_policy","{}")));
        var service=new SatisfactionAlarmRuleService(jdbc,new ObjectMapper());
        for (Object bad : List.of(-1,"1.2","NaN","100000000")) {
            var input=new HashMap<String,Object>(); input.put("alarmCondition",">");input.put("alarmThreshold",bad);
            assertThrows(IllegalArgumentException.class,()->service.update(322L,input));
        }
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
