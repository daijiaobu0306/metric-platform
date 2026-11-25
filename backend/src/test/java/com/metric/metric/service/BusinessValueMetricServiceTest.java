package com.metric.metric.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class BusinessValueMetricServiceTest {
    JdbcTemplate jdbc; BusinessValueMetricService service;
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class); service=new BusinessValueMetricService(jdbc,new ObjectMapper());
        when(jdbc.queryForList(contains("AS alarmStatus"),eq("businessValue"),eq("businessValue"),isNull(),isNull())).thenReturn(List.of(new HashMap<>(Map.of(
            "metricId",1L,"metricCode","alarmCount","metricName","告警量","categoryName","业务价值",
            "subCategoryName","运营成效","unit","个","alarmThreshold",new BigDecimal("100"),"alarmCondition",">","alarmStatus",1))));
    }
    Map<String,Object> row(String value,String time) {
        return new HashMap<>(Map.of("metric_code","alarmCount","metric_name","告警量","sub_category_name","运营成效",
            "unit","个","metric_value",new BigDecimal(value),"data_time",time));
    }
    @Test void validatesZeroAndMultipleObservationTimesWithoutRequiringCategoryColumn() {
        assertDoesNotThrow(()->service.validate(List.of(row("0","2026-10-18T14:05:42"),row("120","2026-10-18T14:06:42"))));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void rejectsDuplicatesUnknownCodesMismatchedDefinitionsAndInvalidCounts() {
        var zero=row("0","2026-10-18T14:05:42");
        assertThrows(IllegalArgumentException.class,()->service.validate(List.of(zero,zero)));
        var unknown=row("1","2026-10-18T14:05:42"); unknown.put("metric_code","unknown");
        assertThrows(IllegalArgumentException.class,()->service.validate(List.of(unknown)));
        var wrong=row("1","2026-10-18T14:05:42"); wrong.put("metric_name","另一个指标");
        assertThrows(IllegalArgumentException.class,()->service.validate(List.of(wrong)));
        assertThrows(IllegalArgumentException.class,()->service.validate(List.of(row("1.5","2026-10-18T14:05:42"))));
        assertThrows(IllegalArgumentException.class,()->service.validate(List.of(row("-1","2026-10-18T14:05:42"))));
    }
    @Test void refreshWritesCollectedValueAndTimestampIntoRealtime() {
        var time=LocalDateTime.of(2026,10,18,14,5,42);
        when(jdbc.queryForList(contains("AS alarmStatus"),eq("businessValue"),eq("businessValue"),isNull(),isNull())).thenReturn(List.of(new HashMap<>(Map.of(
            "metricId",1L,"metricCode","alarmCount","metricName","告警量","categoryName","业务价值",
            "subCategoryName","运营成效","unit","个","alarmThreshold",new BigDecimal("100"),"alarmCondition",">","alarmStatus",1))));
        when(jdbc.query(anyString(),any(RowMapper.class),eq(1L),eq("alarmCount"),eq("alarmCount"))).thenReturn(List.of(new CollectedMetricService.Observation(new BigDecimal("120"),time)));
        service.refresh();
        verify(jdbc).update(contains("INSERT INTO metric_realtime"),eq(1L),eq(new BigDecimal("120")),eq(time),eq(1),eq(1),eq("告警"));
        verify(jdbc).query(contains("ORDER BY data_time DESC"),any(RowMapper.class),eq(1L),eq("alarmCount"),eq("alarmCount"));
    }
    @Test void overviewConnectsHistoricalRowsWithoutMetricIdAndUsesLatestTimeWithinBatch() {
        when(jdbc.queryForList(startsWith("SELECT id,source_file"))).thenReturn(List.of(Map.of("id","batch")));
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM collection_file"),eq(Integer.class),eq("batch"))).thenReturn(1);
        when(jdbc.queryForList(startsWith("SELECT data_json"),eq("batch"))).thenReturn(List.of(
            Map.of("data_json","{\"metric_code\":\"alarmCount\",\"metric_value\":120,\"data_time\":\"2026-10-18T14:06:42\"}"),
            Map.of("data_json","{\"metric_code\":\"alarmCount\",\"metric_value\":0,\"data_time\":\"2026-10-18T14:05:42\"}")));
        var result=service.overview(null);
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertEquals(1,rows.size()); assertEquals(120,rows.get(0).get("metricValue"));
        assertEquals("业务价值",rows.get(0).get("categoryName"));
        assertEquals("2026-10-18T14:06:42",rows.get(0).get("dataTime"));
    }
    @Test void missingThresholdStillPublishesExactZeroWithoutAlarm() {
        var time=LocalDateTime.of(2026,11,17,0,0);
        when(jdbc.queryForList(contains("AS alarmStatus"),eq("businessValue"),eq("businessValue"),isNull(),isNull()))
            .thenReturn(List.of(new HashMap<>(Map.of("metricId",1L,"metricCode","alarmCount","categoryCode","businessValue"))));
        when(jdbc.query(anyString(),any(RowMapper.class),eq(1L),eq("alarmCount"),eq("alarmCount")))
            .thenReturn(List.of(new CollectedMetricService.Observation(BigDecimal.ZERO,time)));
        service.refresh();
        verify(jdbc).update(contains("INSERT INTO metric_realtime"),eq(1L),eq(BigDecimal.ZERO),eq(time),eq(0),eq(0),eq("待配置"));
    }

    @Test void missingValueStaysNullInsteadOfBeingConvertedToZero() {
        var time=LocalDateTime.of(2026,11,17,0,0);
        when(jdbc.query(anyString(),any(RowMapper.class),eq(1L),eq("alarmCount"),eq("alarmCount")))
            .thenReturn(List.of(new CollectedMetricService.Observation(null,time)));
        service.refresh();
        verify(jdbc).update(contains("INSERT INTO metric_realtime"),eq(1L),isNull(),eq(time),eq(0),eq(0),eq("缺数"));
    }
    @Test void batchFromAnotherSourceCannotBeRead() {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("foreign-batch"))).thenReturn(0);
        assertThrows(IllegalArgumentException.class,()->service.overview("foreign-batch"));
    }
    @Test void efficiencySourceMatchesOnlyItsOwnCatalogAndAllowsUnspecifiedUnit() {
        var definition=new HashMap<String,Object>();
        definition.put("metricCode","EFF_GREEN_003"); definition.put("metricName","H5缴费接口平均时长");
        definition.put("categoryName","运行效率"); definition.put("subCategoryName","运行效率-接口效率-渠道缴费"); definition.put("unit",null);
        when(jdbc.queryForList(contains("AS alarmStatus"),eq("system_run"),eq("system_run"),isNull(),isNull())).thenReturn(List.of(definition));
        var row=new HashMap<String,Object>(); row.put("metric_code","EFF_GREEN_003"); row.put("metric_name","H5缴费接口平均时长");
        row.put("metric_value",new BigDecimal("1.2345")); row.put("data_time","2026-10-19T09:00:00"); row.put("unit",null);
        assertDoesNotThrow(()->service.validate("running_efficiency",List.of(row)));
        assertThrows(IllegalArgumentException.class,()->service.validate("business_value",List.of(row)));
        row.put("unit","秒");
        assertThrows(IllegalArgumentException.class,()->service.validate("running_efficiency",List.of(row)));
    }
}
