package com.metric.observe.service;

import com.metric.observe.dto.DashboardPolicy.Scope;
import java.util.*;

/** Explicit ancestor grants expand downward only. Coverage never promotes ancestors. */
public final class DashboardScope {
    private DashboardScope() {}
    public record Node(String key, String parent, String label, String type, Long id,
                       Long systemId, Long categoryId, String categoryName, String subCategoryName) {}

    public static Set<String> resolve(Collection<Scope> grants, Map<String, Node> catalog) {
        Set<String> result = new LinkedHashSet<>();
        for (Scope grant : grants) {
            for (Node node : catalog.values()) {
                List<String> ancestors = ancestors(node.key(), catalog);
                boolean included = ancestors.stream().anyMatch(grant.include()::contains);
                boolean excluded = ancestors.stream().anyMatch(grant.exclude()::contains);
                // A stored parent score contains every descendant. If this grant excludes any
                // descendant, hiding the parent avoids exposing a score calculated with that data.
                boolean aggregateContainsExcludedData = grant.exclude().stream()
                        .anyMatch(exclusion -> ancestors(exclusion, catalog).contains(node.key()));
                if (included && !excluded && !aggregateContainsExcludedData) result.add(node.key());
            }
        }
        return result;
    }
    public static List<String> ancestors(String key, Map<String, Node> catalog) {
        List<String> result = new ArrayList<>();
        while (key != null && !result.contains(key)) {
            result.add(key);
            Node n = catalog.get(key);
            key = n == null ? null : n.parent();
        }
        return result;
    }
}
