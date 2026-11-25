package com.metric.observe.service;

import com.metric.observe.dto.DashboardPolicy.Scope;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardScopeTest {
    private final Map<String, DashboardScope.Node> catalog = new LinkedHashMap<>() {{
        put("system:1", node("system:1", null, "system"));
        put("category:2", node("category:2", "system:1", "category"));
        put("subcategory:3", node("subcategory:3", "category:2", "subcategory"));
        put("metric:4", node("metric:4", "subcategory:3", "metric"));
        put("metric:5", node("metric:5", "subcategory:3", "metric"));
    }};

    private DashboardScope.Node node(String key, String parent, String type) {
        return new DashboardScope.Node(key, parent, key, type, 1L, 1L, 2L, "大类", "小类");
    }

    @Test void explicitMetricNeverPromotesItsAncestors() {
        Set<String> actual = DashboardScope.resolve(List.of(new Scope(List.of("metric:4"), List.of())), catalog);
        assertEquals(Set.of("metric:4"), actual);
    }

    @Test void categoryExpandsOnlyDownward() {
        Set<String> actual = DashboardScope.resolve(List.of(new Scope(List.of("category:2"), List.of())), catalog);
        assertEquals(Set.of("category:2", "subcategory:3", "metric:4", "metric:5"), actual);
        assertFalse(actual.contains("system:1"));
    }

    @Test void exclusionAlsoHidesAggregatesThatContainExcludedData() {
        Set<String> actual = DashboardScope.resolve(
                List.of(new Scope(List.of("category:2"), List.of("metric:4"))), catalog);
        assertEquals(Set.of("metric:5"), actual);
    }
}
