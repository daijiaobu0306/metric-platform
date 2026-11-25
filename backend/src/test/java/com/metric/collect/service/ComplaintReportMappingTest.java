package com.metric.collect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ComplaintReportMappingTest {
    private List<ComplaintReportMapping.Result> mapped() throws Exception {
        var catalog = new ObjectMapper().readTree(getClass().getResourceAsStream("/local/satisfaction-final-catalog.json"));
        Path sample = Path.of("doc/原始数据文件样例/满意度/投诉工单效能报表_20260929113832_5153.xlsx");
        if (!Files.exists(sample)) sample = Path.of("..").resolve(sample);
        try (var input = Files.newInputStream(sample)) {
            var report = ComplaintReportParser.parse(input);
            List<ComplaintReportMapping.Result> results = new ArrayList<>();
            for (JsonNode metric : catalog.path("metrics")) results.add(ComplaintReportMapping.map(report, metric));
            return results;
        }
    }
    private ComplaintReportMapping.Result find(List<ComplaintReportMapping.Result> rows, String group, String name) {
        return rows.stream().filter(row -> row.metric().path("groupName").asText().startsWith(group)
            && row.metric().path("name").asText().equals(name)).findFirst().orElseThrow();
    }
    @Test void finalCatalogProducesExactlySeventeenResultsWithTenAvailable() throws Exception {
        var rows = mapped();
        assertEquals(17, rows.size());
        assertEquals(17, rows.stream().map(row -> row.metric().path("code").asText()).distinct().count());
        assertEquals(10, rows.stream().filter(row -> row.value() != null).count());
        for (String group : List.of("客服", "倒三角", "综调"))
            assertEquals(group.equals("综调") ? 5 : 6, rows.stream().filter(row -> row.metric().path("groupName").asText().startsWith(group)).count());
        assertEquals(new BigDecimal("9"), find(rows, "客服", "96小时超时量").value());
        assertEquals(new BigDecimal("85.08"), find(rows, "客服", "一次解决率").value());
        assertEquals(new BigDecimal("93.2249"), find(rows, "客服", "及时率").value());
        assertNull(find(rows, "客服", "重投率").value());
        assertEquals("PENDING_SCOPE", find(rows, "客服", "重投率").status());
    }
    @Test void triangleOnlySumsThreeTypesAndNeverAveragesRatios() throws Exception {
        var rows = mapped();
        assertEquals(new BigDecimal("733"), find(rows, "倒三角", "工单累计量").value());
        assertEquals(new BigDecimal("98.3380"), find(rows, "倒三角", "及时率").value());
        assertEquals(new BigDecimal("0.2770"), find(rows, "倒三角", "回退率").value());
        for (String name : List.of("满意率", "重投率", "一次解决率")) assertNull(find(rows, "倒三角", name).value());
        assertEquals("B3,B4,B5", find(rows, "倒三角", "工单累计量").cells());
    }
    @Test void dispatchUsesDispatchRowAndPreservesThreeMissingValues() throws Exception {
        var rows = mapped();
        assertEquals(new BigDecimal("831"), find(rows, "综调", "工单累计量").value());
        assertEquals(new BigDecimal("100.0000"), find(rows, "综调", "及时率").value());
        for (String name : List.of("满意率", "回退率", "一次解决率")) {
            var row = find(rows, "综调", name);
            assertNull(row.value()); assertEquals(name.equals("回退率") ? "PENDING_DENOMINATOR" : "SOURCE_MISSING", row.status());
            assertEquals("综调", row.scope());
        }
    }
}
