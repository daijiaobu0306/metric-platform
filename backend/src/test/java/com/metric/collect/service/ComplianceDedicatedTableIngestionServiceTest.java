package com.metric.collect.service;

import com.metric.metric.entity.SystemJhdDetail;
import com.metric.metric.repository.SystemJhdRepository;
import com.metric.metric.repository.SystemJhdDetailRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplianceDedicatedTableIngestionServiceTest {
    @Test void historicalDeliveryCannotReopenClosedOrderButNewOrdersStillImport() {
        var repository=mock(SystemJhdDetailRepository.class);
        var service=new ComplianceDedicatedTableIngestionService(mock(SystemJhdRepository.class),repository);
        var existing=new SystemJhdDetail(); existing.setId(1); existing.setWsId("order");
        existing.setSourceFile("system_jhd_detail_20261117.txt"); existing.setStatus("已关闭");
        when(repository.findByWsIdAndAuditId("order","audit")).thenReturn(Optional.of(existing));
        var stale=Map.<String,Object>of("ws_id","order","audit_id","audit","status","处理中");
        var fresh=Map.<String,Object>of("ws_id","new","audit_id","audit","status","处理中");
        assertEquals(1,service.storeDetail("system_jhd_detail_20261115.txt",List.of(stale,fresh)));
        assertEquals("已关闭",existing.getStatus());
        verify(repository,never()).save(existing);
        verify(repository).save(argThat(row -> "new".equals(row.getWsId())));
    }

    @Test void sameDayOlderSourceTimestampDoesNotOverwriteButCorrectionCanUpdate() {
        var repository=mock(SystemJhdDetailRepository.class);
        var service=new ComplianceDedicatedTableIngestionService(mock(SystemJhdRepository.class),repository);
        var existing=new SystemJhdDetail(); existing.setId(1);
        existing.setSourceFile("system_jhd_detail_20261117.txt");
        existing.setUpdateTime("2026-11-17 12:00:00"); existing.setStatus("已关闭");
        when(repository.findByWsIdAndAuditId("order","audit")).thenReturn(Optional.of(existing));
        assertEquals(0,service.storeDetail("system_jhd_detail_20261117.txt",List.of(Map.of(
            "ws_id","order","audit_id","audit","status","处理中","update_time","2026-11-17T11:00:00"))));
        assertEquals(1,service.storeDetail("system_jhd_detail_20261117.txt",List.of(Map.of(
            "ws_id","order","audit_id","audit","status","整改完成","update_time","2026-11-17T13:00:00"))));
        assertEquals("整改完成",existing.getStatus());
    }

    @Test void nextDayDeliveryWithoutTimestampsRetainsExistingImportCompatibility() {
        var existing=new SystemJhdDetail(); existing.setSourceFile("system_jhd_detail_20261116.txt");
        assertFalse(ComplianceDedicatedTableIngestionService.olderDetail("system_jhd_detail_20261117.txt",Map.of(),existing));
    }
}
