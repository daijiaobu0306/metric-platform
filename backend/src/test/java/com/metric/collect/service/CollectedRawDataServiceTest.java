package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CollectedRawDataServiceTest {
    @Test void largePreviewPageReturnsEmptyRowsWithoutIntegerOverflow() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new CollectedRawDataService(jdbc, mock(CollectionStorageRoots.class));
        when(jdbc.queryForList(contains("FROM collection_file WHERE source_code=?"), eq("quality")))
            .thenReturn(List.of(Map.of("id", "batch-1", "archiveFile", "")));
        when(jdbc.queryForList(contains("FROM collection_row WHERE batch_id=?"), eq("batch-1")))
            .thenReturn(List.of(Map.of("data_json", "{\"metric_value\":\"0\"}")));
        var result = service.preview("数据质量", "quality", null, Integer.MAX_VALUE, 500);
        assertEquals(List.of(), result.get("rows"));
        assertEquals(1, result.get("total"));
    }

    @Test void multiDayFileIsFilteredByObservationDate() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new CollectedRawDataService(jdbc,mock(CollectionStorageRoots.class));
        when(jdbc.queryForList(contains("SELECT m.id,m.metric_code"),eq("运行效率")))
            .thenReturn(List.of(Map.of("id",7L,"code","SR_TEST","name","性能指标")));
        when(jdbc.queryForList(contains("AND DATE(REPLACE"),eq(7L),eq("SR_TEST"),eq("2026-11-15")))
            .thenReturn(List.of(Map.of("data_json","{\"metric_value\":\"0\",\"data_time\":\"2026-11-15T00:00:00\"}",
                "business_date","2026-11-16")));
        assertEquals(1,service.records("运行效率",7L,"2026-11-15",null,1,50).get("total"));
        verify(jdbc,never()).queryForList(contains("AND f.business_date=?"),eq(7L),eq("SR_TEST"),eq("2026-11-15"));
    }

    @Test void archiveReplayIncludesOnlyTheSelectedObservationTime() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var roots=mock(CollectionStorageRoots.class);
        var service=new CollectedRawDataService(jdbc,roots);
        var root=java.nio.file.Files.createTempDirectory("collection-replay-");
        var archive=root.resolve("archive");java.nio.file.Files.createDirectory(archive);
        var csv=archive.resolve("mixed.csv");
        try {
            java.nio.file.Files.writeString(csv,"dataset,data_time,_empty,业务名称\n查询,2026-11-15T00:00:00,0,A\n查询,2026-11-16T00:00:00,0,B\n");
            when(roots.forSource("running_efficiency")).thenReturn(root.toString());
            when(jdbc.queryForList(contains("SELECT m.id,m.metric_code"),eq("运行效率")))
                .thenReturn(List.of(Map.of("id",7L,"code","SR_TEST","name","性能指标")));
            when(jdbc.queryForList(contains("metric_code_canonical_digest"),eq(7L),eq("SR_TEST")))
                .thenReturn(List.of(Map.of("data_json","{\"dataset\":\"查询\",\"data_time\":\"2026-11-15T00:00\"}",
                    "source_code","running_efficiency","archive_file","mixed.csv")));
            when(jdbc.queryForList(contains("SELECT config_json"),eq(String.class),eq("running_efficiency")))
                .thenReturn(List.of("{\"archivePath\":\"archive\"}"));
            var result=service.records("运行效率",7L,null,null,1,50);
            assertEquals(1,result.get("total"));
            assertEquals("A",((List<Map<String,Object>>)result.get("rows")).get(0).get("业务名称"));
        } finally {
            java.nio.file.Files.deleteIfExists(csv);java.nio.file.Files.deleteIfExists(archive);java.nio.file.Files.deleteIfExists(root);
        }
    }
    @Test void csvQuotedNewlineAndEscapedQuoteStayInOneRecord() {
        assertEquals(2,CollectedRawDataService.csvRecords("a,b\r\n1,\"line\nnext \"\"word\"\"\"\r\n").size());
        assertThrows(IllegalArgumentException.class,()->CollectedRawDataService.csvRecords("a,\"broken"));
    }
    @Test void businessAndStepSelectionsMatchParser() throws Exception {
        var mapper=new ObjectMapper();
        var rule=mapper.readTree("{\"businessContains\":\"chbn\",\"businessMatches\":true,\"stepType\":\"页面加载\"}");
        assertTrue(CollectedRawDataService.matchesSelection(Map.of("业务名称","CHBN业务","步骤名称","进入页面"),rule));
        assertFalse(CollectedRawDataService.matchesSelection(Map.of("业务名称","普通业务","步骤名称","进入页面"),rule));
        assertFalse(CollectedRawDataService.matchesSelection(Map.of("业务名称","CHBN业务","步骤类型","步骤时长","步骤名称","进入页面"),rule));
    }
    @Test void selectedMetricReadsItsOwnLatestRecordAndPreservesZero() {
        var jdbc=mock(JdbcTemplate.class);var service=new CollectedRawDataService(jdbc,mock(CollectionStorageRoots.class));
        when(jdbc.queryForList(contains("SELECT m.id,m.metric_code"),eq("运行效率")))
            .thenReturn(List.of(Map.of("id",7L,"code","SR_TEST","name","性能指标","subcategory","系统性能","unit","次")));
        when(jdbc.queryForList(contains("metric_code_canonical_digest"),eq(7L),eq("SR_TEST")))
            .thenReturn(List.of(Map.of("data_json","{\"metric_value\":\"0\",\"data_time\":\"2026-11-17T00:00:00\"}","collected_at","2026-11-18 08:00:00")));
        var result=service.records("运行效率",7L,null,null,1,50);
        assertEquals(1,result.get("total"));
        assertEquals("0",((List<Map<String,Object>>)result.get("rows")).get(0).get("指标值"));
        verify(jdbc).queryForList(contains("metric_code_canonical_digest"),eq(7L),eq("SR_TEST"));
        assertThrows(IllegalArgumentException.class,()->service.records("运行效率",99L,null,null,1,50));
    }
    @Test void missingArchiveIsExplicitlyASampleAndFiltersBeforePaging() {
        var jdbc=mock(JdbcTemplate.class);var service=new CollectedRawDataService(jdbc,mock(CollectionStorageRoots.class));
        when(jdbc.queryForList(contains("SELECT m.id,m.metric_code"),eq("对内部人员")))
            .thenReturn(List.of(Map.of("id",8L,"code","INNER_TEST","name","查询","subcategory","查询类")));
        when(jdbc.queryForList(contains("metric_code_canonical_digest"),eq(8L),eq("INNER_TEST")))
            .thenReturn(List.of(Map.of("data_json","{\"dataset\":\"查询\",\"details\":[{\"业务名称\":\"A\",\"_empty\":\"0\"},{\"业务名称\":\"B\"}]}","source_code","internal_staff","archive_file","")));
        var result=service.records("对内部人员",8L,null,"B",1,1);
        assertEquals(1,result.get("total"));
        assertTrue(result.get("note").toString().contains("样本"));
        assertEquals(List.of("业务名称"),result.get("columns"));
    }
}
