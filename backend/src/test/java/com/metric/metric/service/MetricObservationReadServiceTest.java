package com.metric.metric.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MetricObservationReadServiceTest {
    private final MetricPeriodQueryService definitions=mock(MetricPeriodQueryService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LocalDateTime dataTime=LocalDateTime.of(2026,10,30,9,0);
    private MetricObservationReadService service;
    private Map<String,Object> definition;
    private List<Map<String,Object>> sourceRows;
    private List<Map<String,Object>> snapshots;

    @BeforeEach void setup() {
        service=new MetricObservationReadService(definitions,jdbc);
        definition=new HashMap<>(Map.of("metricId",291L,"metricCode","duration","ruleType","resource_value",
            "codeMatches",1,"unit","小时","alarmCondition",">","alarmThreshold",new BigDecimal("10")));
        sourceRows=List.of(new HashMap<>(Map.of("metricId",291L,"rawValue","0","dataTime",dataTime,"conflict",0)));
        snapshots=List.of();
        when(definitions.observationCatalog(List.of(291L))).thenReturn(List.of(definition));
        // Exercise the public reader with source/realtime/health results at its JDBC boundary.
        when(jdbc.query(any(PreparedStatementCreator.class),any(RowMapper.class))).thenAnswer(call -> {
            String query=((SqlProvider)call.getArgument(0)).getSql();
            if(query.contains("FROM observations_latest WHERE latest=1")) return sourceRows;
            if(query.contains("FROM metric_realtime")) return snapshots;
            return List.of();
        });
    }

    private Map<String,Object> latest() {
        return service.latest(List.of(291L),dataTime.minusDays(1),dataTime.plusDays(1)).get(0);
    }

    @Test void zeroStaysAnExactCollectedValueWithoutInventingHealth() {
        var row=latest();
        assertEquals("0",row.get("metricValue"));
        assertEquals("已采集",row.get("dataStatus"));
        assertEquals("小时",row.get("unit"));
        assertNull(row.get("healthScore"));
    }

    @Test void newestMissingRevisionDoesNotFallBackToCachedNonzeroValue() {
        var missing=new HashMap<String,Object>(sourceRows.get(0));
        missing.put("rawValue",null);sourceRows=List.of(missing);
        snapshots=List.of(new HashMap<>(Map.of("metricId",291L,"rawValue","9.7500","dataTime",dataTime,"status","正常")));
        var row=latest();
        assertNull(row.get("metricValue"));
        assertEquals("缺数",row.get("status"));
    }

    @Test void missingThresholdCannotReuseCachedNormalStatus() {
        definition.remove("alarmThreshold");
        snapshots=List.of(new HashMap<>(Map.of("metricId",291L,"rawValue","0","dataTime",dataTime,"status","正常")));
        var row=latest();
        assertEquals("0",row.get("metricValue"));
        assertNull(row.get("threshold"));
        assertEquals("待配置",row.get("status"));
    }

    @Test void conflictingSourceCannotExposeCachedValue() {
        sourceRows.get(0).put("conflict",1);
        snapshots=List.of(new HashMap<>(Map.of("metricId",291L,"rawValue","0","dataTime",dataTime,"status","正常")));
        var row=latest();
        assertNull(row.get("metricValue"));
        assertEquals("来源冲突",row.get("status"));
    }

    @Test void equalValueFromAnotherDataTimeCannotReuseNormalStatus() {
        snapshots=List.of(new HashMap<>(Map.of("metricId",291L,"rawValue","0","dataTime",dataTime.minusDays(1),"status","正常")));
        assertEquals("待判定",latest().get("status"));
    }

    @Test void duplicateCodeDoesNotGuessACollectedRecord() {
        definition.put("codeMatches",2);
        var row=latest();
        assertNull(row.get("metricValue"));
        assertNull(row.get("dataTime"));
        assertEquals("编码冲突",row.get("status"));
    }

    @Test void emptyAuthorizedScopeCannotExpandToAllMetrics() {
        assertTrue(service.latest(List.of(),null,dataTime).isEmpty());
        verifyNoInteractions(definitions,jdbc);
    }
    @Test void periodSummaryReusesLatestRowsWithoutChangingTheirSourceTimestamp() {
        var rows = service.latest(List.of(291L),dataTime.minusDays(1),dataTime.plusDays(1));
        var sourceTimestamp = rows.get(0).get("latestDataTime");
        var summary = service.periodDataFromLatest(rows,dataTime.minusDays(1),dataTime.plusDays(1));
        assertEquals(sourceTimestamp,rows.get(0).get("latestDataTime"));
        assertFalse(rows.get(0).containsKey("records"));
        assertTrue(summary.get(0).containsKey("records"));
        assertNotSame(rows.get(0),summary.get(0));
        verify(definitions,times(1)).observationCatalog(List.of(291L));
    }

}
