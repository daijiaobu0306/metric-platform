package com.metric.observe.service;

import com.metric.metric.service.MetricAudienceService;
import com.metric.metric.service.MetricObservationReadService;
import com.metric.observe.dto.DashboardPolicy;
import com.metric.observe.entity.DashboardViewConfig;
import com.metric.observe.repository.DashboardViewConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardWorkspaceLoadingTest {
    private final DashboardAccessService access = mock(DashboardAccessService.class);
    private final DashboardViewConfigRepository repository = mock(DashboardViewConfigRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MetricObservationReadService reads = mock(MetricObservationReadService.class);
    private DashboardWorkspaceService service;

    @BeforeEach void setup() {
        service = new DashboardWorkspaceService(access, repository, jdbc,
            mock(HealthSnapshotQueryService.class), reads);
        var view = new DashboardViewConfig();
        view.setViewName("测试专屏");
        when(access.requireView("test", null)).thenReturn(view);
        when(access.policy(view)).thenReturn(new DashboardPolicy(1, "supervisor", List.of(), List.of(), List.of(), "split", List.of()));
        when(access.catalog()).thenReturn(Map.of());
        when(access.userId()).thenReturn(7L);
        when(access.scope(eq(view), eq(7L), anyMap())).thenReturn(new HashSet<>());
    }

    @Test void lightweightOverviewDoesNotWaitForMetricHistory() {
        var result = (Map<?, ?>) service.overview("test", 7, null, null, false);
        assertTrue(result.containsKey("counts"));
        assertFalse(result.containsKey("dataSources"));
        verifyNoInteractions(reads, jdbc);
    }

    @Test void leaderOverviewDoesNotReadPeriodDataBeforeFirstPaint() {
        var view = access.requireView("test", null);
        when(access.policy(view)).thenReturn(new DashboardPolicy(1, "leader", List.of(), List.of(),
            List.of("data", "health"), "split", List.of()));
        var result = (Map<?, ?>) service.overview("test", 7, null, null, false);
        assertEquals("leader", result.get("template"));
        assertFalse(result.containsKey("dataMetrics"));
        verifyNoInteractions(reads, jdbc);
    }

    @Test void catalogTabDoesNotReadMetricValuesOrPeriodHistory() {
        var result = (Map<?, ?>) service.metricPanel("test", 7, null, null, "catalog");
        assertTrue(result.containsKey("catalogMetrics"));
        assertFalse(result.containsKey("dataSources"));
        verifyNoInteractions(reads);
    }

    @Test void existingOverviewCallRetainsFullMetricFields() {
        var result = (Map<?, ?>) service.overview("test", 7, null, null);
        assertTrue(result.keySet().containsAll(List.of("catalogMetrics", "dataSources", "strategies")));
        verify(reads).latestWithPeriodStatistics(anyList(), any(), any());
    }

    @Test void panelRequestCannotBypassViewAuthorization() {
        when(access.requireView("test", null)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        var error = assertThrows(ResponseStatusException.class,
            () -> service.metricPanel("test", 7, null, null, "data"));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(reads, jdbc);
    }

    @Test void sharedCustomViewKeepsConfiguredTabsInOrder() {
        configureSharedView("supervisor", List.of("data"), List.of("sources", "overview", "data"));

        var result = (Map<?, ?>) service.sharePanel("custom", 7, "summary");

        assertEquals(List.of("sources", "overview", "data"), result.get("tabs"));
        assertEquals(Map.of("sources", "sources", "overview", "closure", "data", "data"), result.get("tabPanels"));
        verifyNoInteractions(reads, jdbc);
    }

    @Test void hiddenSharedPanelsCannotBeReadThroughDirectOrLegacyRequests() {
        configureSharedView("supervisor", List.of("health", "events"), List.of("catalog"));

        for (String panel : List.of("closure", "health", "data", "sources", "strategy", "operations")) {
            var error = assertThrows(ResponseStatusException.class, () -> service.sharePanel("custom", 7, panel));
            assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.shareHealthCards("custom", 7)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.shareEvents("custom", "alarm", 7, 0)).getStatusCode());
        var overview = (Map<?, ?>) service.shareOverview("custom", 7);
        assertTrue(overview.containsKey("catalogMetrics"));
        assertFalse(overview.containsKey("strategies"));
        assertFalse(overview.containsKey("alarm"));
        verifyNoInteractions(reads);
    }

    @Test void sharedTabsHonorTemplateAndModuleAvailability() {
        configureSharedView("leader", List.of("health"), List.of("overview", "risk", "outcome"));
        var leader = (Map<?, ?>) service.sharePanel("custom", 7, "summary");
        assertEquals(List.of("overview", "risk"), leader.get("tabs"));

        configureSharedView("leader", List.of("events"), List.of("overview"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.sharePanel("custom", 7, "closure")).getStatusCode());

        configureSharedView("operator", List.of("operations", "events"), List.of("events", "operations", "data"));
        var operator = (Map<?, ?>) service.sharePanel("custom", 7, "summary");
        assertEquals(Map.of("events", "closure", "operations", "operations"), operator.get("tabPanels"));
        verifyNoInteractions(reads, jdbc);
    }

    private void configureSharedView(String template, List<String> modules, List<String> tabs) {
        var view = new DashboardViewConfig();
        view.setViewName("自定义专屏");
        view.setStatus(1);
        when(repository.findByViewCode("custom")).thenReturn(Optional.of(view));
        when(access.policy(view)).thenReturn(new DashboardPolicy(1, template, List.of(), List.of(), modules, "split", tabs));
    }
}
