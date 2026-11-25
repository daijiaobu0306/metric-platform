package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplaintReportStatusTest {
    @TempDir Path root;

    @Test void overviewReflectsAutomaticFailureAndRecoveryWithoutManualScan() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var paths = mock(BasePathProvider.class);
        when(paths.getBasePath()).thenReturn(root.toString());
        var service = new ComplaintReportService(jdbc, mock(TransactionTemplate.class), new ObjectMapper(), paths);
        service.initialize();
        Map<String, Object> recovered = new HashMap<>(); recovered.put("last_error", null);
        when(jdbc.queryForList("SELECT last_error FROM collection_source WHERE code='satisfaction_report'"))
            .thenReturn(List.of(Map.of("last_error", "file.xlsx: invalid value")), List.of(recovered));
        assertEquals(List.of("file.xlsx: invalid value"), service.overview(null).get("errors"));
        assertEquals(List.of(), service.overview(null).get("errors"));
    }
}
