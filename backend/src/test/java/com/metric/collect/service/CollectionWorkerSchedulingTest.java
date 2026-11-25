package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.service.CollectedMetricService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionWorkerSchedulingTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final CollectionWorkerService workers=new CollectionWorkerService(jdbc,new ObjectMapper(),
        mock(CollectionScriptCatalog.class),mock(CollectionDirectoryGuard.class),mock(CollectedMetricService.class));
    private static final String LOCK="SELECT config_json,enabled FROM collection_source WHERE code=? FOR UPDATE";

    @BeforeEach void setup() {
        when(jdbc.queryForList(LOCK,"test")).thenReturn(List.of(Map.of("config_json","{\"code\":\"test\",\"parser\":\"python-worker\",\"enabled\":true}","enabled",1)));
        when(jdbc.queryForObject(contains("state='CLAIMED'"),eq(Integer.class),eq("test"))).thenReturn(0);
    }

    @Test void deletedTaskIsRecheckedUnderRowLockAndCannotBeRequeued() {
        when(jdbc.queryForList(LOCK,"test")).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,()->workers.requestScan("test"));
        verify(jdbc).queryForList(LOCK,"test");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void disabledTaskCannotBeRequeued() {
        when(jdbc.queryForList(LOCK,"test")).thenReturn(List.of(Map.of("config_json","{\"code\":\"test\",\"enabled\":false}","enabled",0)));
        assertThrows(IllegalArgumentException.class,()->workers.requestScan("test"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void activeLeasePreventsAnotherScanRequest() {
        when(jdbc.queryForObject(contains("state='CLAIMED'"),eq(Integer.class),eq("test"))).thenReturn(1);
        assertThrows(IllegalStateException.class,()->workers.requestScan("test"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void idleTaskIsQueuedOnlyAfterConfigurationAndLeaseChecks() {
        workers.requestScan("test");
        var ordered=inOrder(jdbc);
        ordered.verify(jdbc).queryForList(LOCK,"test");
        ordered.verify(jdbc).queryForObject(contains("state='CLAIMED'"),eq(Integer.class),eq("test"));
        ordered.verify(jdbc).update("UPDATE collection_source SET next_scan_at=NOW() WHERE code=?","test");
        verify(jdbc,times(1)).update(anyString(),any(Object[].class));
    }
}
