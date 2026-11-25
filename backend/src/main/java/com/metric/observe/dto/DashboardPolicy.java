package com.metric.observe.dto;

import java.util.List;

/** Versioned dedicated-screen policy stored in the existing layout_config column. */
public record DashboardPolicy(int version, String template, List<Grant> grants,
                              List<Long> supervisorIds, List<String> modules,
                              String layoutMode, List<String> tabs) {
    public record Scope(List<String> include, List<String> exclude) {
        public Scope {
            include = include == null ? List.of() : List.copyOf(include);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }
    }
    public record Grant(String subjectType, Long subjectId, Scope scope) {
        public Grant { scope = scope == null ? new Scope(null, null) : scope; }
    }
    public DashboardPolicy {
        grants = grants == null ? List.of() : List.copyOf(grants);
        supervisorIds = supervisorIds == null ? List.of() : List.copyOf(supervisorIds);
        modules = modules == null ? List.of() : List.copyOf(modules);
        layoutMode = layoutMode == null || layoutMode.isBlank() ? "split" : layoutMode;
        tabs = tabs == null || tabs.isEmpty() ? defaultTabs(template) : List.copyOf(tabs);
    }
    /** Allowed navigation keys per template. */
    public static List<String> allowedTabs(String template) {
        return switch (template == null ? "" : template) {
            case "manager" -> List.of("health-observation","situation","catalog","data","sources","strategy","supervisors","team");
            case "supervisor" -> List.of("overview","catalog","data","sources","strategy");
            case "leader" -> List.of("overview","risk","outcome");
            case "operator" -> List.of("overview","data","events","operations");
            default -> List.of("overview");
        };
    }
    /** Default navigation for policies saved before tabs existed. */
    public static List<String> defaultTabs(String template) {
        return allowedTabs(template);
    }
}
